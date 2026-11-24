# TAKgram for Android

See [../docs/android-plan.md](../docs/android-plan.md) for the design and [../docs/a0-findings.md](../docs/a0-findings.md)
for what has been verified so far.

## Modules

- `:core`: pure Kotlin, no Android imports, tested on the JVM (`gradle :core:test`).
- `:app`: the Android app. `Tg` is the only class that talks to TDLib; it hands plain `TgMessage` values to
  `core.Bridge`, which is tested against made-up messages. `BridgeService` keeps it running in the background.

Message formats are specified in [../docs/message-formats.md](../docs/message-formats.md).

## Building

Requirements: JDK 17, Android SDK (platform 36), Gradle 9.9.2. The Gradle wrapper jar is not committed yet,
so use a local Gradle 9.9.2 for now.

1. Build TDLib once, on Linux or WSL (no root needed if the tools come from a user-level package manager):

   ```sh
   android/tdlib/build-tdlib.sh          # installs into android/tdlib/out (git-ignored)
   ```

2. Provide the Telegram API credentials **outside the repository**, in any one of:
   - environment variables `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`
   - `android/local.properties`: `telegram.apiId=...` and `telegram.apiHash=...` (git-ignored)
   - `~/.takgram/secrets.properties` with the same keys

3. Build:

   ```sh
   cd android
   gradle :core:test :app:assembleDebug                         # debug build uses Telegram's TEST servers
   gradle :app:assembleDebug -Ptakgram.testDc=false             # debug build against production servers
   ```

Debug builds accept adb intent extras so emulator tests can run unattended. See the comment in
`MainActivity.kt`. Release builds ignore them.
