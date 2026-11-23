"""Phase 0 spike: can bot B see a message posted by bot A?

Answers the open question behind the trunk design. Run it once against a
channel where both bots are administrators, and once against a group where
both are members (privacy mode off, and bot-to-bot communication enabled for
both bots in BotFather if that setting exists for your bots).

    python spikes/bot_to_bot.py --a TOKEN_A --b TOKEN_B --chat -1001234567890

No webhook may be set on bot B.
"""

import argparse
import time
import uuid

from _api import call

p = argparse.ArgumentParser()
p.add_argument("--a", required=True, help="token of the posting bot")
p.add_argument("--b", required=True, help="token of the listening bot")
p.add_argument("--chat", required=True, type=int)
p.add_argument("--wait", type=int, default=20)
a = p.parse_args()

ok, chat = call(a.b, "getChat", chat_id=a.chat)
if not ok:
    raise SystemExit(f"bot B cannot see the chat: {chat}")
print(f"chat type: {chat['type']}")

# Drain B's backlog so only new updates are counted.
offset = None
while True:
    ok, ups = call(a.b, "getUpdates", timeout=0, **({"offset": offset} if offset else {}))
    if not ok:
        raise SystemExit(f"getUpdates failed for bot B: {ups}")
    if not ups:
        break
    offset = ups[-1]["update_id"] + 1

nonce = "takgram-spike-" + uuid.uuid4().hex[:12]
ok, sent = call(a.a, "sendMessage", chat_id=a.chat, text=f"#cot1 spike {nonce}", disable_notification=True)
if not ok:
    raise SystemExit(f"bot A could not post: {sent}")
print(f"bot A posted message {sent['message_id']}")

deadline, seen = time.time() + a.wait, None
while time.time() < deadline and not seen:
    ok, ups = call(a.b, "getUpdates", timeout=5, allowed_updates=["message", "channel_post"],
                   **({"offset": offset} if offset else {}))
    for u in ups if ok else []:
        offset = u["update_id"] + 1
        m = u.get("channel_post") or u.get("message") or {}
        if nonce in (m.get("text") or ""):
            seen = "channel_post" if "channel_post" in u else "message"

call(a.a, "deleteMessage", chat_id=a.chat, message_id=sent["message_id"])
if seen:
    print(f"RESULT: YES. Bot B received bot A's post as a '{seen}' update. This chat type works as a trunk.")
else:
    print(f"RESULT: NO. Bot B saw nothing within {a.wait}s. This chat type cannot carry the trunk as configured.")
