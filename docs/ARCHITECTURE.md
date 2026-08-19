# SonicCore architecture

A unified audio control centre for wired, USB, Bluetooth and network devices,
built to survive platform change. The central design decision is keeping the
**domain and DSP code free of Android imports** so they outlive any one
platform.

## Module graph

```
┌──────────────────────────────────────────────────────────────────┐
│                                app                               │
│  Single-activity Compose app, Hilt entry point, NavHost, tiles,  │
│  widget, receivers, foreground service. Depends on everything.   │
└┬──────────┬──────────┬──────────┬──────────┬──────────┬─────────┘
           │          │          │          │          │
           ▼          ▼          ▼          ▼          ▼
┌──────────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌──────────┐
│  feature:*   │ │core:ui │ │core:   │ │core:   │ │core:     │
│ 9 screens    │ │Compose │ │streaming│ │common  │ │audio     │
│ (ViewModel +│ │components│ │Cast &  │ │diagnostics│ │device,  │
│  Composable) │ │strings │ │AirPlay │ │         │ │routing,  │
└──────────────┘ └────────┘ └───┬────┘ └────────┘ │session,  │
                                 │                  │volume    │
┌────────────────────────────────┤                  └────┬─────┘
│                                │                       │
│  ┌──────────────┐    ┌─────────┴─────────┐             │
│  │ benchmark    │    │   core:data       │             │
│  │ macrobench   │    │  Room + DataStore │             │
│  │ startup meas │    │  profile engine   │             │
│  └──────────────┘    └─────────┬─────────┘             │
│                                │                       │
│                       ┌────────┴────────┐              │
│                       │   core:model    │◄─────────────┘
│                       │  pure Kotlin    │
│                       │  domain types   │
│                       └────────┬────────┘
│                                │
│                       ┌────────┴────────┐
│                       │   core:dsp      │
│                       │  pure Kotlin    │
│                       │  biquad, FFT,   │
│                       │  dynamics       │
│                       └─────────────────┘
```

## Layer rules

The architecture is enforced by **dependency direction**, not just convention.
Read this as: "modules in a lower layer must never import modules in a higher
layer."

| Layer | Modules | Rule |
|---|---|---|
| **Platform** | `app` | Android framework, Hilt entry point, system integration |
| **Feature** | `feature:*` | One ViewModel + one Composable per screen. Talk to `core:*`, never to each other |
| **Core (logic)** | `core:audio`, `core:data`, `core:streaming`, `core:common` | Android-allowed, but no UI |
| **Core (pure)** | `core:dsp`, `core:model` | **Zero Android imports**. Pure Kotlin/JVM |

The two pure modules are the longevity anchor. If Android is replaced, they
move unchanged.

## What each module owns

### `core/model`
Domain types with no behaviour: `AudioDevice`, `AudioProfile`, `EqBand`,
`AutomationRule`, `AppSettings`, `AppAudioSession`. Kotlin `data class` only —
no Android imports, no Hilt annotations. Serializers use `kotlinx.serialization`,
which is multiplatform.

### `core/dsp`
The audio math, pure Kotlin:
- `Biquad` — RBJ cookbook coefficients, direct-form II transposed
- `EqualizerEngine` — graphic + parametric, maps to platform bands
- `Fft` — radix-2 Cooley-Tukey, in-place, reusable buffer
- `Processors` — crossfeed, compressor/limiter, replay gain

Tested against measured frequency response, not against itself. 22 unit tests
assert gain at specific frequencies matches the biquad equations.

### `core/data`
Persistence and the profile engine:
- `Room` database with `AudioProfileDao`, `DeviceDao`, `AutomationDao`
- `DataStore` for `AppSettings`
- `ProfileEngine` — applies a profile to the active device, reports what succeeded
- `BuiltInPresets` — 23 EQ presets, seeded defensively
- `AutoEqImporter` — reads AutoEQ measured-correction files

### `core/audio`
Everything the Android audio stack exposes, wrapped so the rest of the app
doesn't touch `AudioManager` directly:
- `AudioDeviceMapper` — maps `AudioDeviceInfo` to domain `AudioDevice`
- `BluetoothInfoProvider` — codec, battery (reflection-gated)
- `WifiSpeakerDiscovery` — mDNS/NSD for network speakers
- `AudioDeviceRegistry` — plug-and-play via `BroadcastReceiver`
- `VolumeController`, `AudioRouter`, `PlatformEffectsController`
- `MicrophoneEngine`, `PlaybackEngine` — with audio focus and `ERROR_DEAD_OBJECT` recovery
- `AudioFocusManager` — full decision table (duck/pause/resume)
- `MediaSessionBridge` — takes a `ComponentName`, keeps framework coupling out of the domain

### `core/streaming`
Network speaker protocols behind the `CastStreamer` interface:
- `CastAudioStreamer` — Google Cast SDK (`full` flavor only)
- `AirPlayAudioStreamer` — RAOP/ALAC/RTP, hand-written over RTSP (both flavors)
- `AlacEncoder` — ALAC escape mode, bit-exact
- `StreamingCoordinator` — routes devices to the right streamer

