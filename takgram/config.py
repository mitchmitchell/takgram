"""Configuration loaded from one TOML file."""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Config:
    token: str = ""
    display_chat: int = 0
    ops_chat: int = 0
    ops_chat_topic: int | None = None
    allow_users: list[int] = field(default_factory=list)

    mesh_enabled: bool = True
    mesh_iface_ip: str = ""
    mesh_direct_port: int = 4242

    endpoint_enabled: bool = True
    endpoint_host: str = "0.0.0.0"
    endpoint_port: int = 8087
    endpoint_public_host: str = ""

    callsign: str = "TAKgram"
    uid: str = "TAKGRAM-BRIDGE"
    db_path: str = "takgram.db"
    icons_dir: str = ""
    tile_url: str = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    tile_cache: str = "tiles"
    zoom: int = 14
    pli_interval_s: float = 30.0
    chat_interval_s: float = 3.1
    export_types: list[str] = field(default_factory=lambda: ["a-", "b-m-p", "u-d-", "b-m-r", "b-t-f", "b-a-", "b-r-f-h-c"])


def load(path: str) -> Config:
    raw = tomllib.loads(Path(path).read_text())
    tg, tak, ex = raw.get("telegram", {}), raw.get("tak", {}), raw.get("export", {})
    mesh, ep, br = tak.get("mesh", {}), tak.get("endpoint", {}), raw.get("bridge", {})
    token = os.environ.get("TAKGRAM_TOKEN", "")
    if not token and tg.get("token_file"):
        token = Path(tg["token_file"]).read_text().strip()
    c = Config(token=token)
    c.display_chat = int(tg.get("display_chat", 0))
    c.ops_chat = int(tg.get("ops_chat", 0))
    c.ops_chat_topic = tg.get("ops_chat_topic")
    c.allow_users = [int(x) for x in tg.get("allow_users", [])]
    c.mesh_enabled = bool(mesh.get("enabled", False))
    c.mesh_iface_ip = mesh.get("iface_ip", "")
    c.mesh_direct_port = int(mesh.get("direct_port", 4242))
    c.endpoint_enabled = bool(ep.get("enabled", "listen_port" in ep))
    c.endpoint_host = ep.get("listen_host", "0.0.0.0")
    c.endpoint_port = int(ep.get("listen_port", 8087))
    c.endpoint_public_host = ep.get("public_host", "")
    for k in ("callsign", "uid", "db_path", "icons_dir", "tile_url", "tile_cache", "zoom"):
        if k in br:
            setattr(c, k, br[k])
    c.pli_interval_s = float(ex.get("pli_interval_s", c.pli_interval_s))
    c.chat_interval_s = float(ex.get("chat_interval_s", c.chat_interval_s))
    if "types" in ex:
        c.export_types = [t.rstrip("*") for t in ex["types"]]
    return c


def validate(c: Config) -> list[str]:
    errs = []
    if not c.token:
        errs.append("no bot token: set TAKGRAM_TOKEN or telegram.token_file")
    if not (c.mesh_enabled or c.endpoint_enabled):
        errs.append("enable at least one of tak.mesh or tak.endpoint")
    return errs
