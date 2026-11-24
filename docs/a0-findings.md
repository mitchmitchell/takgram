# A0 findings

What was verified during phase A0, how, and what is still open. Environment: Windows 11 host, Android
emulator (API 34 Google APIs, x86_64, Pixel 6 profile), ATAK-CIV 5.8.0.10 (`daaa2b7`) installed from the
release APK with default settings, TDLib 1.8.71.

## Spike (b): CoT to ATAK over UDP 127.0.0.1:4242 — PASS (emulator and real phone)

- With default settings ATAK-CIV 5.8 listens on UDP `0.0.0.0:4242` (seen with `ss -lunp` as root).
- A marker sent from the TAKgram app to `127.0.0.1:4242` appeared on ATAK's map with the right type, callsign
  and position. An earlier marker sent from the adb shell user also appeared.
- **Pitfall found:** on Android `InetAddress.getLoopbackAddress()` is IPv6 `::1`. ATAK's input socket is
  IPv4-only, so datagrams sent there are silently lost. TAKgram now sends to IPv4 `127.0.0.1` explicitly
  (`CotUdpSender.LOOPBACK_V4`).
- Confirmed on a real phone (Galaxy Z Fold, ATAK-CIV 5.8): the test marker appeared in ATAK (device check D2).

## Ports ATAK opens by default (ATAK-CIV 5.8, emulator, `ss -ltnup`)

| Protocol | Port | Bound to |
| --- | --- | --- |
| TCP | 4242, 8087, 8443 | 0.0.0.0 |
| UDP | 4242, 4243, 6969, 8087, 10011, 17012 | 0.0.0.0 |

**Why 8087 must be avoided:** ATAK itself listens on TCP and UDP 8087. A local server on 8087 would collide
with ATAK on the same phone. 8087 is also the plain-text CoT port of TAK Server and FreeTAKServer. **8099 is
free and is TAKgram's port.**

## ATAK streaming connection behaviour (for A2)

- ATAK auto-imports data packages copied into `/sdcard/atak/tools/datapackage/` and deletes them after
  import. A connection package for `127.0.0.1:8099:tcp` made ATAK connect at once.
- On connecting, ATAK sends its own position (`a-f-G-U-C` with `takv`, `contact`, `__group`) and asks the
  server for a contact list over HTTP. That request fails harmlessly ("failed to get callsign list").
- Ping, captured verbatim: `type="t-x-c-t"`, `uid="<device uid>-ping"`, `how="m-g"`, `stale` 10 s after
  `time`, point 0,0, empty `<detail/>`. ATAK sends one about every 5 s when idle.
- **Without a reply ATAK dropped the connection 25 s after connecting** (3 pings). Replying `t-x-c-t-r` kept
  it connected for the whole 155 s observation. The ping reply is required.

## Spike (a): TDLib on Android, sign in, read one chosen chat — PASS (real phone)

- TDLib 1.8.71 builds for arm64-v8a and x86_64 with NDK r28.2 and OpenSSL 3.5.9, without root, inside WSL
  (`android/tdlib/build-tdlib.sh`). Both `.so` files have 16 KB-aligned LOAD segments, as Android 15+
  devices require.
- On a Galaxy Z Fold (arm64) the app signed in to a production account, listed chats, linked one chat and
  logged a text message sent to that chat from another account. Messages in other chats are ignored.
- **Sign-in findings (all observed 2026-11-22/23):**
  - **Telegram's test accounts no longer work.** Test number `99966 2 7413` got a 5-digit code prompt, but the
    documented codes `22222` and `222222` were rejected with `PHONE_CODE_INVALID`. Public reports in the
    TDLib and GramJS trackers say Telegram disabled auto-created test accounts in 2026. Sign-in cannot be
    automated; core logic is tested against a fake Telegram client.
  - **Login codes were never delivered for this new api_id.** For an account with other logged-in devices
    the server answers `AuthenticationCodeTypeTelegramMessage` with no next type (no SMS fallback), but no
    code message arrived in the official app, in nine requests across two numbers. Cause not confirmed.
  - **QR sign-in works** and needs no code: the app shows the `tg://login` link as a QR code and another
    device on the same account scans it (Settings > Devices > Link Desktop Device). The QR code must be on
    the phone's own screen; it expires in about 30 s, too fast to relay any other way. **QR is the primary
    sign-in method**; the phone-number route stays as a fallback.
  - Phone numbers must be international. The app completes a national number from the SIM's country.

## Spike (c): live location updated from a background service — PASS (real phone)

