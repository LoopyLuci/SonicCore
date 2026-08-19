# F-Droid submission checklist

Everything here is verified against the actual build, not assumed.

## Inclusion policy

| Requirement | Status |
|---|---|
| FOSS licence, file present | ✅ MIT, `LICENSE` |
| No proprietary dependencies | ✅ `foss` flavor: **0** `com.google.android.gms` refs |
| No non-free network services | ✅ no analytics, ads, crash reporting or telemetry |
| Builds from source, unmodified | ✅ `assembleFossRelease` works with no `keystore.properties` |
| No pre-built binaries in repo | ✅ no `.jar`/`.aar` committed |
| Reproducible source of truth | ✅ everything built by Gradle from source |

### How the Cast exclusion works

The Google Cast SDK (`play-services-cast-framework`) is proprietary, so it is confined to
the `full` flavor:

- `core/streaming/src/main/.../CastStreamer.kt` — interface + `NoOpCastStreamer`
- `core/streaming/src/full/.../cast/CastAudioStreamer.kt` — real SDK implementation
- `core/streaming/src/foss/.../di/CastBindingModule.kt` — binds the no-op
- `core/streaming/src/full/.../di/CastBindingModule.kt` — binds the real one
- `app/src/full/AndroidManifest.xml` — the Cast `OPTIONS_PROVIDER` meta-data

The dependency is declared `"fullImplementation"(...)`, never `implementation(...)`, so
Gradle cannot leak it into the FOSS variant.

**Verify after any dependency change:**

```bash
./gradlew :app:assembleFossRelease
unzip -p app/build/outputs/apk/foss/release/*.apk classes.dex \
  | grep -c 'com/google/android/gms'      # must be 0
```

CI enforces this on every push, so a transitive dependency cannot reintroduce it silently.

## Verified build output

```
app-foss-release-unsigned.apk   4.25 MB
  com.google.android.gms refs : 0
  NoOpCastStreamer present    : True
  signed                      : False   (F-Droid signs it)
  baseline profile embedded   : True
```

## Metadata

Standard fastlane layout, ready to copy into `fdroiddata`:

```
fastlane/metadata/android/en-US/
├── short_description.txt      (66 chars, limit 80)
├── full_description.txt
└── changelogs/1.txt           (matches versionCode 1)
```

The build recipe is `fdroid/com.soniccore.yml` — replace `OWNER` with the GitHub org/user
before submitting.

## Still to do before submitting

- [x] **Screenshots** — 6 real-device PNGs captured and verified via semantics dump:
  `fastlane/metadata/android/en-US/phoneScreenshots/`
  Dashboard, Equalizer, Devices, Profiles, More, Diagnostics.
- [x] **App icon** — 512×512 PNG at `fastlane/metadata/android/en-US/icon.png`, matching the
  `ic_launcher_foreground.xml` spectrum-bar design exactly.
- [x] **Replace `OWNER`** — all three files now point to `LoopyLuci/SonicCore`.
- [x] **Permission audit** — verified from the *built APK* with `aapt2 dump permissions`,
  not the source manifest; 5 genuinely-unused permissions removed, 2 library-injected ones
  documented with their origin.
- [ ] **Tag `v1.0.0`** so `UpdateCheckMode: Tags` can find it.
- [ ] Open a merge request against
  [fdroiddata](https://gitlab.com/fdroid/fdroiddata) with `fdroid/com.soniccore.yml`
  placed at `metadata/com.soniccore.yml`.

## Permission justifications

F-Droid displays permissions in its listing, and reviewers ask about them.

**These were audited against actual API usage and 5 were removed** as genuinely unused:
`ACCESS_WIFI_STATE`, `READ_PHONE_STATE`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, and
the app's own `ACCESS_NETWORK_STATE` declaration. `READ_PHONE_STATE` had **zero** call
sites — call state is detected via `AudioManager.mode` and audio focus, which need no
permission. Time-of-day rules use coroutine `delay()`, not `AlarmManager`.

Verify the real list from the built artifact, not the source manifest — libraries inject
their own:

```bash
aapt2 dump permissions app.apk | grep uses-permission
```

### Declared by SonicCore (15)

| Permission | Why |
|---|---|
| `MODIFY_AUDIO_SETTINGS` | routing and volume — the core function (77 call sites) |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` | device names, codecs, battery (API 31+) |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | same on API ≤ 30, capped with `maxSdkVersion="30"` |
| `BLUETOOTH_PRIVILEGED` | **see note below** |
| `RECORD_AUDIO` | microphone engine and monitoring — never written to disk |
| `FOREGROUND_SERVICE` | keep profiles applied while other apps are in use |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | required type declaration (API 34+) |
| `FOREGROUND_SERVICE_MICROPHONE` | required when monitoring input (API 34+) |
| `POST_NOTIFICATIONS` | the foreground service notification |
| `INTERNET` | AirPlay/RAOP streaming to LAN speakers only |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS/NSD discovery of network speakers |
| `RECEIVE_BOOT_COMPLETED` | restore automation rules after reboot |
| `VIBRATE` | haptic feedback (user-toggleable) |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | MediaSession metadata for the per-app mixer (service-level, user-granted) |

No `ACCESS_FINE_LOCATION` — Bluetooth scanning uses `neverForLocation`, confirmed present
in the built APK.

### Added by libraries, not by SonicCore (2)

Both appear in the FOSS build too and cannot be removed without dropping the home screen
widget:

| Permission | Injected by |
|---|---|
| `ACCESS_NETWORK_STATE` | `androidx.work:work-runtime` (transitive via `androidx.glance:glance-appwidget`) |
| `WAKE_LOCK` | `androidx.work:work-runtime` |

Glance uses WorkManager to update widgets. Say so in the merge request — a reviewer seeing
`WAKE_LOCK` on an audio app will otherwise assume the app holds wake locks itself, which it
does not.

### `BLUETOOTH_PRIVILEGED`

Signature-level, so a normal app can never hold it: on stock Android it is simply never
granted. It is declared because it gates `setCodecConfigPreference` (the one real call
site) on ROMs that do grant it, and on rooted/system installs. The app degrades gracefully
and reports the refusal to the diagnostic log when it is absent. Justify this in the MR
description, or drop it if a reviewer objects — nothing else depends on it.

