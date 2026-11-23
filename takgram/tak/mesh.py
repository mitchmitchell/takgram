"""Serverless mesh attachment: standard TAK multicast groups on the local network.

Situational awareness goes to 239.2.3.1:6969 and chat to 224.10.10.1:17012.
Direct messages between mesh peers are sent point to point over TCP to the
endpoint each peer advertises, so the bridge also listens on a TCP port and
advertises it in its own contact event.

Only XML CoT is understood. Peers that negotiate TAK protobuf are logged and
skipped; the bridge never advertises protobuf support, which keeps a standard
ATAK mesh on XML.
"""

from __future__ import annotations

import asyncio
import logging
import socket
import struct
from typing import Awaitable, Callable

from ..cot import CotError, CotEvent, ProtobufNotSupported, StreamSplitter, parse

log = logging.getLogger("takgram.mesh")
SA_GROUP, SA_PORT = "239.2.3.1", 6969
CHAT_GROUP, CHAT_PORT = "224.10.10.1", 17012
Handler = Callable[[CotEvent, str, object], Awaitable[bool]]


def local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def _mcast_socket(group: str, port: int, iface_ip: str) -> socket.socket:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "SO_REUSEPORT"):
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    s.bind(("", port))
    mreq = struct.pack("4s4s", socket.inet_aton(group), socket.inet_aton(iface_ip))
    s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(iface_ip))
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 0)
    s.setblocking(False)
    return s


class _Rx(asyncio.DatagramProtocol):
    def __init__(self, mesh: "Mesh") -> None:
        self.mesh = mesh

    def datagram_received(self, data: bytes, addr) -> None:
        asyncio.ensure_future(self.mesh._incoming(data))


class Mesh:
    name = "mesh"

    def __init__(self, handler: Handler, iface_ip: str = "", direct_port: int = 4242) -> None:
        self.handler = handler
        self.ip = iface_ip or local_ip()
        self.direct_port = direct_port
        self.sa: asyncio.DatagramTransport | None = None
        self.chat: asyncio.DatagramTransport | None = None
        self.server: asyncio.AbstractServer | None = None
        self.warned_protobuf = False

    @property
    def advertised_endpoint(self) -> str:
        return f"{self.ip}:{self.direct_port}:tcp"

    async def start(self) -> None:
        loop = asyncio.get_running_loop()
        self.sa, _ = await loop.create_datagram_endpoint(
            lambda: _Rx(self), sock=_mcast_socket(SA_GROUP, SA_PORT, self.ip))
        self.chat, _ = await loop.create_datagram_endpoint(
            lambda: _Rx(self), sock=_mcast_socket(CHAT_GROUP, CHAT_PORT, self.ip))
        self.server = await asyncio.start_server(self._direct, self.ip, self.direct_port)
        log.info("mesh joined on %s; direct messages on tcp %s", self.ip, self.direct_port)

    async def stop(self) -> None:
        for t in (self.sa, self.chat):
            if t:
                t.close()
        if self.server:
            self.server.close()

    async def _incoming(self, data: bytes) -> None:
        try:
            ev = parse(data)
        except ProtobufNotSupported:
            if not self.warned_protobuf:
                self.warned_protobuf = True
                log.warning("a mesh peer is sending TAK protobuf, which this version cannot read")
            return
        except CotError as e:
            log.debug("dropped mesh datagram: %s", e)
            return
        await self.handler(ev, self.name, None)

    async def _direct(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        split = StreamSplitter()
        try:
            while data := await asyncio.wait_for(reader.read(65536), 10):
                for raw in split.feed(data):
                    await self._incoming(raw)
        except (asyncio.TimeoutError, ConnectionError):
            pass
        finally:
            writer.close()

    def send(self, xml: bytes, chat: bool = False) -> None:
        t, dest = (self.chat, (CHAT_GROUP, CHAT_PORT)) if chat else (self.sa, (SA_GROUP, SA_PORT))
        if t:
            t.sendto(xml, dest)

    async def send_direct(self, endpoint: str, xml: bytes) -> bool:
        """Deliver one event to a peer's advertised 'ip:port:tcp' endpoint."""
        parts = endpoint.split(":")
        if len(parts) < 3 or parts[2] != "tcp":
            return False
        try:
            _, w = await asyncio.wait_for(asyncio.open_connection(parts[0], int(parts[1])), 5)
            w.write(xml)
            await w.drain()
            w.close()
            return True
        except (OSError, ValueError, asyncio.TimeoutError) as e:
            log.warning("direct send to %s failed: %s", endpoint, e)
            return False
