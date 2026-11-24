# Device checks

Checks that need a person and a real phone. Each has exact steps and the expected result. Work through them
in any order; report the result (pass, fail and what you saw) for each number.

Everything not listed here has been tested on the Android emulator (API 34, x86_64) with ATAK-CIV 5.8.0.10.

## A0

Status on 2026-11-23 (Galaxy Z Fold, ATAK-CIV 5.8): **D1 pass, D2 pass, D3 pass** (short run, a few minutes
locked). D4 not yet run.

### D1. TDLib runs on a real arm64 phone

1. Install `app-debug.apk` (production-server build, `-Ptakgram.testDc=false`) on the phone.
2. Open TAKgram. The status should read `Servers: production` and `Sign-in: WaitPhoneNumber`.
3. Tap **Sign in with QR code**, then scan the code with another phone signed in to the same Telegram
   account (Telegram > Settings > Devices > Link Desktop Device). The status should change to `Sign-in: Ready`.

Expected: no crash. Sign-in state appears within 5 seconds. This proves the arm64 `libtdjni.so` loads; the
emulator only exercises the x86_64 build.

### D2. A CoT event from TAKgram appears in ATAK on a real phone

1. Open ATAK with default network settings. Leave it running.
2. In TAKgram tap **Send test CoT to ATAK (UDP 4242)**.
3. Switch to ATAK and pan to Austin, TX (30.269, -97.739).

Expected: a yellow "unknown" marker named **TAKgram A0 test**. Tapping it shows the remark
"Sent by the TAKgram app to UDP 127.0.0.1:4242".

### D3. Live location keeps updating with the screen off

1. Sign in, link a test chat with a person or a group (not a bot), tap **Start sharing my position**, allow location and notifications.
2. Confirm the notification "TAKgram is sharing your position" is shown.
3. Lock the phone and walk or drive for 15 minutes.
4. On another device, open the test chat in Telegram and watch the live location.

Expected: the live location moves during the whole 15 minutes, updating at least every 30 seconds while
moving. Then tap **Stop sharing** in the notification: the live location in Telegram ends.

### D4. Battery optimisation on the manufacturer's skin

1. With sharing on, leave the phone locked and still for 2 hours.
2. Then move it a few hundred metres with the screen still off.

Expected: the live location in Telegram follows. If it does not, note the phone make and model, the Android
version, and whether Settings > Apps > TAKgram > Battery is "Optimised" or "Unrestricted".

## A1

Status on 2026-11-23 (Galaxy Z Fold with ATAK-CIV 5.8, second account on an iPhone): **D5 pass, D6 pass,
D7 pass** (pin, then named by a reply), **D8 runs** (jumping not yet reported on), **D9 pass** (card posted,
then edited in place). Not yet confirmed: D10 (needs a second Android phone), D11.

Tested on the emulator with made-up Telegram messages: teammate position, identity card, chat, pin, pin
naming and a `#cot1` marker all appear in ATAK-CIV 5.8. The checks below need real accounts and phones.

Setup for all of them: TAKgram signed in on phone A, a linked chat with a second account, ATAK open on
phone A, and **Turn bridge on** tapped in TAKgram.

### D5. A Telegram-only teammate appears in ATAK

1. On the second account (plain Telegram, any phone), share a live location into the linked chat for 15
   minutes.
2. On phone A, open ATAK and its contact list.

Expected: a contact named `TG-<first name>` at that position, in white. It follows the other phone as it
moves and stays on the map while the share is active. TAKgram's status line "Sharing with me" names it.

### D6. A Telegram message arrives in ATAK chat

1. From the second account, send the text `radio check` into the linked chat.

Expected: ATAK shows a new chat message in **All Chat Rooms** from `TG-<first name>` reading `radio check`.

### D7. A pin becomes a marker and a reply names it

1. From the second account, send a location pin (attach > Location > pick a point) into the linked chat.
2. Reply to that pin with the text `Rally point`.

Expected: after step 1 a yellow marker named `Pin (TG-<first name>)` at that point in ATAK. After step 2 the
same marker is named `Rally point`.

### D8. Own position reaches the chat, without jumping

