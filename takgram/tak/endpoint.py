"""Built-in CoT endpoint: a small TCP listener that TAK clients add as a server.

It speaks plain streaming XML CoT, relays events between connected clients
and hands every event to the bridge. Events carrying <marti><dest> are routed
only to the named callsigns. It is not a TAK Server: no TLS, no missions, no
enrollment. It answers the clients' t-x-c-t pings.

The default port is 8099, not 8087: ATAK itself listens on TCP and UDP 8087 by default (its
streaming input), so a server on 8087 collides with ATAK on the same device and is easily
confused with a TAK Server or FreeTAKServer, whose plain-text CoT port is also 8087.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Awaitable, Callable

from ..cot import PING, CotError, CotEvent, StreamSplitter, build_pong, parse

log = logging.getLogger("takgram.endpoint")
Handler = Callable[[CotEvent, str, "Client | None"], Awaitable[bool]]


class Client:
    def __init__(self, writer: asyncio.StreamWriter) -> None:
        self.writer = writer
        self.peer = writer.get_extra_info("peername")
        self.uid = ""
        self.callsign = ""

    def send(self, xml: bytes) -> None:
        if not self.writer.is_closing():
            self.writer.write(xml)


class Endpoint:
    name = "endpoint"

    def __init__(self, host: str, port: int, handler: Handler) -> None:
        self.host, self.port, self.handler = host, port, handler
        self.clients: set[Client] = set()
        self.server: asyncio.AbstractServer | None = None

    async def start(self) -> None:
        self.server = await asyncio.start_server(self._serve, self.host, self.port)
        self.port = self.server.sockets[0].getsockname()[1]
        log.info("built-in endpoint listening on %s:%s", self.host, self.port)

    async def stop(self) -> None:
        if self.server:
            self.server.close()
        for c in list(self.clients):
            c.writer.close()

    def broadcast(self, xml: bytes, exclude: Client | None = None, callsigns: list[str] | None = None) -> None:
        for c in self.clients:
            if c is exclude:
                continue
            if callsigns and c.callsign not in callsigns:
                continue
            c.send(xml)

    def client_for(self, uid: str) -> Client | None:
        return next((c for c in self.clients if c.uid == uid), None)

    async def _serve(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        client = Client(writer)
        self.clients.add(client)
        log.info("client connected: %s", client.peer)
        split = StreamSplitter()
        try:
            while data := await reader.read(65536):
                for raw in split.feed(data):
                    try:
                        ev = parse(raw)
                    except CotError as e:
                        log.debug("dropped event from %s: %s", client.peer, e)
                        continue
                    if ev.type == PING:
                        client.send(build_pong())  # keepalive only: never relayed or bridged
                        continue
                    if ev.kind == "unit" and ev.callsign:
                        client.uid, client.callsign = ev.uid, ev.callsign
                    relay = await self.handler(ev, self.name, client)
                    if relay:
                        self.broadcast(raw, exclude=client, callsigns=ev.dest_callsigns or None)
        except (ConnectionError, asyncio.IncompleteReadError):
            pass
        finally:
            self.clients.discard(client)
            writer.close()
            log.info("client disconnected: %s", client.peer)
