"""Minimal async Telegram Bot API client (long polling, no webhook)."""

from __future__ import annotations

import json
import logging

import aiohttp

from .ratequeue import RetryAfter

log = logging.getLogger("takgram.telegram")


class TelegramError(Exception):
    def __init__(self, code: int, description: str) -> None:
        super().__init__(f"{code}: {description}")
        self.code = code
        self.description = description


class Bot:
    def __init__(self, token: str, api_base: str = "https://api.telegram.org") -> None:
        self.base = f"{api_base}/bot{token}/"
        self.session: aiohttp.ClientSession | None = None

    async def start(self) -> None:
        self.session = aiohttp.ClientSession(trust_env=True)

    async def close(self) -> None:
        if self.session:
            await self.session.close()

    async def call(self, method: str, files: dict[str, tuple[str, bytes, str]] | None = None,
                   timeout: float = 30, **params):
        """Call a Bot API method. `files` maps field name to (filename, bytes, mime)."""
        assert self.session, "call start() first"
        params = {k: v for k, v in params.items() if v is not None}
        kw: dict = {"timeout": aiohttp.ClientTimeout(total=timeout)}
        if files:
            form = aiohttp.FormData()
            for k, v in params.items():
                form.add_field(k, v if isinstance(v, str) else json.dumps(v))
            for k, (name, data, mime) in files.items():
                form.add_field(k, data, filename=name, content_type=mime)
            kw["data"] = form
        else:
            kw["json"] = params
        async with self.session.post(self.base + method, **kw) as r:
            body = await r.json(content_type=None)
        if body.get("ok"):
            return body["result"]
        code = body.get("error_code", r.status)
        retry = (body.get("parameters") or {}).get("retry_after")
        if code == 429 and retry is not None:
            raise RetryAfter(float(retry))
        raise TelegramError(code, body.get("description", "unknown error"))

    async def updates(self, offset: int | None, timeout: int = 50) -> list[dict]:
        """Long-poll getUpdates. Separate from call() because the API parameter is also named timeout."""
        assert self.session
        params = {"timeout": timeout, "allowed_updates": ["message", "channel_post", "callback_query"]}
        if offset is not None:
            params["offset"] = offset
        async with self.session.post(self.base + "getUpdates", json=params,
                                     timeout=aiohttp.ClientTimeout(total=timeout + 15)) as r:
            body = await r.json(content_type=None)
        if not body.get("ok"):
            raise TelegramError(body.get("error_code", r.status), body.get("description", ""))
        return body["result"]