The foss/full split lives here. `CastStreamer` interface binds to a no-op in
the FOSS build, so the F-Droid binary has **zero Play Services references**.

### `core/ui`
Compose theme (Material 3 + dynamic colour) and shared components used by
multiple features:
- `EqCurveView` — Canvas-drawn EQ curve (ImmutableList params, no recomposition churn)
- `SpectrumVisualizer`, `DeviceCard`, `Controls`, `InfoChip`
- `LoadingRow`, `EmptyState`, `ConfirmDestructiveDialog` — the new shared states

### `feature:*`
One ViewModel + one Composable per screen. The ViewModel exposes a single
`StateFlow<UiState>`; the Composable renders it. No business logic lives here —
everything delegates to a repository or engine.

### `app`
The assembly layer:
- `SonicCoreApplication` — Hilt, diagnostic log, defensive preset seeding
- `MainActivity` — single-activity Compose, NavHost
- `SonicCoreApp` — NavHost graph, 5 bottom-bar destinations + sub-screens
- `SonicAudioService` — foreground service, keeps profiles applied
- `Tiles.kt` (3 QS tiles), `SonicWidget.kt` (Glance), `Receivers.kt`, `AutomationEngine.kt`

### `benchmark`
Macrobenchmark for cold-start measurement. Compares `None` / `Partial` /
`Full` compilation modes against the release-like `benchmark` build type.

## The conventions that prevent bugs

These are the lessons from real bugs that shipped. They're encoded in
`build-logic/` and the test suite, not just documented here.

1. **No allocations in the audio path.** `Biquad.updateCoefficients()` ran on
   every EQ band drag and allocated a `List` — replaced with explicit checks.
   Per-frame code runs thousands of times a second; `listOf(...)` causes
   audible glitches.

2. **Strings in resources, never in Kotlin.** 163 inline literals extracted.
   Counts use plurals, never `"$n item(s)"` — unfixable in most languages.

3. **Defensive `runCatching` around platform calls.** OEM audio stacks refuse
   operations the public API advertises. ~150 sites catch and log rather than
   crash. The diagnostic log makes those failures visible.

4. **Compose `Text` in `LazyColumn` is a crash.** A lazy list inside an infinite-
   height scroll container throws `IllegalStateException`. Exactly one scrollable
   owns the vertical axis. The `MoreScreen` bug that shipped and was fixed.

5. **`isLoading` from first emission, not from a timer.** Async data (Room, mDNS)
   needs a loading state that clears when data arrives, not after a fixed delay.
   `_uiState` must be declared before any flow that touches it in `onEach` — a
   property referenced before its initialiser runs is null at construction.

6. **Test seeding is defensive.** `HiltTestApplication` replaces
   `SonicCoreApplication`, so `Application.onCreate` seeding never runs.
   ViewModels seed presets in `init` if the repository is empty.

## Testing strategy

```
core/model, core/dsp         JVM unit tests (no Android needed)
core/data (Room)             Robolectric + in-memory DB
core/ui (Compose)            Robolectric createComposeRule
feature:*                    ViewModel logic + Compose component tests
app (integration)            Hilt + createAndroidComposedRule on device
benchmark                    Macrobenchmark on device
```

The instrumented suite runs against **both** the debug build and the minified
release build — R8 breaks reflection-based code paths that debug tests can't
see. The release keep rules are the ones that were actually tested.

## The foss/full split

| | full | foss |
|---|---|---|
| `com.google.android.gms` refs | 4,206 | **0** |
| Chromecast | ✅ | ❌ (explains itself) |
| AirPlay | ✅ | ✅ (hand-written RTSP) |

Verified from the compiled dex with `aapt2 dump permissions | grep gms` — never
from the config. A `missingDimensionStrategy` fallback would silently pin FOSS
to the Play-Services variant, defeating the split; the dimension is declared
in the library convention plugin instead.

## Performance budget

| Metric | Target | Measured |
|---|---|---|
| Cold start (JIT) | < 800 ms | 712 ms |
| Cold start (profile) | < 550 ms | 517 ms |
| Release APK | < 10 MB | 6.7 MB |
| Test count | > 1000 | 1734 |

Macrobenchmark on a Nokia 7.2 (Android 11). Reproduce:
`./gradlew :benchmark:connectedFullBenchmarkAndroidTest`

## Build

```bash
./gradlew :app:assembleFullDebug     # development, with Chromecast
./gradlew :app:assembleFossDebug     # no proprietary dependencies
bash verify.sh                       # all unit tests + artifact check
bash check-device.sh                 # confirm device is unlocked for UI tests
./gradlew :app:connectedFullDebugAndroidTest
```

`JAVA_HOME` and `ANDROID_HOME` are pinned by `build.sh` if you keep a portable
toolchain — no system-wide install needed.

## License

MIT. No proprietary dependencies in the FOSS build. No ads, tracking, or
analytics. Network access only for LAN speaker discovery and streaming.
