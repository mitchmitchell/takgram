"""SQLite object store: one row per CoT uid, plus sharing flags."""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass

from .cot import CotEvent, iso, parse_time

SCHEMA = """
CREATE TABLE IF NOT EXISTS objects (
  uid TEXT PRIMARY KEY, type TEXT, kind TEXT, callsign TEXT, lat REAL, lon REAL,
  time TEXT, stale TEXT, source TEXT, endpoint TEXT, xml BLOB,
  card_chat INTEGER, card_msg INTEGER, live_chat INTEGER, live_msg INTEGER, removed INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS shares (uid TEXT PRIMARY KEY, enabled INTEGER);
CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT);
"""


@dataclass
class Row:
    uid: str
    type: str
    kind: str
    callsign: str
    lat: float
    lon: float
    time: str
    stale: str
    source: str
    endpoint: str
    xml: bytes
    card_chat: int | None
    card_msg: int | None
    live_chat: int | None
    live_msg: int | None
    removed: int


class Store:
    def __init__(self, path: str = ":memory:") -> None:
        self.db = sqlite3.connect(path)
        self.db.executescript(SCHEMA)

    def get(self, uid: str) -> Row | None:
        r = self.db.execute("SELECT * FROM objects WHERE uid=?", (uid,)).fetchone()
        return Row(*r) if r else None

    def upsert(self, ev: CotEvent, source: str) -> tuple[Row | None, bool]:
        """Store the event. Returns (previous row, accepted). Older events are rejected."""
        prev = self.get(ev.uid)
        if prev and not prev.removed:
            pt = parse_time(prev.time)
            if pt and ev.time < pt:
                return prev, False
        self.db.execute(
            "INSERT INTO objects (uid,type,kind,callsign,lat,lon,time,stale,source,endpoint,xml,removed) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,0) ON CONFLICT(uid) DO UPDATE SET type=excluded.type, "
            "kind=excluded.kind, callsign=excluded.callsign, lat=excluded.lat, lon=excluded.lon, "
            "time=excluded.time, stale=excluded.stale, source=excluded.source, "
            "endpoint=excluded.endpoint, xml=excluded.xml, removed=0",
            (ev.uid, ev.type, ev.kind, ev.callsign, ev.lat, ev.lon, iso(ev.time), iso(ev.stale),
             source, ev.endpoint, ev.xml),
        )
        self.db.commit()
        return prev, True

    def set_card(self, uid: str, chat: int, msg: int) -> None:
        self.db.execute("UPDATE objects SET card_chat=?, card_msg=? WHERE uid=?", (chat, msg, uid))
        self.db.commit()

    def set_live(self, uid: str, chat: int | None, msg: int | None) -> None:
        self.db.execute("UPDATE objects SET live_chat=?, live_msg=? WHERE uid=?", (chat, msg, uid))
        self.db.commit()

    def mark_removed(self, uid: str, state: int = 1) -> None:
        """state 1 = deleted, 2 = card already marked stale."""
        self.db.execute("UPDATE objects SET removed=? WHERE uid=?", (state, uid))
        self.db.commit()

    def stale_cards(self, now_iso: str) -> list[Row]:
        cur = self.db.execute(
            "SELECT * FROM objects WHERE removed=0 AND card_msg IS NOT NULL AND stale < ?", (now_iso,))
        return [Row(*r) for r in cur.fetchall()]

    def units(self) -> list[Row]:
        cur = self.db.execute("SELECT * FROM objects WHERE kind='unit' AND removed=0 ORDER BY callsign")
        return [Row(*r) for r in cur.fetchall()]

    def find_callsign(self, name: str) -> Row | None:
        r = self.db.execute(
            "SELECT * FROM objects WHERE removed=0 AND lower(callsign)=lower(?) ORDER BY time DESC", (name,)
        ).fetchone()
        return Row(*r) if r else None

    def sharing(self, uid: str) -> bool:
        r = self.db.execute("SELECT enabled FROM shares WHERE uid=?", (uid,)).fetchone()
        return bool(r and r[0])

    def set_sharing(self, uid: str, enabled: bool) -> None:
        self.db.execute("INSERT INTO shares VALUES (?,?) ON CONFLICT(uid) DO UPDATE SET enabled=excluded.enabled",
                        (uid, int(enabled)))
        self.db.commit()

    def kv_get(self, k: str) -> str | None:
        r = self.db.execute("SELECT v FROM kv WHERE k=?", (k,)).fetchone()
        return r[0] if r else None

    def kv_set(self, k: str, v: str) -> None:
        self.db.execute("INSERT INTO kv VALUES (?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, v))
        self.db.commit()
