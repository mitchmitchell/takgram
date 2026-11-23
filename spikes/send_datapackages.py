"""Phase 0 spike: do TAK clients import data packages received through Telegram?

Posts two files to a chat: a connection package (adds the bridge's built-in
endpoint as a server) and an object package (one marker). On each device,
tap the file in Telegram and open it with ATAK, WinTAK or iTAK, then note
whether the server entry and the marker appear.

    python spikes/send_datapackages.py --token TOKEN --chat 123456789 --host 192.168.1.10
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from _api import call  # noqa: E402

from takgram import cot  # noqa: E402
from takgram.datapackage import connection_package, object_package  # noqa: E402

p = argparse.ArgumentParser()
p.add_argument("--token", required=True)
p.add_argument("--chat", required=True, type=int)
p.add_argument("--host", required=True, help="address of the machine that will run the bridge")
p.add_argument("--port", type=int, default=8087)
p.add_argument("--lat", type=float, default=30.2672)
p.add_argument("--lon", type=float, default=-97.7431)
a = p.parse_args()

marker = cot.parse(cot.build_marker("takgram-spike-marker", "a-u-G", "TAKgram test marker", a.lat, a.lon,
                                    "Imported from Telegram"))
for name, data, note in (
    ("takgram-connection.zip", connection_package(a.host, a.port),
     f"Connection package: should add server {a.host}:{a.port}:tcp"),
    ("takgram-marker.zip", object_package(marker), "Object package: should add one yellow unknown marker"),
):
    ok, r = call(a.token, "sendDocument", files={"document": (name, data)}, chat_id=a.chat, caption=note)
    print(("sent " if ok else "FAILED ") + name + ("" if ok else f": {r}"))
