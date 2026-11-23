"""Cursor on Target parsing and building.

Only XML CoT is handled. TAK protobuf payloads (which start with 0xBF) are
detected and rejected with ProtobufNotSupported so callers can log them.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape, quoteattr

from defusedxml import ElementTree as SafeET

MAX_EVENT_BYTES = 256 * 1024
ALL_CHAT = "All Chat Rooms"


class CotError(ValueError):
    pass


class ProtobufNotSupported(CotError):
    pass


def now() -> datetime:
    return datetime.now(timezone.utc)


def iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def parse_time(s: str | None) -> datetime | None:
    if not s:
        return None
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone(timezone.utc)
    except ValueError:
        return None


def _f(s: str | None, default: float | None = None) -> float | None:
    try:
        return float(s) if s is not None else default
    except ValueError:
        return default


@dataclass
class Chat:
    sender_uid: str
    sender_callsign: str
    room: str  # chat room name, or the recipient callsign for a direct message
    to_uid: str  # ALL_CHAT or the recipient uid
    text: str

    @property
    def is_direct(self) -> bool:
        return self.to_uid != ALL_CHAT


@dataclass
class CotEvent:
    uid: str
    type: str
    lat: float = 0.0
    lon: float = 0.0
    hae: float | None = None
    ce: float | None = None
    how: str = "m-g"
    time: datetime = field(default_factory=now)
    stale: datetime = field(default_factory=lambda: now() + timedelta(minutes=5))
    callsign: str = ""
    endpoint: str = ""
    remarks: str = ""
    course: float | None = None
    speed: float | None = None
    color_argb: int | None = None
    iconsetpath: str = ""
    team: str = ""
    role: str = ""
    is_tak_client: bool = False
    chat: Chat | None = None
    delete_uid: str = ""
    dest_callsigns: list[str] = field(default_factory=list)
    points: list[tuple[float, float]] = field(default_factory=list)
    xml: bytes = b""

    @property
    def name(self) -> str:
        return self.callsign or self.uid

    @property
    def kind(self) -> str:
        t = self.type
        if t == "b-t-f":
            return "chat"
        if t.startswith("t-x-d-d"):
            return "delete"
        if t.startswith("b-a-"):
            return "alert"
        if t == "b-r-f-h-c":
            return "casevac"
        if t.startswith("u-d-"):
            return "shape"
        if t == "b-m-r":
            return "route"
        if t.startswith("a-") and self.is_tak_client:
            return "unit"
        if t.startswith("a-") or t.startswith("b-m-p"):
            return "marker"
        return "other"


def parse(data: bytes) -> CotEvent:
    """Parse one CoT XML event. Raises CotError on anything malformed."""
    if not data:
        raise CotError("empty")
    if data[0] == 0xBF:
        raise ProtobufNotSupported("TAK protobuf payload")
    if len(data) > MAX_EVENT_BYTES:
        raise CotError("event too large")
    try:
        root = SafeET.fromstring(data)
    except Exception as e:  # defusedxml raises several unrelated types
        raise CotError(f"bad xml: {e}") from e
    if root.tag != "event":
        raise CotError("not an event")
    uid, typ = root.get("uid"), root.get("type")
    if not uid or not typ:
        raise CotError("missing uid or type")

    ev = CotEvent(uid=uid, type=typ, how=root.get("how", ""), xml=data)
    ev.time = parse_time(root.get("time")) or now()
    ev.stale = parse_time(root.get("stale")) or ev.time + timedelta(minutes=5)

    pt = root.find("point")
    if pt is not None:
        ev.lat = _f(pt.get("lat"), 0.0) or 0.0
        ev.lon = _f(pt.get("lon"), 0.0) or 0.0
        hae = _f(pt.get("hae"))
        ev.hae = hae if hae is not None and hae < 9_000_000 else None
        ce = _f(pt.get("ce"))
        ev.ce = ce if ce is not None and ce < 9_000_000 else None
    if not (-90 <= ev.lat <= 90 and -180 <= ev.lon <= 180):
        raise CotError("coordinates out of range")

    d = root.find("detail")
    if d is None:
        return ev

    c = d.find("contact")
    if c is not None:
        ev.callsign = c.get("callsign", "")
        ev.endpoint = c.get("endpoint", "")
    g = d.find("__group")
    if g is not None:
        ev.team, ev.role = g.get("name", ""), g.get("role", "")
    ev.is_tak_client = d.find("takv") is not None or g is not None
    r = d.find("remarks")
    if r is not None and r.text:
        ev.remarks = r.text.strip()
    tr = d.find("track")
    if tr is not None:
        ev.course, ev.speed = _f(tr.get("course")), _f(tr.get("speed"))
    col = d.find("color")
    if col is not None:
        try:
            ev.color_argb = int(col.get("argb", col.get("value", ""))) & 0xFFFFFFFF
        except ValueError:
            pass
    ui = d.find("usericon")
    if ui is not None:
        ev.iconsetpath = ui.get("iconsetpath", "")
    m = d.find("marti")
    if m is not None:
        ev.dest_callsigns = [x.get("callsign", "") for x in m.findall("dest") if x.get("callsign")]

    for ln in d.findall("link"):
        p = ln.get("point")
        if p:
            parts = p.split(",")
            a, b = _f(parts[0]), _f(parts[1]) if len(parts) > 1 else None
            if a is not None and b is not None:
                ev.points.append((a, b))
        if ev.type.startswith("t-x-d-d") and ln.get("uid"):
            ev.delete_uid = ln.get("uid", "")

    ch = d.find("__chat")
    if ev.type == "b-t-f" and ch is not None:
        grp = ch.find("chatgrp")
        sender_uid = grp.get("uid0", "") if grp is not None else ""
        to_uid = ch.get("id", "") or (grp.get("uid1", "") if grp is not None else "")
        if not sender_uid:
            ln = d.find("link")
            sender_uid = ln.get("uid", "") if ln is not None else ""
        ev.chat = Chat(
            sender_uid=sender_uid,
            sender_callsign=ch.get("senderCallsign", "") or sender_uid,
            room=ch.get("chatroom", ""),
            to_uid=to_uid or ALL_CHAT,
            text=ev.remarks,
        )
    return ev


class StreamSplitter:
    """Splits a TCP byte stream into CoT events on the closing event tag."""

    END = b"</event>"

    def __init__(self) -> None:
        self.buf = bytearray()

    def feed(self, data: bytes) -> list[bytes]:
        self.buf += data
        out = []
        while True:
            i = self.buf.find(self.END)
            if i < 0:
                break
            chunk = bytes(self.buf[: i + len(self.END)])
            del self.buf[: i + len(self.END)]
            start = chunk.find(b"<event")
            if start >= 0:
                out.append(chunk[start:])
        if len(self.buf) > MAX_EVENT_BYTES:
            self.buf.clear()
        return out


def _event(uid: str, typ: str, how: str, lat: float, lon: float, hae: float | None,
           stale_s: float, detail: str) -> bytes:
    t = now()
    h = "9999999.0" if hae is None else f"{hae:.1f}"
    return (
        f'<event version="2.0" uid={quoteattr(uid)} type={quoteattr(typ)} how={quoteattr(how)} '
        f'time="{iso(t)}" start="{iso(t)}" stale="{iso(t + timedelta(seconds=stale_s))}">'
        f'<point lat="{lat:.7f}" lon="{lon:.7f}" hae="{h}" ce="9999999.0" le="9999999.0"/>'
        f"<detail>{detail}</detail></event>"
    ).encode()


def build_contact(uid: str, callsign: str, endpoint: str, lat: float = 0.0, lon: float = 0.0,
                  typ: str = "a-f-G-U-U-S", team: str = "Cyan", stale_s: float = 120) -> bytes:
    """Presence event that makes the bridge show up in TAK contact lists."""
    detail = (
        f"<contact callsign={quoteattr(callsign)} endpoint={quoteattr(endpoint)}/>"
        f'<__group name={quoteattr(team)} role="Team Member"/>'
        '<takv device="takgram" platform="TAKgram" os="python" version="0.1.0"/>'
        f"<uid Droid={quoteattr(callsign)}/>"
    )
    return _event(uid, typ, "h-e", lat, lon, None, stale_s, detail)


def build_marker(uid: str, typ: str, callsign: str, lat: float, lon: float,
                 remarks: str = "", stale_s: float = 3600, how: str = "h-g-i-g-o") -> bytes:
    detail = f"<contact callsign={quoteattr(callsign)}/><remarks>{escape(remarks)}</remarks>"
    return _event(uid, typ, how, lat, lon, None, stale_s, detail)


def build_chat(sender_uid: str, sender_callsign: str, text: str,
               to_uid: str = ALL_CHAT, to_callsign: str = ALL_CHAT) -> bytes:
    """GeoChat message. to_uid=ALL_CHAT broadcasts; otherwise a direct message."""
    msg_id = str(uuid.uuid4())
    room = ALL_CHAT if to_uid == ALL_CHAT else to_callsign
    detail = (
        f'<__chat parent="RootContactGroup" groupOwner="false" messageId="{msg_id}" '
        f"chatroom={quoteattr(room)} id={quoteattr(to_uid)} senderCallsign={quoteattr(sender_callsign)}>"
        f"<chatgrp uid0={quoteattr(sender_uid)} uid1={quoteattr(to_uid)} id={quoteattr(to_uid)}/></__chat>"
        f'<link uid={quoteattr(sender_uid)} type="a-f-G-U-U-S" relation="p-p"/>'
        f"<remarks source={quoteattr('BAO.F.ATAK.' + sender_uid)} to={quoteattr(to_uid)} "
        f'time="{iso(now())}">{escape(text)}</remarks>'
    )
    if to_uid != ALL_CHAT:
        detail += f"<marti><dest callsign={quoteattr(to_callsign)}/></marti>"
    uid = f"GeoChat.{sender_uid}.{to_uid}.{msg_id}"
    return _event(uid, "b-t-f", "h-g-i-g-o", 0.0, 0.0, None, 86400, detail)


def canonical(xml: bytes) -> bytes:
    """Re-serialize an event so a stream never carries a stray XML declaration."""
    root = SafeET.fromstring(xml)
    return ET.tostring(root, encoding="utf-8")
