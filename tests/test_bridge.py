import asyncio

from conftest import delete, marker, pli
from takgram import cot
from takgram.bridge import Bridge
from takgram.render import callback_key
from takgram.tak.endpoint import Endpoint
from takgram.telegram import TelegramError


async def settle(bridge, seconds=0.25):
    task = asyncio.create_task(bridge.queue.run_forever())
    await asyncio.sleep(seconds)
    task.cancel()


class FakeClient:
    def __init__(self):
        self.sent = []

    def send(self, xml):
        self.sent.append(xml)


async def test_marker_becomes_one_card_then_is_edited_then_removed(cfg, bot):
    b = Bridge(cfg, bot)
    assert await b.on_cot(cot.parse(marker()), "mesh")
    await settle(b)
    assert bot.methods() == ["sendPhoto"]
    _, params, files = bot.calls[0]
    assert params["chat_id"] == -100 and "Tank 1" in params["caption"] and files["photo"][1][:4] == b"\x89PNG"

    await b.on_cot(cot.parse(marker(lat=30.4, offset_s=5)), "mesh")
    await settle(b)
    assert bot.methods() == ["sendPhoto", "editMessageMedia"]
    assert "30.40000" in bot.calls[1][1]["media"]["caption"]

    await b.on_cot(cot.parse(delete("m-1")), "mesh")
    await settle(b)
    assert bot.methods()[-1] == "editMessageCaption" and "REMOVED" in bot.calls[-1][1]["caption"]


async def test_duplicate_and_rapid_updates_do_not_create_two_cards(cfg, bot):
    b = Bridge(cfg, bot)
    first = cot.parse(marker())
    await b.on_cot(first, "mesh")
    assert await b.on_cot(first, "endpoint") is False
    await b.on_cot(cot.parse(marker(lat=30.5, offset_s=2)), "mesh")
    await settle(b)
    assert bot.methods() == ["sendPhoto"] and "30.50000" in bot.calls[0][1]["caption"]


async def test_alert_is_pinned_and_not_silent(cfg, bot):
    b = Bridge(cfg, bot)
    await b.on_cot(cot.parse(marker(uid="sos", typ="b-a-o-tbl", name="ALPHA-2-9-1-1")), "mesh")
    await settle(b)
    assert bot.methods() == ["sendPhoto", "pinChatMessage"]
    assert bot.calls[0][1]["disable_notification"] is False


async def test_positions_stay_private_until_share_on_from_inside_tak(cfg, bot):
    b = Bridge(cfg, bot)
    client = FakeClient()
    await b.on_cot(cot.parse(pli()), "endpoint", client)
    await settle(b)
    assert bot.calls == [] and "No TAK users" in b.roster_text()

    dm = cot.parse(cot.build_chat("ANDROID-1", "ALPHA-2", "Share ON", cfg.uid, cfg.callsign))
    assert await b.on_cot(dm, "endpoint", client) is False  # commands are never relayed
    await settle(b)
    reply = cot.parse(client.sent[-1])
    assert reply.chat.to_uid == "ANDROID-1" and "Sharing ON" in reply.chat.text
    assert bot.methods() == ["sendMessage", "sendLocation"]
    loc = bot.calls[1][1]
    assert loc["live_period"] == 0x7FFFFFFF and loc["heading"] == 45 and loc["horizontal_accuracy"] == 8.5
    assert "ALPHA-2" in b.roster_text()

    await b.on_cot(cot.parse(pli(lat=30.27, offset_s=10)), "endpoint", client)
    await settle(b)
    assert bot.methods()[-1] == "editMessageLiveLocation" and bot.calls[-1][1]["latitude"] == 30.27

    off = cot.parse(cot.build_chat("ANDROID-1", "ALPHA-2", "share off", cfg.uid, cfg.callsign))
    await b.on_cot(off, "endpoint", client)
    await settle(b)
    assert bot.methods()[-2:] == ["stopMessageLiveLocation", "editMessageText"]
    await b.on_cot(cot.parse(pli(lat=30.28, offset_s=20)), "endpoint", client)
    await settle(b)
    assert bot.methods()[-1] == "editMessageText" and "No TAK users" in b.roster_text()


async def test_all_chat_goes_to_ops_group_but_direct_chat_does_not(cfg, bot):
    b = Bridge(cfg, bot)
    await b.on_cot(cot.parse(cot.build_chat("U1", "ALPHA", "moving <now>")), "mesh")
    await b.on_cot(cot.parse(cot.build_chat("U1", "ALPHA", "private", "U2", "BRAVO")), "mesh")
    await settle(b)
    assert bot.methods() == ["sendMessage"]
    assert bot.calls[0][1]["chat_id"] == -200 and "moving &lt;now&gt;" in bot.calls[0][1]["text"]


async def test_telegram_buttons_and_commands(cfg, bot):
    b = Bridge(cfg, bot)
    await b.on_cot(cot.parse(marker()), "mesh")
    await settle(b)
    msg = {"message_id": 101, "chat": {"id": -100}}
    key = callback_key("m-1")
    await b.on_update({"callback_query": {"id": "1", "data": f"pin:{key}", "message": msg}})
    await b.on_update({"callback_query": {"id": "2", "data": f"tak:{key}", "message": msg}})
    await b.on_update({"message": {"chat": {"id": -200}, "from": {"id": 9}, "text": "/where tank 1"}})
    await b.on_update({"message": {"chat": {"id": -200}, "from": {"id": 9}, "text": "/where nobody"}})
    await settle(b, 0.4)
    m = bot.methods()
    assert m.count("sendLocation") == 2 and m.count("sendDocument") == 1 and m.count("answerCallbackQuery") == 2
    doc = next(c for c in bot.calls if c[0] == "sendDocument")
    assert doc[2]["document"][1][:2] == b"PK"
    assert any("No shared object" in (c[1].get("text") or "") for c in bot.calls)


