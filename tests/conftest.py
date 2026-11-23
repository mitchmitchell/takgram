from datetime import timedelta

import pytest

from takgram import cot
from takgram.config import Config


def stamp(offset_s: float = 0, stale_s: float = 300) -> str:
    t = cot.now() + timedelta(seconds=offset_s)
    return f'time="{cot.iso(t)}" start="{cot.iso(t)}" stale="{cot.iso(t + timedelta(seconds=stale_s))}"'


def pli(uid="ANDROID-1", callsign="ALPHA-2", lat=30.26715, lon=-97.74306, offset_s=0.0) -> bytes:
    return (
        f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        f'<event version="2.0" uid="{uid}" type="a-f-G-U-C" how="m-g" {stamp(offset_s)}>'
        f'<point lat="{lat}" lon="{lon}" hae="149.2" ce="8.5" le="9999999.0"/>'
        f'<detail><takv os="34" version="5.6.0.12" device="PIXEL" platform="ATAK-CIV"/>'
        f'<contact endpoint="192.168.1.20:4242:tcp" callsign="{callsign}"/><uid Droid="{callsign}"/>'
        f'<__group role="Team Lead" name="Cyan"/><status battery="88"/>'
        f'<track course="45.0" speed="1.4"/></detail></event>'
    ).encode()


def marker(uid="m-1", typ="a-h-G-U-C-A", name="Tank 1", lat=30.3, lon=-97.7, remarks="Seen <moving> north",
           offset_s=0.0) -> bytes:
    return (
        f'<event version="2.0" uid="{uid}" type="{typ}" how="h-g-i-g-o" {stamp(offset_s, 3600)}>'
        f'<point lat="{lat}" lon="{lon}" hae="9999999.0" ce="9999999.0" le="9999999.0"/>'
        f'<detail><contact callsign="{name}"/><remarks>{remarks.replace("<", "&lt;").replace(">", "&gt;")}</remarks>'
        f'<usericon iconsetpath="COT_MAPPING_2525B/a-h/a-h-G"/><link uid="ANDROID-1" type="a-f-G-U-C" relation="p-p"/>'
        f'</detail></event>'
    ).encode()


def delete(target="m-1") -> bytes:
    return (
        f'<event version="2.0" uid="del-{target}" type="t-x-d-d" how="m-g" {stamp(1)}>'
        f'<point lat="0" lon="0" hae="0" ce="9999999" le="9999999"/>'
        f'<detail><link uid="{target}" relation="none" type="none"/><_flow-tags_/></detail></event>'
    ).encode()


@pytest.fixture
def cfg(tmp_path):
    return Config(token="x", display_chat=-100, ops_chat=-200, allow_users=[7], db_path=":memory:",
                  tile_url="", tile_cache="", chat_interval_s=0.0, pli_interval_s=0.0,
                  mesh_enabled=False, endpoint_enabled=False)


class FakeBot:
    def __init__(self):
        self.calls = []
        self.next_id = 100
        self.fail = {}

    async def start(self):
        pass

    async def close(self):
        pass

    async def call(self, method, files=None, **params):
        self.calls.append((method, params, files))
        if method in self.fail:
            raise self.fail.pop(method)
        self.next_id += 1
        return {"message_id": self.next_id, "username": "fake"}

    def methods(self):
        return [c[0] for c in self.calls]


@pytest.fixture
def bot():
    return FakeBot()