1. In TAKgram tap **Share my position**. Watch the linked chat from the second account for 10 minutes while
   moving, then for 5 minutes while still.

Expected: a live location that follows phone A with no jumps to far-off points. While still, it does not
move, and Telegram keeps showing it as live.

### D9. Identity card

1. In TAKgram set a callsign and team colour, then tap **Save identity and post it to the chat**.

Expected: one message in the chat reading "TAKgram identity", the callsign and the team. Changing the
callsign and saving again edits that message; no second card appears.

### D10. Two TAKgram phones see each other (A1 exit test)

Needs two Android phones, each with ATAK and TAKgram, signed in to different Telegram accounts, linked to
the same chat, on different networks (one on mobile data), both with the bridge on and sharing on.

Expected: each ATAK shows the other user as a contact with the callsign and team colour from that user's
identity card, moving as the phone moves. A third, Telegram-only member of the chat sees both live locations.
On either phone, **Post a test marker to the chat** makes a yellow marker named "Test marker (callsign)"
appear in the other phone's ATAK.

### D11. Bridge survives the screen being off

1. With the bridge on and ATAK running, lock phone A for 30 minutes while the second account keeps sharing
   and moving.
2. Unlock and open ATAK.

Expected: the teammate's icon is at the current position and not stale (grey).

## A2

Status on 2026-11-23 (Galaxy Z Fold with ATAK-CIV 5.8, second account on an iPhone): **D12 pass, D13 pass,
D14 pass, D16 pass**. A marker that had come from a Telegram pin was correctly not posted back. Not yet
confirmed: D15 (sending to the "Telegram: ..." contact), D17 (emergency alert), D18 (needs a second Android
phone).

Observed: because a card is edited in place, an update to an older marker happens far back in the chat and
is easy to miss. To decide whether an update should also be announced.

Tested on the emulator with ATAK-CIV 5.8: the one-tap connection package imports and ATAK connects; the
connection stays up (pings answered); TAKgram reads the callsign and team from ATAK; a marker broadcast from
ATAK reaches TAKgram. The emulator is not signed in to Telegram, so posting is checked below.

Setup: TAKgram signed in, a linked chat with a second account, the bridge on, ATAK open.

### D12. Connect ATAK to TAKgram with one tap

1. In TAKgram tap **Connect ATAK to TAKgram (once)**. ATAK opens and imports the package.
2. Return to TAKgram.

Expected: the status reads `ATAK connected: yes`, and the log shows "identity from ATAK" with your ATAK
callsign and team. In ATAK, Settings > Network Preferences > TAK Servers lists "TAKgram" as connected. The
identity card in the chat is edited to your ATAK callsign.

### D13. A marker from ATAK becomes a card in the chat

1. In ATAK drop a marker (the point dropper), then tap **SEND** and **Broadcast**.

Expected: one message in the chat from your account, such as "R.23.225340 · Hostile ground track", the
coordinates, the MGRS grid, the time, and a final `#cot1` line.

### D14. The card is edited when the marker changes

1. In ATAK rename or move that marker and broadcast it again.

Expected: the same message in the chat changes (marked "edited"); no second card appears.

### D15. A marker sent to a Telegram contact

1. In ATAK tap **SEND** on a marker, tick the `TG-...` contact and tap **Send**.

Expected: a card in the chat. A marker sent only to a contact who is not a chat member does not appear.

### D16. Chat from ATAK reaches Telegram

1. In ATAK open Contacts > All Chat Rooms and send `radio check`.

Expected: `radio check` appears in the chat as an ordinary message from your account. It is not echoed back
into ATAK.

### D17. Emergency alert

1. In ATAK start an emergency (the alert tool, "911 Alert").
2. Cancel it afterwards.

Expected: a card beginning "EMERGENCY:" appears in the chat within a few seconds, ahead of anything else
waiting. Cancelling posts "Emergency cancelled".

### D18. Marker from one phone on the other phone's map (A2 exit test)

Needs two Android phones with ATAK and TAKgram, as in D10.

Expected: a marker broadcast from ATAK on phone A appears on phone B's ATAK map with the same name, type and
position, and as a readable card in the chat.
