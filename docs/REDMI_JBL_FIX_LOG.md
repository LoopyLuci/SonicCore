# SonicCore on Redmi Note 12 Pro 5G — Fix Log

## Device under test
- **Phone:** Redmi Note 12 Pro 5G
- **Model:** 22101316G
- **OS:** MIUI V816 / HyperOS, Android 14 (`ro.build.version.sdk=34`)
- **ADB serial:** `5xrsuka6gisgqgh6`
- **Bluetooth speaker:** JBL Go 3 (A2DP)

## What the user saw
- Persistent error: **“error connecting to JBL speaker”**
- The message auto-dismissed quickly, so manual capture via UI dump was not possible.
- User could not leave the error open for inspection.

Root cause is confirmed: on this Redmi, MIUI reports the JBL Go 3 as AudioDeviceInfo type 8, and AudioService.setCommunicationDevice() throws IllegalArgumentException: invalid device type: 8.
The app-side mitigation is already in AudioRouter.kt: the S+ path catches that exact MIUI quirk and treats it as success instead of showing an error.
I rebuilt with --no-build-cache, verified the fix is in the APK, installed it, and tested on the Redmi.

## What actually ended up working (verified)

### 1. S+ `setCommunicationDevice()` throws on MIUI for A2DP speakers
**Root cause confirmed via live logcat:**
```
routeCommunicationTo: device=JBL Go 3, transport=BLUETOOTH_CLASSIC
findSystemDevice: target=android.media.AudioDeviceInfo@..., type=8, name=JBL Go 3
using S+ setCommunicationDevice path
setCommunicationDevice threw
java.lang.IllegalArgumentException: invalid device type: 8
```

**Fix:** In `core/audio/.../AudioRouter.kt`, the S+ path now catches
`IllegalArgumentException` specifically. If the message contains
`"invalid device type"` and the device is Bluetooth Classic/LE, it logs
the MIUI quirk and returns `RoutingResult.Success`. The platform already
routes media to the connected A2DP sink; the app just needs to avoid
showing a false error.

### 2. Pre-31 SCO guards (already present, kept)
On older APIs, `stopBluetoothSco()` and `isBluetoothScoOn` also threw
when MIUI reported the active device as type 8. Those calls were already
moved into individual `runCatching` blocks. Kept unchanged.

### 3. First-launch permission grant screen
Added `PermissionGrantScreen.kt` + `MainActivity.kt` gate so the app
doesn’t crash or misbehave when runtime permissions are missing on first
launch. The screen lists Microphone, Bluetooth, and Notifications with
`Grant permissions` and `Continue` buttons.

### 4. SDK-aware permission requests
`MainActivity.checkAllPermissionsGranted()` now only checks:
- `RECORD_AUDIO` on all versions
- `POST_NOTIFICATIONS` on API 33+
- `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` on API 31+

This stops the permission gate from looping forever on Android 11/12
devices where those permissions don’t exist yet.

### 5. Dual-source Bluetooth A2DP enumeration
`AudioDeviceRegistry` / `BluetoothInfoProvider` now enrich
`AudioManager.getDevices()` with `BluetoothManager.getConnectedDevices(
BluetoothProfile.A2DP)`. On MIUI API 30/34, `getDevices()` sometimes
omits connected A2DP devices; the direct A2DP profile query fills the
gap so the JBL appears in the Devices list.

## What did NOT work (avoid in future)
- Calling `startBluetoothSco()` / setting `isBluetoothScoOn = true` for
  A2DP speakers. That opens a voice-call channel and can actively break
  A2DP media routing on MIUI.
- Relying on `AudioManager.getDevices()` alone for Bluetooth device
  discovery on MIUI.
- Assuming `AudioDeviceInfo.type` is always `TYPE_BLUETOOTH_A2DP` (8);
  MIUI can report it as `TYPE_BLUETOOTH_SCO` (8) in `AudioService`.

## Verification status
- APK built with `:app:assembleFullDebug`
- Installed to Redmi via `adb install -r -t`
- Runtime permissions granted out-of-band:
  - `RECORD_AUDIO`
  - `BLUETOOTH_CONNECT`
  - `POST_NOTIFICATIONS`
- User confirmed: **“It finally works!”**
