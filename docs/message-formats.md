# TAKgram message formats

What TAKgram writes into a linked Telegram chat, and what it reads from it. Everything read is untrusted:
it is size-capped, validated field by field, and never forwarded to ATAK as received.

The code and tests are in `android/core` (`Cot1Codec`, `IdentityCard`, `Bridge`).

## Object message: `#cot1`

A TAK object is one Telegram message: text a person can read, then one machine line as the **last line**.

```
Tank 1
Hostile ground armor
30.30000, -97.70000
#cot1 <payload>
```

- `payload` = base64url without padding of the raw DEFLATE (no zlib header) of the event's UTF-8 XML.
- Only the last line is examined. A `#cot1` anywhere else in a message is ordinary text.
- Limits: payload at most 8 192 characters; inflated XML at most 64 KiB. Anything larger is dropped.
- The same line is read from a text message or from the caption of a photo or document.
- TAKgram posts an object as a photo (the object's symbol and name on a map thumbnail) with this text as
  the caption, cut to Telegram's 1 024-character caption limit. If no picture can be drawn it posts the
  same text as a plain message. The coordinates, the grid reference and the machine line are sent as
  fixed-width text, so Telegram does not turn grid digits into a phone-number link.
- The map thumbnail uses OpenStreetMap tiles, fetched by the posting phone, cached on it and credited on
  the picture. With no network for tiles the symbol is drawn on a plain grid.

Receiving app:

1. Ignores the message if the sender is itself (its ATAK already has the object).
2. Decodes and parses with DTDs and entities refused.
3. Drops it if the event is already stale, or if an event with the same uid and the same or a newer time
   has been seen (de-duplication by uid and time).
4. Writes a **new** event from the validated fields (uid, type, how, time, stale, point, callsign, remarks,
   team, role, track, colour, icon path) and sends that to ATAK. Unknown detail is not passed on.

## Identity card: `#takid1`

Each TAKgram user posts one card per linked chat and edits it when their settings change.

```
TAKgram identity
Callsign: ALPHA-2
Team: Cyan
#takid1 uid=ANDROID-f2ea670fc71b500d cs=ALPHA-2 team=Cyan
```

- Values are URL-encoded. `team` must be one of ATAK's team colours; anything else is read as White.
- A card describes **only the account that posted it**. Telegram authenticates the sender, so one member
  cannot rename another.
- Effect: that person's Telegram live location is shown in ATAK under the card's uid, callsign and team.
  If the uid is the person's real ATAK uid, the Wi-Fi contact and the Telegram contact are one icon.

## Native Telegram content

| In the chat | In ATAK |
| --- | --- |
| Live location from another member | A team-member contact, repeated every minute while the share is active, stale after 3 minutes |
| Live location stopped or expired | No more updates; the icon goes stale |
| Text message | A chat message in All Chat Rooms from that contact |
| Location pin | A yellow "unknown" marker named "Pin (sender)" |
| Text reply to a pin | The pin's marker is renamed to the reply text |
| Anything from a bot not on the trusted list | Ignored |
| Anything from a channel or anonymous admin | Ignored |

A member with no identity card is shown as `TG-<first name>` with uid `TG-<telegram user id>`, team White.

## Own position

Sent as a native Telegram live location with `live_period = 0x7FFFFFFF` ("until stopped"), edited when the
phone has moved 10 m (at most every 15 s) and at least once a minute. A receiving TAKgram treats a
"forever" share that has been silent for 3 minutes as gone.
