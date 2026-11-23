"""Phase 0 spike: how fast can a bot edit messages in one chat before Telegram pushes back?

Sends one text message and one live location, then edits each alternately at
a fixed interval and reports every 429 with its retry_after. Use the result
to set export.chat_interval_s and export.pli_interval_s.

    python spikes/edit_rate.py --token TOKEN --chat -1001234567890 --interval 1.0 --count 60
"""

import argparse
import time

from _api import call

p = argparse.ArgumentParser()
p.add_argument("--token", required=True)
p.add_argument("--chat", required=True, type=int)
p.add_argument("--interval", type=float, default=1.0)
p.add_argument("--count", type=int, default=60)
a = p.parse_args()

ok, text = call(a.token, "sendMessage", chat_id=a.chat, text="edit-rate spike 0", disable_notification=True)
if not ok:
    raise SystemExit(text)
ok, loc = call(a.token, "sendLocation", chat_id=a.chat, latitude=30.0, longitude=-97.0,
               live_period=3600, disable_notification=True)
if not ok:
    raise SystemExit(loc)

limited, errors, start = [], [], time.time()
for i in range(1, a.count + 1):
    if i % 2:
        ok, r = call(a.token, "editMessageText", chat_id=a.chat, message_id=text["message_id"],
                     text=f"edit-rate spike {i}")
    else:
        ok, r = call(a.token, "editMessageLiveLocation", chat_id=a.chat, message_id=loc["message_id"],
                     latitude=30.0 + i * 0.0001, longitude=-97.0)
    if not ok:
        retry = (r.get("parameters") or {}).get("retry_after")
        if r.get("error_code") == 429:
            limited.append((i, retry))
            print(f"edit {i}: 429, retry_after={retry}s")
            time.sleep((retry or 1) + 0.5)
        else:
            errors.append((i, r.get("description")))
            print(f"edit {i}: error {r.get('description')}")
    time.sleep(a.interval)

elapsed = time.time() - start
call(a.token, "stopMessageLiveLocation", chat_id=a.chat, message_id=loc["message_id"])
print(f"\n{a.count} edits in {elapsed:.0f}s at a {a.interval}s interval: "
      f"{len(limited)} rate-limited, {len(errors)} other errors.")
if limited:
    print(f"First limit at edit {limited[0][0]}; longest retry_after {max(x[1] or 0 for x in limited)}s.")
else:
    print("No rate limiting at this interval.")
