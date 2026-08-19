# SonicCore v1.0.0

A unified audio control centre for every output your phone can reach — wired headphones,
USB DACs, Bluetooth earbuds and network speakers — with per-device profiles that apply
automatically on connect.

## Which download?

| File | For | Chromecast |
|---|---|---|
| `soniccore-1.0.0-full.apk` | Most users | ✅ Yes |
| `soniccore-1.0.0-foss.apk` | No Google Play Services / degoogled ROMs | ❌ No |
| `soniccore-1.0.0.aab` | Play Store submission | ✅ Yes |

The `foss` build contains **zero** references to `com.google.android.gms` (verified by
scanning the compiled dex). Chromecast needs a closed-source Google SDK, so it is absent
there. AirPlay works in both builds — it is implemented from scratch over RTSP.

F-Droid users: install the `foss` build from the F-Droid repository instead.

## Highlights

**Per-device profiles.** Every device gets its own EQ, effects and volume, applied on
connect. Devices are keyed so a profile stays attached across reconnects.

**A real equaliser.** Graphic and fully parametric modes over an RBJ-cookbook biquad
chain, with live frequency response and spectrum analysis. 23 built-in presets, plus
AutoEQ file import for measured headphone corrections.

**Effects.** Crossfeed, stereo width, balance, phase inversion, bass boost,
virtualisation, reverb, compressor, and a limiter enabled by default so processing cannot
clip your output.

**Per-app mixer, microphone engine, automation rules, Quick Settings tiles, home screen
widget.**

**Honest about platform limits.** Where Android or your manufacturer forbids something,
SonicCore names the missing permission or API instead of showing a control that silently
does nothing. A built-in diagnostic log records what the platform refused, so bug reports
can be specific.

## Performance

Cold start is **27% faster** thanks to a bundled Baseline Profile. Macrobenchmark,
10 iterations per compilation mode, median `timeToInitialDisplay`:

| Compilation mode | Median | Range |
|---|---|---|
| `None` (JIT only) | 712 ms | 693–833 ms |
| `Partial` (Baseline Profile) | **517 ms** | 500–529 ms |
| `Full` (complete AOT) | 524 ms | 492–556 ms |

The profile matches full ahead-of-time compilation within 7 ms, and cuts startup variance
from a 139 ms spread to 29 ms.

Measured on a Nokia 7.2 (Android 11).

## Verified

- **606 unit tests**, 0 failures
- **25 instrumented tests** passing on physical hardware, against both the debug and the
  minified release build
- Release APK confirmed running with zero `NoClassDefFound` / `NoSuchMethod` errors under
  R8 — the shipped binary is the one that was tested

## Requirements

Android 8.0 (API 26) or newer. No ads, no tracking, no analytics. Network access is used
only to discover and stream to speakers on your own network.

## Verify your download

```
sha256sum soniccore-1.0.0-full.apk
```

Checksums are listed below. APKs are signed with the SonicCore release key
(SHA384withRSA, 4096-bit); `apksigner verify --print-certs` will show
`CN=SonicCore`.
