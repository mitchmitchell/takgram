"""Rate-limited outbound queue for Telegram calls.

Jobs for the same (chat, key) are coalesced: a newer edit replaces an older
one that has not been sent yet. Each chat has its own minimum interval and
there is a global one. A RetryAfter from Telegram pauses that chat and the
job is retried, never dropped.
"""

from __future__ import annotations

import asyncio
import itertools
import logging
import time
from dataclasses import dataclass
from typing import Awaitable, Callable

log = logging.getLogger("takgram.queue")


class RetryAfter(Exception):
    def __init__(self, seconds: float) -> None:
        super().__init__(f"retry after {seconds}s")
        self.seconds = seconds


@dataclass
class Job:
    chat: int
    key: object
    run: Callable[[], Awaitable[None]]
    priority: int
    seq: int
    tries: int = 0


class OutboundQueue:
    def __init__(self, chat_interval: float = 3.1, global_interval: float = 0.05, max_tries: int = 5) -> None:
        self.chat_interval = chat_interval
        self.global_interval = global_interval
        self.max_tries = max_tries
        self.jobs: dict[object, Job] = {}
        self.ready_at: dict[int, float] = {}
        self.global_ready = 0.0
        self.seq = itertools.count()
        self.wake = asyncio.Event()
        self.sent = 0

    def submit(self, chat: int, key: object, run: Callable[[], Awaitable[None]], priority: int = 1) -> None:
        """priority 0 is urgent. key=None never coalesces."""
        seq = next(self.seq)
        ident = (chat, key) if key is not None else (chat, "_", seq)
        old = self.jobs.get(ident)
        self.jobs[ident] = Job(chat, key, run, min(priority, old.priority) if old else priority,
                               old.seq if old else seq)
        self.wake.set()

    def __len__(self) -> int:
        return len(self.jobs)

    def _next(self, now: float) -> tuple[object, Job] | tuple[None, float]:
        best = None
        soonest = float("inf")
        for ident, job in self.jobs.items():
            at = max(self.ready_at.get(job.chat, 0.0), self.global_ready)
            if at <= now:
                if best is None or (job.priority, job.seq) < (best[1].priority, best[1].seq):
                    best = (ident, job)
            else:
                soonest = min(soonest, at)
        return best if best else (None, soonest)

    async def run_forever(self) -> None:
        while True:
            now = time.monotonic()
            ident, item = self._next(now)
            if ident is None:
                self.wake.clear()
                delay = None if item == float("inf") else max(0.0, item - now)
                try:
                    await asyncio.wait_for(self.wake.wait(), delay)
                except asyncio.TimeoutError:
                    pass
                continue
            job: Job = item
            del self.jobs[ident]
            self.ready_at[job.chat] = now + self.chat_interval
            self.global_ready = now + self.global_interval
            try:
                await job.run()
                self.sent += 1
            except RetryAfter as e:
                self.ready_at[job.chat] = time.monotonic() + e.seconds + 0.5
                self.jobs.setdefault(ident, job)  # keep a newer replacement if one arrived
                log.warning("rate limited on chat %s for %.0fs", job.chat, e.seconds)
            except Exception as e:
                job.tries += 1
                if job.tries < self.max_tries:
                    self.jobs.setdefault(ident, job)
                    self.ready_at[job.chat] = time.monotonic() + min(30, 2**job.tries)
                    log.warning("send failed (try %d): %s", job.tries, e)
                else:
                    log.error("dropping job after %d tries: %s", job.tries, e)