- `LocationShareService` is a foreground service of type `location` using framework `LocationManager` (no
  Google Play Services). It sends a live location with `livePeriod = 0x7FFFFFFF` and edits it at most every
  15 s.
- On the Galaxy Z Fold, in a private chat with a second account: the live location appeared, **30 edits
  succeeded and none failed**, the pin followed the phone, and it **kept updating with the phone locked**
  (owner's observation; a few minutes, not a long Doze test).
- The server echoed `livePeriod = 2147483647` and `expiresIn = 2147483647` on every edit: a "forever" live
  location is accepted for a user account and its edits are not bound by the message edit window.
- **A live location could not be sent to a bot chat.** The send was never confirmed and every edit failed
  with `400 Message can't be edited`. The server's reason was not captured (that build did not log send
  failures; later builds do). Linked chats should be people or groups; the chat picker should say so (A1).
- **Mixed location sources make the pin jump.** GPS fixes and a coarse cell/Wi-Fi fix about 750 m away
  alternated. `core.FixFilter` now drops a much less accurate fix while a recent good one exists (unit
  tested; not yet confirmed on the phone).
- A message that the server has not confirmed has a local id (not a multiple of 2^20) and cannot be edited.
  The app now waits for `updateMessageSendSucceeded` before editing and stops sharing on a failed send.
- Still open: a long Doze test and manufacturer battery managers (device check D4).

## Message edit window (for long-lived markers)

- Telegram's MTProto config has one global `edit_time_limit` ("Only messages with age (in seconds) smaller
  than the one specified in this field can be edited"). The value is not documented. It is widely reported
  as 172 800 s (48 h) and will be read from the live config once signed in.
- Consequence for A1/A2: a marker card older than the limit cannot be edited in place. The plan is to post a
  fresh card and mark the old one as superseded (or delete it, where deletion is still allowed).
  `revoke_time_limit` and `revoke_pm_time_limit` govern deletion and will also be read from config.

## Flood limits

- Telegram documents `FLOOD_WAIT_X` (wait X seconds), `FLOOD_PREMIUM_WAIT_X` and `SLOWMODE_WAIT_X`, but
  publishes no numbers for user accounts. Telegram's test servers are no longer usable, so limits will be
  measured carefully with the owner's account in a test chat. The outbound queue honours every wait TDLib
  reports. Observed so far: 30 live-location edits at one per 15 s with no flood wait.

## Telegram API terms (summary, from core.telegram.org/api/terms, read 2026-11-22)

Read before any build is shared with other people.

1. **Own api_id:** each developer must obtain their own. Done; it is read from a file outside the repo.
2. **Disclosure:** users must be told the app uses the Telegram API, prominently, in store listings and in
   the app's introduction.
3. **Name and logo:** the app name must not contain "Telegram" unless preceded by "Unofficial". The
   Telegram logo must not be used. "TAKgram" is fine as long as the logo is not used.
4. **Privacy and security:** guard user privacy "with utmost care" and follow Telegram's security guidelines.
5. **No interference:** it is forbidden to interfere with basic Telegram functionality, including "making
   actions on behalf of the user without the user's knowledge and consent". TAKgram posts and edits from the
   user's account automatically, so the user must turn each behaviour on knowingly (position sharing is off by
   default) and the app must show what it posts.
6. **Feature parity:** do not force users of other clients to install the app to see messages. TAKgram cards
   are readable in any Telegram client.
7. **No AI training:** data obtained through the API must not be used to train or fine-tune AI/ML models.
8. **Sponsored messages:** an app that shows channel content must support official sponsored messages.
   Relevant only if TAKgram ever displays channel content.
9. **Monetisation:** allowed, but every method must be named in every store listing.
10. **Enforcement:** Telegram notifies the developer. If the breach is not fixed within 10 days, it can cut
    API access and ask app stores to remove the app.

The terms say nothing specific about automated posting rates. Flood limits are the practical bound.

## Android background limits (foreground service with location)

- Android 14+: a `location` foreground service needs `FOREGROUND_SERVICE_LOCATION` in the manifest and a
  granted location permission **before** `startForeground`. It must be started while the app is visible (the
  "while in use" exemption) unless the app holds background location. TAKgram starts it from the activity
  when the user taps **Start sharing**.
- If the system restarts a sticky service while the app is in the background, a location foreground service
  cannot start without `ACCESS_BACKGROUND_LOCATION`. For A1: either request background location with a clear
  explanation, or stop sharing on restart and notify the user. To decide with the owner.
- Doze: processes with a foreground-service state keep network access during Doze. To be confirmed on the
  emulator (`dumpsys deviceidle force-idle`) and on a real phone (check D4). Manufacturer battery managers
  vary.
