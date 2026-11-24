# Third-party licences

TAKgram is Apache-2.0. Every component that ships in the Android app, or is used to build it, is listed here
with its licence. Anything copyleft (GPL, LGPL, AGPL) must not be added without a decision recorded here.

No code from APRSdroid (GPL-2.0), TAKwave (GPL-3.0) or Meshtastic (GPL-3.0) is used. Their behaviour may be
studied; their code is not copied or adapted.

## Shipped in the APK

| Component | Version | Licence | Notes |
| --- | --- | --- | --- |
| TDLib (`libtdjni.so`, generated `org.drinkless.tdlib` Java) | 1.8.71, commit `f10611ee1f03f91ddae8185483a1ccfd3d664668` | Boost Software License 1.0 (`LICENSE_1_0.txt` in the TDLib repo) | Permissive; requires the licence text to be kept with source distributions. Built from source by `android/tdlib/build-tdlib.sh`. |
| OpenSSL (statically linked into `libtdjni.so`) | 3.5.9 (LTS) | Apache-2.0 | 3.x is Apache-2.0; 1.1.1 (TDLib's default) was avoided because it is end-of-life and uses the older dual licence. |
| `androidx.annotation:annotation` | 1.11.0 | Apache-2.0 | Needed by the generated TdApi.java. |
| ZXing core (`com.google.zxing:core`) | 3.5.4 | Apache-2.0 | Draws the QR code for QR sign-in. |
| Kotlin standard library | 2.4.21 | Apache-2.0 | |
| LLVM libc++ (static, `c++_static`) | NDK r28 | Apache-2.0 with LLVM exception | |

## Build and test only (not shipped)

| Component | Version | Licence |
| --- | --- | --- |
| Android Gradle Plugin | 9.5.2 | Apache-2.0 |
| Gradle | 9.9.2 | Apache-2.0 |
| Kotlin Gradle plugin | 2.4.21 | Apache-2.0 |
| JUnit | 4.13.2 | EPL-1.0 (test scope only) |
| Android NDK r28.2 / SDK | 28.2.13676358 | Android SDK licence (tools only) |

`libtdjni.so` links dynamically only to Android system libraries (`liblog`, `libdl`, `libz`, `libm`, `libc`;
checked with `llvm-readelf -d`). Those come with the phone and are not shipped in the APK.

## Not used

- Google Play Services location APIs (proprietary): the app uses the framework `LocationManager` instead.
- ATAK-CIV is a separate app that TAKgram talks to over localhost sockets. TAKgram contains no ATAK code and
  links no ATAK SDK.

## Licence obligations to carry into the app

- Show the TDLib (BSL-1.0), OpenSSL (Apache-2.0) and Apache-2.0 notices in an "Open-source licences" screen
  before any build is shared (tracked for A1).
