import asyncio
import io
import zipfile

import pytest
from PIL import Image

from conftest import delete, marker, pli
from takgram import cot
from takgram.cottypes import describe
from takgram.datapackage import connection_package, object_package
from takgram.icons import IconResolver
from takgram.ratequeue import OutboundQueue, RetryAfter
from takgram.render import CAPTION_LIMIT, caption, keyboard, mgrs_of, render_card
from takgram.store import Store


def test_parse_pli():
    ev = cot.parse(pli())
    assert ev.kind == "unit" and ev.callsign == "ALPHA-2" and ev.team == "Cyan"
    assert ev.lat == pytest.approx(30.26715) and ev.hae == pytest.approx(149.2) and ev.ce == pytest.approx(8.5)
    assert ev.course == 45.0 and ev.endpoint == "192.168.1.20:4242:tcp"


def test_parse_marker_and_delete():
    ev = cot.parse(marker())
    assert ev.kind == "marker" and ev.hae is None and ev.ce is None
    assert ev.remarks == "Seen <moving> north"
    d = cot.parse(delete("m-1"))
    assert d.kind == "delete" and d.delete_uid == "m-1"


def test_chat_roundtrip_broadcast_and_direct():
    ev = cot.parse(cot.build_chat("U1", "ALPHA", "hello <all>"))
    assert ev.kind == "chat" and not ev.chat.is_direct and ev.chat.text == "hello <all>"
    assert ev.chat.sender_uid == "U1" and ev.chat.sender_callsign == "ALPHA"
    dm = cot.parse(cot.build_chat("U1", "ALPHA", "share on", "BRIDGE", "TAKgram"))
    assert dm.chat.is_direct and dm.chat.to_uid == "BRIDGE" and dm.dest_callsigns == ["TAKgram"]


@pytest.mark.parametrize("bad", [
    b"", b"\xbf\x01\xbf\x00", b"<notevent/>", b"<event uid='a'/>",
    b'<event uid="a" type="a-f-G"><point lat="95" lon="0"/></event>',
    b'<!DOCTYPE x [<!ENTITY a "aaaa">]><event uid="&a;" type="a-f-G"><point lat="1" lon="1"/></event>',
])
def test_rejects_bad_input(bad):
    with pytest.raises(cot.CotError):
        cot.parse(bad)


def test_protobuf_is_named():
    with pytest.raises(cot.ProtobufNotSupported):
        cot.parse(b"\xbf\x01\xbf\x0a\x00")


def test_stream_splitter_handles_fragments_and_declarations():
    s = cot.StreamSplitter()
    data = pli() + marker()
    out = []
    for i in range(0, len(data), 37):
        out += s.feed(data[i:i + 37])
    assert len(out) == 2 and all(o.startswith(b"<event") for o in out)
    assert cot.parse(out[1]).uid == "m-1"


@pytest.mark.parametrize("code,text", [
    ("a-f-G-U-C", "Friendly ground combat unit"),
    ("a-h-G-U-C-A", "Hostile ground armor"),
    ("a-u-G", "Unknown ground track"),
    ("a-n-A-C-F", "Neutral air civil fixed-wing"),
    ("a-f-A-M-F-Q", "Friendly air drone"),
    ("b-m-p-s-m", "Spot marker"),
    ("b-a-o-tbl", "Emergency: 911 alert"),
    ("b-r-f-h-c", "CASEVAC request"),
    ("x-y-z", "Object (x-y-z)"),
])
def test_describe(code, text):
    assert describe(code) == text


def test_mgrs_known_point_and_polar_gap():
    # Washington Monument, a commonly published MGRS reference: 18S UJ 2337 0651 (10 m precision ~).
    g = mgrs_of(38.8895, -77.0353)
    assert g.startswith("18S UJ 23") and len(g.replace(" ", "")) == 13
    assert mgrs_of(88.0, 10.0) == ""


