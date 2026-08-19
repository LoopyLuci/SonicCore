# SonicCore FAQ

Frequently asked questions, organised by who tends to ask them.

---

## General (everyone)

### What is SonicCore?

A unified audio control centre for every output your phone can reach — wired headphones, USB DACs, Bluetooth earbuds, and network speakers. Instead of hunting through system settings that expose only a fraction of what the Android audio stack can actually do, SonicCore puts it in one place.

### Who is it for?

Anyone who:
- Switches between multiple audio devices and wants each to sound right automatically
- Wants a real parametric equaliser, not a decorative curve
- Cares about privacy (no ads, no tracking, no analytics)
- Uses a degoogled ROM and wants powerful audio control without Google Play Services

### Is it free?

Free as in freedom (MIT licence) and free as in beer. No ads, no tracking, no in-app purchases, no subscriptions.

### Where can I get it?

- **GitHub Releases**: https://github.com/LoopyLuci/SonicCore/releases — the `full` APK (with Chromecast)
- **F-Droid** (pending): the `foss` APK (no Google Play Services)
- **Build from source**: see [CONTRIBUTING.md](CONTRIBUTING.md)

### What Android version do I need?

Android 8.0 (API 26) or newer.

### Why is it large/small?

- Full build: 6.7 MB R8-minified
- FOSS build: 4.2 MB
- Debug build: 24 MB (not for distribution)

---

## Features

### What audio devices does it support?

- **3.5mm wired** headphones and headsets
- **USB** DACs and audio interfaces
- **Bluetooth** earbuds, headphones, speakers (SBC, AAC, LDAC, aptX, aptX HD, LC3)
- **WiFi** speakers via AirPlay and Chromecast

### What are per-device profiles?

Every device gets its own EQ, effects, and volume settings. When you connect your studio headphones, SonicCore recognises them and applies your preferred EQ automatically. When you switch to your car stereo, that profile loads instead. Devices are identified by a stable key that survives reconnects.

### What's the difference between graphic and parametric EQ?

- **Graphic**: fixed frequency bands (10-band or 31-band). Simple, familiar.
- **Parametric**: fully adjustable frequency, gain, and Q per band. More precise. You can place a filter at exactly 1,873 Hz with a Q of 4.2 and a cut of 3.5 dB. You cannot do that with a graphic EQ.

### What effects are available?

- Crossfeed (for headphone listening — blends channels to reduce fatigue)
- Stereo width and balance
- Channel swap and phase inversion
- Bass boost
- Virtualisation (headphone spatialisation)
- Reverb
- Compressor and limiter (limiter is on by default — processing cannot clip your output)
- Noise gate and de-esser (microphone)
- Replay gain

### What does the microphone engine do?

Input source selection, gain, noise gate with separate attack and release, de-esser, input EQ, and live monitoring with a level meter. Android has no hardware mic gain API, so gain is applied in software.

### What are automation rules?

Rules that react to events: device connection, time of day, battery level, headset action. Priorities and cooldowns prevent overlapping rules from fighting. Example: "When I connect my Bluetooth headphones, switch to the 'Commute' profile."

### What are Quick Settings tiles and the widget?

- **Tiles**: three tiles in the notification shade — toggle the equalizer, switch profiles, switch output device
- **Widget**: a home screen widget showing the current profile and letting you switch with one tap

### Does it work with the lockscreen?

Yes. The foreground service keeps profiles applied. The notification shows the active profile and lets you switch.

---

## Privacy

### Does it track me?

No. No analytics, no crash reporting, no advertising identifiers, no telemetry. Nothing leaves your device.

### Does it have network access?

Yes, but only for LAN speaker discovery (mDNS/NSD) and AirPlay streaming to speakers on your own network. No cloud, no remote servers, no analytics endpoints.

### Why does the FOSS build need `INTERNET` permission?

For discovering and streaming to network speakers on your local network. Not for any cloud service.

### What does the diagnostic log record?

Which platform calls were refused (codec selection, battery reporting, routing). Enough to diagnose "feature X doesn't work on my phone" without identifying you. No PII.

### What does the failure report contain?

Only: Android version bucket, failure counts per category, and one anonymised recent message per category. No device model, no user data, no identifiers beyond "Android 15". Enough to spot a trend, not enough to track anyone.

### Is the diagnostic log shared automatically?

