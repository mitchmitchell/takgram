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