def test_caption_has_what_a_reader_needs_and_fits():
    ev = cot.parse(marker(remarks="x" * 3000))
    cap = caption(ev, "mesh")
    assert len(cap) <= CAPTION_LIMIT
    assert "<code>30.30000, -97.70000</code>" in cap and "MGRS" in cap
    assert "Hostile ground armor" in cap and "#takobj uid=m-1" in cap
    assert "&lt;" not in caption(cot.parse(marker(remarks="plain")), "")
    assert "Seen &lt;moving&gt; north" in caption(cot.parse(marker()), "")
    kb = keyboard(ev)["inline_keyboard"]
    assert "30.300000,-97.700000" in kb[0][0]["url"] and len(kb[1][0]["callback_data"]) <= 64


@pytest.mark.parametrize("xml", [pli(), marker(), marker(typ="b-m-p-s-m"), marker(typ="b-a-o-tbl"),
                                 marker(typ="a-u-G"), marker(typ="a-n-G")])
def test_render_card_offline_is_a_png(xml):
    png = render_card(cot.parse(xml), IconResolver(), None)
    img = Image.open(io.BytesIO(png))
    assert img.size == (640, 400)


def test_icon_resolver_refuses_paths_outside_icons_dir(tmp_path):
    (tmp_path / "icons").mkdir()
    secret = tmp_path / "secret.png"
    Image.new("RGBA", (8, 8), (255, 0, 0, 255)).save(secret)
    ev = cot.parse(marker())
    ev.iconsetpath = "../secret.png"
    r = IconResolver(str(tmp_path / "icons"))
    assert r._file(ev.iconsetpath) is None


def test_store_latest_wins_and_sharing_default_off():
    s = Store()
    _, ok = s.upsert(cot.parse(marker(lat=1.0, offset_s=0)), "mesh")
    assert ok
    _, ok = s.upsert(cot.parse(marker(lat=2.0, offset_s=-60)), "mesh")
    assert not ok and s.get("m-1").lat == 1.0
    _, ok = s.upsert(cot.parse(marker(lat=3.0, offset_s=60)), "mesh")
    assert ok and s.get("m-1").lat == 3.0
    assert s.sharing("ANDROID-1") is False
    s.set_sharing("ANDROID-1", True)
    assert s.sharing("ANDROID-1") is True


def test_data_packages():
    z = zipfile.ZipFile(io.BytesIO(connection_package("10.0.0.5", 8087)))
    assert "MANIFEST/manifest.xml" in z.namelist()
    assert b"10.0.0.5:8087:tcp" in z.read("takgram.pref")
    ev = cot.parse(marker())
    z = zipfile.ZipFile(io.BytesIO(object_package(ev)))
    cot_name = next(n for n in z.namelist() if n.endswith(".cot"))
    assert cot.parse(z.read(cot_name).split(b"?>", 1)[1]).uid == "m-1"
    assert b'value="m-1"' in z.read("MANIFEST/manifest.xml")


async def _drain(q, seconds=0.3):
    task = asyncio.create_task(q.run_forever())
    await asyncio.sleep(seconds)
    task.cancel()


async def test_queue_coalesces_and_orders_by_priority():
    q = OutboundQueue(chat_interval=0.0, global_interval=0.0)
    ran = []

    def job(tag):
        async def run():
            ran.append(tag)
        return run

    q.submit(1, "a", job("a-old"))
    q.submit(1, "b", job("b"))
    q.submit(1, "a", job("a-new"))
    q.submit(1, "urgent", job("urgent"), priority=0)
    await _drain(q)
    assert ran == ["urgent", "a-new", "b"]


async def test_queue_retries_after_rate_limit_and_respects_interval():
    q = OutboundQueue(chat_interval=0.05, global_interval=0.0)
    times, state = [], {"first": True}

    async def run():
        if state["first"]:
            state["first"] = False
            raise RetryAfter(0.1)
        times.append(asyncio.get_running_loop().time())

    q.submit(1, "x", run)
    q.submit(1, None, run)
    q.submit(1, None, run)
    await _drain(q, 1.2)
    assert len(times) == 3 and len(q) == 0
    assert all(b - a >= 0.045 for a, b in zip(times, times[1:]))
