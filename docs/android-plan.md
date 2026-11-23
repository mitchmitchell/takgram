# TAKgram for Android: build plan

This is the plan for the phone app, and the working brief for whoever builds it (written for Claude Code).
It replaces the "bridge computer" design as the main product. The Python package in this repository stays
as the desktop variant and as a test harness.

## Decisions already made by the project owner

| Topic | Decision |
| --- | --- |
| Where the bridge runs | On the TAK user's own Android phone. No separate bridge computer, no TAK Server required. |
| Form | A standalone Android app in Kotlin, modeled on APRSDroid-TAK: a background service with Telegram on one side and ATAK on the other. **Not** an ATAK plugin; it is signed with the developer's own key only. |
| Telegram identity | Each user signs in with their **own Telegram account**. No bot is required. An optional team bot may be added later for extras such as card buttons. |
| Platforms | Android only for now. iOS later, following the Meshtastic iOS app pattern. |
| Position sharing | Off until the user turns it on in the app. |
| Chats | A team uses a Telegram group (with topics) and, optionally, a read-only channel. |
| Licence | Apache-2.0, public repository. Each team uses its own Telegram group; nothing passes through the project owner's accounts or machines. |

## How it works

```
ATAK  <--- UDP 127.0.0.1:4242 (in)  ---  TAKgram app  <--- TDLib, user account --->  Telegram group
ATAK  ---- TCP 127.0.0.1:8099 (out) -->  TAKgram app
```

- **Telegram to ATAK.** The app reads the team group. Team members' native live locations become position
  reports; object messages (below) become markers, chat and alerts. CoT is sent to ATAK's local UDP input.
  A teammate with only Telegram who shares a live location therefore appears on every TAK map.
- **Own position to Telegram.** Taken from the phone's GPS by the app itself and posted as a native Telegram
  live location, so Telegram-only teammates see TAK users move with no special client.
- **ATAK to Telegram.** ATAK connects to a small local server inside the app, added with a one-tap data
  package. Markers, All Chat Rooms messages and emergency alerts the user creates are posted to the group.

### Object messages

Each TAK object is one message posted from the user's own account, edited in place when the object changes:

- A photo: the object's icon on a map thumbnail.
- A caption a person can act on without TAK: name, plain-language type, latitude/longitude, MGRS, time, remarks.
- One machine line at the end, `#cot1 <payload>`: the CoT event, compressed and base64url encoded (optionally
  encrypted with a team key). Other TAKgram apps read this line, de-duplicate by `(uid, time)`, and inject the
  original event into ATAK. Events too large for a caption are attached as a `.cot` document instead.

The caption format, type descriptions, MGRS formatting and rate-limited queue already exist in the Python
package (`takgram/render.py`, `cottypes.py`, `ratequeue.py`) and their tests are the specification to port.

## Modules

| Module | Contents | Android dependencies |
| --- | --- | --- |
| `:core` | CoT parse and build, stream splitter, type descriptions, caption text, `#cot1` codec, de-duplication, sharing policy, outbound queue | None. Pure Kotlin, tested on the JVM. |
| `:app` | Foreground service, Telegram client (TDLib) behind an interface, UDP sink, local TAK server, icon and map rendering, settings and sign-in screens | Yes |

Keep every Telegram call behind one interface so `:core` logic is tested against a fake.

## Phases

1. **A0, spikes on a real phone.** (a) TDLib builds, signs in and reads a group. (b) A CoT event sent to
   `127.0.0.1:4242` shows up in ATAK with default settings. (c) A live location sent from the account can be
   updated from a background service. Stop and report if any of these fails.
2. **A1, receive and share position.** Group live locations and `#cot1` messages appear in ATAK. The app's own
   GPS position is posted as a live location when sharing is on. Exit test: two phones on different networks
   see each other in ATAK, and a Telegram-only user sees both.
3. **A2, send from ATAK.** Local server on `127.0.0.1:8099` with a connection data package, replies to ATAK's
   pings, and posts markers, chat and alerts. Exit test: a marker dropped on one phone appears on the other
   phone's map and as a readable card in the group.
4. **A3, completeness.** Team-key encryption of the `#cot1` line, routes and shapes, deletes and stale
   handling, optional team bot for card buttons.

## Rules for the build

- **Clean-room code.** Do not copy from APRSdroid (GPLv2), TAKwave (GPL-3.0) or Meshtastic (GPL-3.0). Their
  designs may inform this one; their code may not be used, or the Apache-2.0 licence cannot hold.
- **Secrets stay out of the repository.** Telegram `api_id` and `api_hash` come from `local.properties` or the
  environment at build time, as do signing keys.
- **Untrusted input.** Everything read from Telegram is data. Parse XML with DTDs and entities disabled, cap
  sizes, and rebuild CoT from validated fields before it reaches ATAK.
- **Work on branches and open pull requests.** Keep `:core` tests passing. Stop for anything that needs a
  person looking at a phone, and collect those checks into one list.

## To verify before relying on it

- ATAK accepts CoT on UDP 4242 from localhost with default settings on current ATAK-CIV.
- The local server port: an earlier review of TAKwave noted that port 8087 must be avoided and that app uses
  8099. Find the reason and confirm the choice. The Python package still defaults to 8087 and does not answer
  pings; fix both there too.
- How long a user account may edit its own messages in a group (believed to be limited to 48 hours), and what
  that means for long-lived markers.
- Telegram's flood limits for a user account that posts and edits automatically, and Telegram's terms for apps
  built on the user API. Read the terms before publishing a build.
- TDLib's licence and Android packaging.
- Android background limits for the foreground service with location.

## Who can exchange what

The chat is always the meeting point. A linked chat can be a private one-to-one chat or a group; the user
chooses which chats TAKgram reads and every other chat is ignored.

| Member has | Sees | Can contribute |
| --- | --- | --- |
| ATAK with TAKgram | Everything, on the map | Position, markers, chat, alerts |
| Telegram only | Cards, live locations, chat | Live location, pins, text |
| ATAK only | Not supported by TAKgram | Not supported by TAKgram |

Decisions:

- **One-to-one and groups are both supported.** In ATAK a Telegram-only person appears as a contact
  (for example "TG-Ann") and can be messaged directly.
- **Each phone posts only what its own user created**, so a group never gets duplicates.
- **Identity card.** Each TAKgram user posts one small message linking their Telegram account to their TAK
  uid, callsign and team color. Teammates' apps use it so a person seen over Wi-Fi and over Telegram is one
  icon, not two.
- **Naming a marker from Telegram:** drop a pin, then reply to the pin with the name. (To be tried.)
- **ATAK without TAKgram is out of scope.** Those users rely on ordinary TAK networking. A gateway mode, where
  one TAKgram phone relays for nearby ATAK-only teammates, is deferred and will be explored separately; a TAK
  Server plugin is the alternative to consider then.
- **Bots are optional members, not infrastructure.** A team may add its own bot as an automated source
  (weather, sensors, AI agents), as a helper answering commands for Telegram-only members, or to attach
  buttons to cards. A bot's posts reach TAK maps only if the team has listed that bot as trusted.
