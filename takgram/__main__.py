"""Command line: takgram run | datapackage | card."""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from pathlib import Path


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="takgram", description="TAK <-> Telegram bridge")
    sub = p.add_subparsers(dest="cmd", required=True)

    r = sub.add_parser("run", help="run the bridge")
    r.add_argument("-c", "--config", default="takgram.toml")
    r.add_argument("-v", "--verbose", action="store_true")

    d = sub.add_parser("datapackage", help="write a connection data package for TAK clients")
    d.add_argument("--host", required=True, help="address TAK clients use to reach the bridge")
    d.add_argument("--port", type=int, default=8087)
    d.add_argument("-o", "--out", default="takgram-connection.zip")

    c = sub.add_parser("card", help="render one CoT XML file to a card image and caption (no network to Telegram)")
    c.add_argument("cot_file")
    c.add_argument("-o", "--out", default="card.png")
    c.add_argument("--offline", action="store_true", help="do not fetch map tiles")

    a = p.parse_args(argv)

    if a.cmd == "datapackage":
        from .datapackage import connection_package

        Path(a.out).write_bytes(connection_package(a.host, a.port))
        print(f"wrote {a.out}: import it in ATAK, WinTAK or iTAK to add {a.host}:{a.port}:tcp")
        return 0

    if a.cmd == "card":
        from . import cot
        from .icons import IconResolver
        from .render import TileSource, caption, render_card

        ev = cot.parse(Path(a.cot_file).read_bytes())
        tiles = None if a.offline else TileSource("https://tile.openstreetmap.org/{z}/{x}/{y}.png", "tiles")
        Path(a.out).write_bytes(render_card(ev, IconResolver(), tiles))
        print(caption(ev, "file"))
        return 0

    from .bridge import Bridge
    from .config import load, validate
    from .telegram import Bot

    logging.basicConfig(level=logging.DEBUG if a.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    cfg = load(a.config)
    errs = validate(cfg)
    if errs:
        for e in errs:
            print("config error:", e, file=sys.stderr)
        return 2
    try:
        asyncio.run(Bridge(cfg, Bot(cfg.token)).run())
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
