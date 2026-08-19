## Reporting a bug

SonicCore leans on `runCatching` around platform calls because OEM audio stacks
routinely refuse operations the public API advertises. Failures are recorded rather than
discarded, so please include the diagnostic log — it turns "codec switching doesn't work"
into something actionable.

**Settings → Diagnostics → Export log**, then attach the text. It contains your Android
version, device model, and which platform calls were refused. No personal data, no
network requests, no identifiers beyond the device model.

Please also state:

- Device and Android version (e.g. Nokia 7.2, Android 11)
- Which build: `full` (GitHub) or `foss` (F-Droid)
- The audio device involved, and how it is connected (3.5mm / USB / Bluetooth / WiFi)
- What you expected versus what happened

## Building

No Android Studio required. The build is self-contained:

```bash
./gradlew :app:assembleFullDebug     # with Chromecast support
./gradlew :app:assembleFossDebug     # no proprietary dependencies
bash verify.sh                       # compile + all unit tests + verify artifacts
```

JDK 21 and Android SDK 35 are the only prerequisites. `build.sh` pins `JAVA_HOME` and
`ANDROID_HOME` if you keep a portable toolchain outside the system paths.

Before running instrumented tests, check the device is usable:

```bash
bash check-device.sh          # screen must be ON and UNLOCKED
./gradlew :app:connectedFullDebugAndroidTest
```

A locked or dozing device makes every Compose UI test fail with "No compose hierarchies
found in the app", which looks like an app bug but is not.

## Pull requests

- Keep the `foss` flavor free of proprietary dependencies. Anything requiring Google
  Play Services goes in `src/full/`, behind an interface with a no-op FOSS binding.
- Add tests. The DSP, data and model layers are pure JVM code with no Android
  dependencies, so they test without an emulator.
- Don't allocate in the audio path. Per-frame code runs thousands of times a second;
  `listOf(...)`, `.map { }` and `.filter { }` inside a render loop cause audible glitches.
- Be honest about platform limits in the UI. Where Android forbids something, say which
  permission or API is missing instead of showing a control that silently does nothing.
- Run `bash verify.sh` before opening the PR.

## Architecture

Clean Architecture with MVI, 18 Gradle modules, Hilt for DI:

```
core/model      pure Kotlin domain types, no Android imports
core/dsp        biquad filters, FFT, dynamics — pure Kotlin, unit tested
core/audio      AudioManager/AudioTrack/AudioRecord, device mapping, routing
core/data       Room + DataStore persistence, profile engine
core/streaming  Cast (full flavor only) and AirPlay/RAOP
core/ui         Compose theme and shared components
core/common     diagnostics
feature/*       9 screens, one ViewModel + one Composable each
app             navigation, service, tiles, widget, receivers
benchmark       macrobenchmark startup measurements
```

Convention plugins in `build-logic/` keep the 18 module build files short — add shared
dependencies there rather than repeating them per module.
