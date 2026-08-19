# SonicCore

**A unified audio control centre for every output your phone can reach.**

Wired headphones, USB DACs, Bluetooth earbuds, network speakers — one place to control
them all, with per-device profiles that apply automatically on connect.

[![CI](https://github.com/LoopyLuci/SonicCore/actions/workflows/ci.yml/badge.svg)](https://github.com/LoopyLuci/SonicCore/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-606%20passing-brightgreen)](#verification)

---

## Download

| | |
|---|---|
| **GitHub Releases** | [`full` build](../../releases/latest) — includes Chromecast |
| **F-Droid** | `foss` build — no proprietary dependencies, no Chromecast |

The `foss` flavor contains **zero** references to `com.google.android.gms`, verified in CI
by scanning the compiled dex. Chromecast requires a closed-source Google SDK, so it is
absent there; **AirPlay works in both** because it is implemented from scratch over RTSP.

Requires Android 8.0 (API 26) or newer.

## What it does

**Per-device profiles** — Each device gets its own EQ, effects and volume, applied when it
connects. Devices are identified by a stable key that survives reconnects, so your studio
headphones and your car stereo stop fighting over one global EQ.

**An equaliser that shows its work** — Graphic and fully parametric modes over a real
biquad filter chain (RBJ cookbook), with live frequency response and a spectrum analyser.
23 built-in presets, plus AutoEQ file import so measured corrections for your exact
headphone model work immediately.

**Effects and dynamics** — Crossfeed, stereo width, balance, channel swap, phase
inversion, bass boost, virtualisation, reverb, compressor, and a limiter that is on by
default so processing cannot clip your output.

**Per-app mixer** — See which apps hold audio sessions and control what the platform
actually permits.

**Microphone engine** — Source selection, gain, noise gate with separate attack/release,
de-esser, input EQ, live monitoring with a level meter.

**Automation** — Rules driven by device connection, time of day, battery level and headset
actions, with priorities and cooldowns so overlapping rules behave predictably.

**System integration** — Quick Settings tiles, home screen widget, foreground service.

## Honest about platform limits

Android restricts a great deal of audio control, and manufacturers restrict more. Where a
feature is unavailable, SonicCore names the missing permission or API instead of showing a
control that silently does nothing.

A built-in diagnostic log records every platform call the OS or OEM refused, exportable
from **Settings → Diagnostics**, so bug reports can be specific rather than "it doesn't
work on my phone".

## Privacy

No ads, no tracking, no analytics, no crash reporting. Network access is used solely to
discover and stream to speakers on your own network. Nothing leaves your device.

## Performance

Cold start is **27% faster** with the bundled Baseline Profile. Macrobenchmark, 10
iterations per mode, median `timeToInitialDisplay`:

| Compilation mode | Median | Range |
|---|---|---|
| `None` (JIT only) | 712 ms | 693–833 ms |
| `Partial` (Baseline Profile) | **517 ms** | 500–529 ms |
| `Full` (complete AOT) | 524 ms | 492–556 ms |

The Baseline Profile matches full ahead-of-time compilation (within 7 ms) while costing a
fraction of the install-time work — and it makes startup markedly more consistent: a
29 ms spread versus 139 ms without it.

Measured on a Nokia 7.2 (Android 11). Reproduce with:

```bash
./gradlew :benchmark:connectedFullBenchmarkAndroidTest
```

## Verification

| | |
|---|---|
| Unit tests | **606**, 0 failures |
| Instrumented tests | **25**, passing on physical hardware |
| Release build | Instrumented suite also run against the **minified** binary |
| Modules | 18 |

The release APK is verified to launch with zero `NoClassDefFound` / `NoSuchMethod` errors
under R8 — the binary that ships is the one that was tested.

```bash
bash verify.sh    # compiles every module, runs all unit tests, verifies the artifacts
```

## Building

No Android Studio required — JDK 21 and Android SDK 35 are the only prerequisites.

```bash
./gradlew :app:assembleFullDebug    # with Chromecast
./gradlew :app:assembleFossDebug    # no proprietary dependencies
```

For instrumented tests, confirm the device is usable first — a locked or dozing device
makes every Compose test fail with a misleading error:

```bash
bash check-device.sh
./gradlew :app:connectedFullDebugAndroidTest
```

## Architecture

Clean Architecture + MVI, Jetpack Compose, Hilt, Room, Coroutines/Flow. 18 Gradle modules
with convention plugins in `build-logic/`.

```
core/model      pure Kotlin domain types (no Android imports)
core/dsp        biquads, FFT, dynamics — pure Kotlin, unit tested
core/audio      AudioManager / AudioTrack / AudioRecord, routing, device mapping
core/data       Room + DataStore, profile engine
core/streaming  Cast (full flavor only) and AirPlay/RAOP
core/ui         Compose theme and shared components
core/common     diagnostics
feature/*       9 screens (ViewModel + Composable each)
app             navigation, foreground service, tiles, widget, receivers
benchmark       macrobenchmark startup measurements
```

Contributions welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