No. You choose to export it. You choose to share it. Nothing leaves the device without your action.

---

## Technical

### Why is the EQ a biquad filter?

The RBJ cookbook biquad equations are the standard for audio equalisers. They're well-understood, stable, and predictable. Every frequency response claim can be verified against the math. The unit tests do exactly this.

### How is the DSP tested?

Three layers:
1. **Unit tests**: each biquad filter's magnitude response is measured at specific frequencies and verified against the equations
2. **Reference audio pipeline**: a sine wave goes through the full EQ chain and the output amplitude matches what the biquad math predicts
3. **Integration tests on device**: verifies the app can talk to the Android audio stack on a real or emulated device

### Why are some modules pure Kotlin?

`core/model` and `core/dsp` have zero Android imports. If Android is eventually replaced by something else, that code survives unchanged. The boundary is enforced by the module dependency graph — feature modules can't import Android into the core.

### How does the foss/full split work?

The `CastStreamer` interface in `core/streaming` is bound to:
- `CastAudioStreamer` (real Cast SDK) in the `full` build
- `NoOpCastStreamer` (does nothing, explains why) in the `foss` build

The Cast SDK dependency is declared `fullImplementation(...)`, never `implementation(...)`, so Gradle cannot leak it into the FOSS variant. Verified from the compiled dex: 0 references to `com.google.android.gms`.

### Why are there two build flavors instead of two repos?

A single codebase, a single tag, a single review. The `foss` flavor binds a no-op Cast implementation and carries no Play Services. A fork would diverge and require double the maintenance.

### How is the AirPlay implementation different from Chromecast?

Chromecast uses Google's closed-source Cast SDK (proprietary). AirPlay is implemented entirely from scratch over RTSP/RTP — a public protocol. That's why AirPlay works in both builds and Chromecast doesn't.

### What's the Baseline Profile?

A compiled version of the startup path, bundled in the APK, that Android applies on first run. Cuts cold start from ~712 ms to ~517 ms (27% faster) and removes first-run jank. Measured with Macrobenchmark on a Nokia 7.2 (Android 11).

### What's the test count?

1,734 unit tests + 28 instrumented tests on hardware + 3 localisation tests. 0 failures.

---

## Building

### How do I build it?

```bash
./gradlew :app:assembleFullDebug     # development, with Chromecast
./gradlew :app:assembleFossDebug     # no proprietary dependencies
bash verify.sh                       # all unit tests + artifact check
```

### What do I need?

JDK 21 and Android SDK 35. No Android Studio required. `build.sh` pins `JAVA_HOME` and `ANDROID_HOME` for a portable toolchain.

### How do I run instrumented tests?

```bash
bash check-device.sh                 # confirm device is unlocked
./gradlew :app:connectedFullDebugAndroidTest
```

A locked or dozing device makes every Compose UI test fail with "No compose hierarchies found" — `check-device.sh` catches this in one second.

### Where does the signing key go?

In `keystore/soniccore-release.jks` with credentials in `keystore.properties` — both are gitignored. The build degrades gracefully: without `keystore.properties`, it produces an unsigned APK (what F-Droid needs).

### Can I debug the DSP?

Yes. `core/dsp` is pure Kotlin — run the unit tests in any JVM. No emulator needed.

---

## Contributing

### How do I contribute?

1. Pick up a "good first issue"
2. Run `bash verify.sh` (compiles everything, runs all unit tests)
3. Open a PR

### What should I know before submitting?

- Keep `foss` free of proprietary dependencies. Anything requiring Google Play Services goes in `src/full/`, behind an interface with a no-op FOSS binding
- Add tests. The DSP, data and model layers are pure JVM code with no Android dependencies
- Don't allocate in the audio path. Per-frame code runs thousands of times a second
- Don't put strings in Kotlin — use `stringResource()`
- Don't nest a `LazyColumn` in a scrolling `Column` (crash)
- Run `bash verify.sh` before opening the PR

### How do I add a new audio effect?

1. Add the processor to `core/dsp` (pure Kotlin, unit test it)
2. Wire it into `EffectsViewModel` and `EffectsScreen`
3. Add a test in `EffectsSettingsTest`

### How do I add a new language?

Add a new `values-XX/` directory under `core/ui/src/main/res/` with translated `strings_common.xml` and `strings_formats.xml`. See `values-es/` for a partial example.

