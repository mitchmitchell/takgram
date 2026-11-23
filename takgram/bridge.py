"""The bridge: wires TAK attachments, the object store and Telegram together."""

from __future__ import annotations

import asyncio
import html
import logging
import time
from collections import OrderedDict

from . import cot
from .config import Config
from .cot import CotEvent
from .datapackage import object_package
from .icons import IconResolver
from .ratequeue import OutboundQueue
from .render import TileSource, callback_key, caption, keyboard, mgrs_of, render_card
from .store import Row, Store
from .telegram import TelegramError

log = logging.getLogger("takgram.bridge")
LIVE_FOREVER = 0x7FFFFFFF
CARD_KINDS = ("marker", "alert", "casevac", "shape", "route")
HELP_TAK = "Commands: share on | share off | status | help"
HELP_TG = ("/roster lists units sharing position\n/where NAME sends a pin for a callsign\n"
           "/id shows this chat's id\nButtons on a card: Pin sends a native location, "
           "Open in TAK sends a data package.")


class Bridge:
    def __init__(self, cfg: Config, bot, store: Store | None = None) -> None:
        self.cfg, self.bot = cfg, bot
        self.store = store or Store(cfg.db_path)
        self.queue = OutboundQueue(cfg.chat_interval_s)
        self.icons = IconResolver(cfg.icons_dir or None)
        self.tiles = TileSource(cfg.tile_url, cfg.tile_cache or None) if cfg.tile_url else None
        self.mesh = None
        self.endpoint = None
        self.seen: OrderedDict = OrderedDict()
        self.last_live: dict[str, float] = {}
        self.roster_dirty = False

    # ---------------------------------------------------------------- TAK in

    async def on_cot(self, ev: CotEvent, source: str, client=None) -> bool:
        """Handle one event from a TAK attachment. Returns True if it may be relayed."""
        if ev.uid == self.cfg.uid:
            return False
        key = (ev.uid, ev.time)
        if key in self.seen:
            return False
        self.seen[key] = True
        while len(self.seen) > 4096:
            self.seen.popitem(last=False)

        if ev.chat and ev.chat.to_uid == self.cfg.uid:
            await self._tak_command(ev, source, client)
            return False

        # Relay between attachments. The endpoint relays among its own clients itself.
        try:
            if source == "mesh" and self.endpoint:
                self.endpoint.broadcast(cot.canonical(ev.xml), callsigns=ev.dest_callsigns or None)
            elif source == "endpoint" and self.mesh and not ev.dest_callsigns:
                self.mesh.send(ev.xml, chat=ev.kind == "chat")
        except Exception as e:
            log.warning("relay failed: %s", e)

        if not self.cfg.display_chat:
            return True  # setup mode: relay between TAK clients, publish nothing
        if any(ev.type.startswith(p) for p in self.cfg.export_types) or ev.kind == "delete":
            await self._dispatch(ev, source)
        return True

    async def _dispatch(self, ev: CotEvent, source: str) -> None:
        kind = ev.kind
        if kind == "chat":
            if ev.chat and not ev.chat.is_direct:
                chat = self.cfg.ops_chat or self.cfg.display_chat
                text = f"<b>{html.escape(ev.chat.sender_callsign)}</b>: {html.escape(ev.chat.text)}"
                self.queue.submit(chat, None, lambda: self.bot.call(
                    "sendMessage", chat_id=chat, text=text, parse_mode="HTML",
                    message_thread_id=self.cfg.ops_chat_topic if chat == self.cfg.ops_chat else None))
        elif kind == "unit":
            _, ok = self.store.upsert(ev, source)
            if ok and self.store.sharing(ev.uid):
                self._publish_live(ev.uid)
        elif kind in CARD_KINDS:
            _, ok = self.store.upsert(ev, source)
            if ok:
                uid = ev.uid
                self.queue.submit(self.cfg.display_chat, ("card", uid), lambda: self._card(uid),
                                  priority=0 if kind in ("alert", "casevac") else 1)
        elif kind == "delete" and ev.delete_uid:
            await self._remove(ev.delete_uid)

    # ------------------------------------------------------------- cards out

    async def _card(self, uid: str) -> None:
        row = self.store.get(uid)
        if not row or row.removed == 1:
            return
        ev = cot.parse(row.xml)
        loop = asyncio.get_running_loop()
        png = await loop.run_in_executor(None, render_card, ev, self.icons, self.tiles, self.cfg.zoom)
        cap, kb = caption(ev, row.source), keyboard(ev)
        self.store.kv_set("cb:" + callback_key(uid), uid)
        photo = {"photo": ("card.png", png, "image/png")}
        if row.card_msg:
            media = {"type": "photo", "media": "attach://photo", "caption": cap, "parse_mode": "HTML"}
            try:
                await self.bot.call("editMessageMedia", files=photo, chat_id=row.card_chat,
                                    message_id=row.card_msg, media=media, reply_markup=kb)
                return
            except TelegramError as e:
                if "not modified" in e.description:
                    return
                if "not found" not in e.description and "can't be edited" not in e.description:
                    raise
        alert = ev.kind in ("alert", "casevac") and ev.type != "b-a-o-can"
        msg = await self.bot.call("sendPhoto", files=photo, chat_id=self.cfg.display_chat, caption=cap,
                                  parse_mode="HTML", reply_markup=kb, disable_notification=not alert)
        self.store.set_card(uid, self.cfg.display_chat, msg["message_id"])
        if alert:
            try:
                await self.bot.call("pinChatMessage", chat_id=self.cfg.display_chat, message_id=msg["message_id"])
            except TelegramError as e:
                log.info("could not pin alert: %s", e)

    async def _mark_card(self, row: Row, status: str) -> None:
        ev = cot.parse(row.xml)
        try:
            await self.bot.call("editMessageCaption", chat_id=row.card_chat, message_id=row.card_msg,
                                caption=caption(ev, row.source, status), parse_mode="HTML")
        except TelegramError as e:
            log.info("could not mark card %s: %s", row.uid, e)

    async def _remove(self, uid: str) -> None:
        row = self.store.get(uid)
        if not row or row.removed == 1:
            return
        self.store.mark_removed(uid)
        if row.card_msg:
            self.queue.submit(row.card_chat, ("card", uid), lambda: self._mark_card(row, "❌ REMOVED"))
        if row.live_msg:
            self.queue.submit(row.live_chat, ("live", uid), lambda: self._stop_live(uid))

    # -------------------------------------------------------- live positions

    def _publish_live(self, uid: str, force: bool = False) -> None:
        now = time.monotonic()
        if not force and now - self.last_live.get(uid, -1e9) < self.cfg.pli_interval_s:
            return
        self.last_live[uid] = now
        self.roster_dirty = True
        self.queue.submit(self.cfg.display_chat, ("live", uid), lambda: self._live(uid))

    async def _live(self, uid: str) -> None:
        row = self.store.get(uid)
        if not row or row.removed == 1 or not self.store.sharing(uid):
            return
        ev = cot.parse(row.xml)
        pos: dict = {"latitude": ev.lat, "longitude": ev.lon}
        if ev.ce is not None and 0 < ev.ce <= 1500:
            pos["horizontal_accuracy"] = ev.ce
        if ev.course is not None and ev.speed and ev.speed > 0.3:
            pos["heading"] = int(round(ev.course)) % 360 or 360
        if row.live_msg:
            try:
                await self.bot.call("editMessageLiveLocation", chat_id=row.live_chat,
                                    message_id=row.live_msg, **pos)
                return
            except TelegramError as e:
                if "not modified" in e.description:
                    return
                log.info("live location for %s must be re-sent: %s", ev.name, e)
        chat = self.cfg.display_chat
        head = await self.bot.call("sendMessage", chat_id=chat, parse_mode="HTML", disable_notification=True,
                                   text=f"🔵 <b>{html.escape(ev.name)}</b> is sharing position")
        loc = await self.bot.call("sendLocation", chat_id=chat, live_period=LIVE_FOREVER,
                                  disable_notification=True,
                                  reply_parameters={"message_id": head["message_id"]}, **pos)
        self.store.set_live(uid, chat, loc["message_id"])
        self.store.kv_set("livehead:" + uid, str(head["message_id"]))

    async def _stop_live(self, uid: str) -> None:
        row = self.store.get(uid)
        if not row or not row.live_msg:
            return
        self.store.set_live(uid, None, None)
        self.roster_dirty = True
        for method, params in (
            ("stopMessageLiveLocation", {"message_id": row.live_msg}),
            ("editMessageText", {"message_id": int(self.store.kv_get("livehead:" + uid) or 0),
                                 "parse_mode": "HTML",
                                 "text": f"⚪ <b>{html.escape(row.callsign or uid)}</b> stopped sharing position"}),
        ):
            try:
                await self.bot.call(method, chat_id=row.live_chat, **params)
            except TelegramError as e:
                log.info("%s failed for %s: %s", method, uid, e)

    def roster_text(self) -> str:
        rows = [r for r in self.store.units() if self.store.sharing(r.uid)]
        if not rows:
            return "<b>Roster</b>\nNo TAK users are sharing position."
        lines = ["<b>Roster</b>"]
        for r in rows:
            t = cot.parse_time(r.time)
            age = int((cot.now() - t).total_seconds() // 60) if t else 0
            grid = mgrs_of(r.lat, r.lon)
            lines.append(f"🔵 <b>{html.escape(r.callsign or r.uid)}</b> <code>{r.lat:.5f}, {r.lon:.5f}</code>"
                         + (f" · <code>{grid}</code>" if grid else "") + f" · {age} min ago")
        return "\n".join(lines)

    async def _roster(self) -> None:
        text, chat = self.roster_text(), self.cfg.display_chat
        mid = self.store.kv_get("roster_msg")
        if mid:
            try:
                await self.bot.call("editMessageText", chat_id=chat, message_id=int(mid), text=text,
                                    parse_mode="HTML")
                return
            except TelegramError as e:
                if "not modified" in e.description:
                    return
        msg = await self.bot.call("sendMessage", chat_id=chat, text=text, parse_mode="HTML",
                                  disable_notification=True)
        self.store.kv_set("roster_msg", str(msg["message_id"]))
        try:
            await self.bot.call("pinChatMessage", chat_id=chat, message_id=msg["message_id"],
                                disable_notification=True)
        except TelegramError as e:
            log.info("could not pin roster: %s", e)

    # ------------------------------------------------- control from inside TAK

    async def _tak_command(self, ev: CotEvent, source: str, client) -> None:
        chat = ev.chat
        assert chat
        words = chat.text.lower().split()
        uid = chat.sender_uid
        if words[:2] == ["share", "on"]:
            self.store.set_sharing(uid, True)
            reply = "Sharing ON: your position is now published to Telegram."
            if self.store.get(uid):
                self._publish_live(uid, force=True)
        elif words[:2] == ["share", "off"]:
            self.store.set_sharing(uid, False)
            reply = "Sharing OFF: your position is no longer published."
            self.queue.submit(self.cfg.display_chat, ("live", uid), lambda: self._stop_live(uid))
        elif words[:1] == ["status"]:
            state = "ON" if self.store.sharing(uid) else "OFF"
            reply = f"Sharing is {state}. Display chat {self.cfg.display_chat}, operations chat {self.cfg.ops_chat or 'none'}."
        else:
            reply = HELP_TAK
        await self._tak_reply(uid, chat.sender_callsign, source, client, reply)

    async def _tak_reply(self, to_uid: str, to_callsign: str, source: str, client, text: str) -> None:
        xml = cot.build_chat(self.cfg.uid, self.cfg.callsign, text, to_uid, to_callsign)
        if source == "endpoint" and client:
            client.send(xml)
        elif source == "mesh" and self.mesh:
            row = self.store.get(to_uid)
            if not (row and row.endpoint and await self.mesh.send_direct(row.endpoint, xml)):
                self.mesh.send(xml, chat=True)

    def send_tak(self, xml: bytes, chat: bool = False) -> None:
        if self.mesh:
            self.mesh.send(xml, chat=chat)
        if self.endpoint:
            self.endpoint.broadcast(xml)

    # ------------------------------------------------------------ Telegram in

    async def on_update(self, upd: dict) -> None:
        if "callback_query" in upd:
            await self._callback(upd["callback_query"])
            return
        msg = upd.get("message") or upd.get("channel_post")
        if not msg or not msg.get("text"):
            return
        sender = msg.get("from") or {}
        if sender.get("is_bot"):
            return
        chat, text = msg["chat"]["id"], msg["text"].strip()
        thread = msg.get("message_thread_id") if msg.get("is_topic_message") else None

        def say(body: str) -> None:
            self.queue.submit(chat, None, lambda: self.bot.call(
                "sendMessage", chat_id=chat, text=body, parse_mode="HTML", message_thread_id=thread))

        cmd, _, arg = text.partition(" ")
        cmd = cmd.split("@")[0].lower()
        if cmd == "/id":
            say(f"Chat id: <code>{chat}</code>" + (f"\nYour user id: <code>{sender['id']}</code>" if sender else ""))
        elif cmd in ("/start", "/help"):
            say(HELP_TG)
        elif cmd == "/roster":
            say(self.roster_text())
        elif cmd == "/where":
            row = self.store.find_callsign(arg.strip())
            if not row or (row.kind == "unit" and not self.store.sharing(row.uid)):
                say("No shared object with that name.")
            else:
                self.queue.submit(chat, None, lambda: self.bot.call(
                    "sendLocation", chat_id=chat, latitude=row.lat, longitude=row.lon, message_thread_id=thread))
                say(f"<b>{html.escape(row.callsign)}</b> <code>{row.lat:.5f}, {row.lon:.5f}</code> "
                    f"<code>{mgrs_of(row.lat, row.lon)}</code>")
        elif text.startswith("/"):
            return
        elif chat == self.cfg.ops_chat and sender.get("id") in self.cfg.allow_users:
            name = "TG-" + (sender.get("first_name") or str(sender["id"]))[:20]
            self.send_tak(cot.build_chat(f"TG-{sender['id']}", name, text[:1000]), chat=True)

    async def _callback(self, cq: dict) -> None:
        action, _, key = (cq.get("data") or "").partition(":")
        uid = self.store.kv_get("cb:" + key)
        row = self.store.get(uid) if uid else None
        msg = cq.get("message") or {}
        chat = (msg.get("chat") or {}).get("id")
        note = ""
        if not row or not chat:
            note = "That object is no longer known."
        elif action == "pin":
            self.queue.submit(chat, None, lambda: self.bot.call(
                "sendLocation", chat_id=chat, latitude=row.lat, longitude=row.lon,
                reply_parameters={"message_id": msg["message_id"]}))
        elif action == "tak":
            ev = cot.parse(row.xml)
            data = object_package(ev)
            self.queue.submit(chat, None, lambda: self.bot.call(
                "sendDocument", files={"document": (f"{ev.name[:40]}.zip", data, "application/zip")},
                chat_id=chat, caption="Data package: open with ATAK, or use Import Manager.",
                reply_parameters={"message_id": msg["message_id"]}))
        try:
            await self.bot.call("answerCallbackQuery", callback_query_id=cq["id"], text=note or None)
        except TelegramError:
            pass

    # ------------------------------------------------------------------ loops

    async def _poll(self) -> None:
        offset = None
        while True:
            try:
                for upd in await self.bot.updates(offset):
                    offset = upd["update_id"] + 1
                    try:
                        await self.on_update(upd)
                    except Exception:
                        log.exception("update handler failed")
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.warning("getUpdates failed: %s", e)
                await asyncio.sleep(5)

    async def _announce(self) -> None:
        while True:
            if self.mesh:
                self.mesh.send(cot.build_contact(self.cfg.uid, self.cfg.callsign, self.mesh.advertised_endpoint))
            if self.endpoint:
                self.endpoint.broadcast(cot.build_contact(self.cfg.uid, self.cfg.callsign, "*:-1:stcp"))
            await asyncio.sleep(30)

    async def _housekeeping(self) -> None:
        while True:
            await asyncio.sleep(60)
            if self.roster_dirty:
                self.roster_dirty = False
                self.queue.submit(self.cfg.display_chat, "roster", self._roster, priority=2)
            for row in self.store.stale_cards(cot.iso(cot.now())):
                self.store.mark_removed(row.uid, 2)
                self.queue.submit(row.card_chat, ("card", row.uid),
                                  lambda r=row: self._mark_card(r, "⌛ STALE"), priority=2)

    async def run(self) -> None:
        from .tak.endpoint import Endpoint
        from .tak.mesh import Mesh

        await self.bot.start()
        me = await self.bot.call("getMe")
        log.info("Telegram bot @%s ready", me.get("username"))
        if not self.cfg.display_chat:
            log.warning("setup mode: telegram.display_chat is not set, so nothing is published. "
                        "Send /id in a chat to learn its id.")
        if self.cfg.mesh_enabled:
            self.mesh = Mesh(self.on_cot, self.cfg.mesh_iface_ip, self.cfg.mesh_direct_port)
            await self.mesh.start()
        if self.cfg.endpoint_enabled:
            self.endpoint = Endpoint(self.cfg.endpoint_host, self.cfg.endpoint_port, self.on_cot)
            await self.endpoint.start()
        try:
            await asyncio.gather(self.queue.run_forever(), self._poll(), self._announce(), self._housekeeping())
        finally:
            for a in (self.mesh, self.endpoint):
                if a:
                    await a.stop()
            await self.bot.close()