async def test_only_allowlisted_users_reach_tak_and_bots_are_ignored(cfg, bot):
    b = Bridge(cfg, bot)
    sent = []
    b.send_tak = lambda xml, chat=False: sent.append(cot.parse(xml))
    base = {"chat": {"id": -200}, "text": "hello team"}
    await b.on_update({"message": {**base, "from": {"id": 9, "first_name": "Eve"}}})
    await b.on_update({"message": {**base, "from": {"id": 7, "first_name": "Ann", "is_bot": True}}})
    await b.on_update({"message": {**base, "chat": {"id": -999}, "from": {"id": 7, "first_name": "Ann"}}})
    assert sent == []
    await b.on_update({"message": {**base, "from": {"id": 7, "first_name": "Ann"}}})
    assert len(sent) == 1 and sent[0].chat.sender_callsign == "TG-Ann" and sent[0].chat.text == "hello team"


async def test_card_is_resent_when_telegram_lost_the_message(cfg, bot):
    b = Bridge(cfg, bot)
    await b.on_cot(cot.parse(marker()), "mesh")
    await settle(b)
    bot.fail["editMessageMedia"] = TelegramError(400, "Bad Request: message to edit not found")
    await b.on_cot(cot.parse(marker(lat=31.0, offset_s=5)), "mesh")
    await settle(b)
    assert bot.methods() == ["sendPhoto", "editMessageMedia", "sendPhoto"]


async def test_endpoint_relays_between_real_tcp_clients_and_answers_commands(cfg, bot):
    b = Bridge(cfg, bot)
    ep = Endpoint("127.0.0.1", 0, b.on_cot)
    b.endpoint = ep
    await ep.start()
    try:
        ra, wa = await asyncio.open_connection("127.0.0.1", ep.port)
        rb, wb = await asyncio.open_connection("127.0.0.1", ep.port)
        wa.write(pli("ANDROID-1", "ALPHA-2"))
        wb.write(pli("ANDROID-2", "BRAVO-6"))
        await asyncio.sleep(0.1)

        async def read_events(reader, n, timeout=1.0):
            split, out = cot.StreamSplitter(), []
            while len(out) < n:
                out += split.feed(await asyncio.wait_for(reader.read(65536), timeout))
            return [cot.parse(x) for x in out]

        got_b = await read_events(rb, 1)
        assert got_b[0].uid == "ANDROID-1"
        got_a = await read_events(ra, 1)
        assert got_a[0].uid == "ANDROID-2"

        wa.write(marker())
        assert (await read_events(rb, 1))[0].uid == "m-1"

        wa.write(cot.build_chat("ANDROID-1", "ALPHA-2", "status", cfg.uid, cfg.callsign))
        reply = (await read_events(ra, 1))[0]
        assert reply.chat.sender_uid == cfg.uid and "Sharing is OFF" in reply.chat.text
        await asyncio.sleep(0.1)
        assert rb._buffer == b""  # the command and its reply never reached the other client
        wa.close()
        wb.close()
    finally:
        await ep.stop()


async def test_setup_mode_answers_id_and_publishes_nothing(cfg, bot):
    cfg.display_chat = 0
    b = Bridge(cfg, bot)
    assert await b.on_cot(cot.parse(marker()), "mesh") is True
    await b.on_update({"channel_post": {"chat": {"id": -100123}, "text": "/id"}})
    await settle(b)
    assert bot.methods() == ["sendMessage"] and "-100123" in bot.calls[0][1]["text"]


# Captured from ATAK-CIV 5.8.0.10 connected to a streaming TCP input (2026-11-22).
ATAK_PING = (
    b'<?xml version="1.0"?>\n<event version="2.0" uid="ANDROID-f2ea670fc71b500d-ping" type="t-x-c-t" '
    b'access="Undefined" time="2026-11-23T04:12:48.490Z" start="2026-11-23T04:12:48.490Z" '
    b'stale="2026-11-23T04:12:58.490Z" how="m-g"><point lat="0.00000000" lon="0.00000000" '
    b'hae="0.00000000" ce="9999999" le="9999999"/><detail/></event>'
)


async def test_endpoint_answers_atak_ping_and_does_not_relay_it(cfg, bot):
    seen = []

    async def handler(ev, source, client):
        seen.append(ev.type)
        return True

    ep = Endpoint("127.0.0.1", 0, handler)
    await ep.start()
    try:
        ra, wa = await asyncio.open_connection("127.0.0.1", ep.port)
        rb, wb = await asyncio.open_connection("127.0.0.1", ep.port)
        wa.write(ATAK_PING)
        split, out = cot.StreamSplitter(), []
        while not out:
            out += split.feed(await asyncio.wait_for(ra.read(65536), 1.0))
        pong = cot.parse(out[0])
        assert pong.type == cot.PONG and pong.kind == "other"
        assert pong.stale > pong.time
        await asyncio.sleep(0.1)
        assert seen == [] and rb._buffer == b""  # pings never reach the bridge or other clients
        wa.close()
        wb.close()
    finally:
        await ep.stop()


def test_ping_is_recognised_and_default_port_avoids_atak():
    assert cot.parse(ATAK_PING).kind == "ping"
    from takgram.config import Config
    assert Config().endpoint_port == 8099  # ATAK itself listens on 8087