### How do I add a new feature screen?

1. Create a new `feature/<name>` module
2. Add the ViewModel (single `StateFlow<UiState>`) and Composable
3. Register the route in `SonicCoreApp.kt`
4. Add a bottom-bar entry or a link from `MoreScreen`

---

## F-Droid

### Why two APKs?

- **Full**: includes Chromecast (needs Google's proprietary Cast SDK)
- **FOSS**: no proprietary dependencies, F-Droid compatible. Chromecast is absent; AirPlay works.

### Why is the FOSS build smaller?

It excludes the Cast SDK (~2.5 MB of proprietary code).

### Why is `BLUETOOTH_PRIVILEGED` requested?

It's signature-level, so a normal app can never hold it. It's declared so the codec-selection path works on ROMs that do grant it (rooted/system installs). The app degrades gracefully and reports the refusal to the diagnostic log when absent.

### Why `ACCESS_NETWORK_STATE` in the FOSS build?

Injected by `androidx.work` via Glance for the home screen widget update. Not requested by SonicCore itself.

### Why `WAKE_LOCK`?

Same as above — Glance uses WorkManager, which declares `WAKE_LOCK`. The app never holds a wake lock itself.

### Why is `READ_PHONE_STATE` absent?

It was removed. Zero call sites. Call state is detected via `AudioManager.mode` and audio focus, which need no permission.

### Why is `SCHEDULE_EXACT_ALARM` absent?

Time-of-day automation rules use coroutine `delay()`, not `AlarmManager`.

### Why does F-Droid sign the APK instead of you?

Standard F-Droid practice. The `AllowedAPKSigningKeys: ~` in the recipe means "F-Droid signs it." Your GitHub releases are still signed with your key (`CN=SonicCore`).

### Why `scandelete: keystore.properties`?

Defensive. The file is gitignored, but if it ever leaked into the tree (e.g., from a contributor's mistake), F-Droid's scanner should exclude it.

### How are new releases published to F-Droid?

1. Tag a new version on GitHub (e.g., `v1.0.1`)
2. Update `fdroid/com.soniccore.yml` with the new `versionName`, `versionCode`, and `commit`
3. Open a new MR against fdroiddata (or update the existing one)

`AutoUpdateMode: Version` + `UpdateCheckMode: Tags ^v[0-9.]+$` means F-Droid's scanner will notice the new tag, but a human still needs to verify and merge.

---

## Troubleshooting

### The equaliser doesn't seem to do anything

- Check the global EQ toggle (top right of the Equalizer screen) — it may be off
- Check you're not on the "Quick" mode with a "Flat" preset
- Check the per-app mixer isn't overriding your volume

### My device isn't detected

- Check Bluetooth permissions are granted (Settings → SonicCore → Permissions)
- Check `MODIFY_AUDIO_SETTINGS` is granted
- Check the diagnostic log (Settings → Diagnostics) for refused platform calls

### Audio crackling or glitching

- Check the diagnostic log for underrun indicators
- Try increasing the buffer size in Settings → Audio
- Check no other app is hogging the audio stream

### The app crashes on open

- Check the diagnostic log export (if accessible) and file a GitHub issue
- Check the fastlane screenshots for your device type — some OEM ROMs break standard APIs

### Chromecast doesn't work in the FOSS build

It can't — the Cast SDK is proprietary and excluded from the FOSS build. Use the GitHub release ("full" APK) for Chromecast.

### My language isn't supported

Add a translation. Copy `core/ui/src/main/res/values/strings_common.xml` to `values-XX/strings_common.xml` and translate. Open a PR.

---

## Licensing

### What's the licence?

MIT. Do almost anything, just keep the licence notice.

### Can I distribute my own build?

Yes. MIT permits distribution. F-Droid does this. You can too.

### Can I use the DSP code in my own project?

Yes. `core/dsp` is pure Kotlin, MIT licensed. No Android imports. Copy the files, keep the licence.

### Why MIT and not GPL?

MIT is permissive — it allows proprietary forks, commercial use, and integration into closed-source projects. The goal is for the code to be as widely usable as possible.

---

## Contact

- **Issues**: https://github.com/LoopyLuci/SonicCore/issues
- **Source**: https://github.com/LoopyLuci/SonicCore
- **F-Droid** (pending): will be listed at f-droid.org once merged
