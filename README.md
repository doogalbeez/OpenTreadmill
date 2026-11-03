# Treadmill

An open replacement for the iFit app on a NordicTrack treadmill console. It runs on the existing
Android console and talks straight to the motor board over USB. No account, no subscription,
no internet.

Built for the **NordicTrack Commercial 1750 (NTL14221.0)**, which uses the FitPro motor board.
Other Gen 6 NordicTrack/ProForm consoles with a FitPro board will probably work too.

## What it does

- Start, pause, stop, with a STOP button that is always available
- Speed and incline: step buttons plus one-tap presets
- Live time, distance, pace and calories (from the ACSM equations and your weight)
- Built-in workouts (walks, hills, intervals, steady jog) and a custom interval builder
- Workout history saved on the console, with CSV export
- Miles or kilometres
- A built-in simulator, so the app runs on any Android device without a treadmill

## Safety

- **The physical safety key is the real stop.** Keep it clipped on. Pulling it cuts the motor no matter what the app does.
- The app clamps every command to the limits the motor board reports, never starts the belt faster
  than 2 mph, and ramps speed changes gently.
- STOP and Pause send the board's own Pause (the only command a running FitPro board obeys to stop),
  retry until the board confirms, and keep re-sending stop while the belt is moving when it shouldn't be.
- Pulling the safety key is latched: speed is refused until the key is back and you press Start.
- Settings → Board details shows the board's mode and a live command log for troubleshooting.
- It never sends the firmware-update or bootloader commands, which can reset or brick the board.
- This is hobby software with no warranty. Test it first with nobody on the belt.

## Layout

| Path | What's in it |
| --- | --- |
| `core/` | Plain Kotlin, no Android: FitPro frame codec, the `Controller` that drives the board, a `SimulatedBoard` that copies the real board's quirks, workout engine, stats. Unit-tested against the simulator. |
| `app/` | The Android app: USB HID transport, view model, Compose UI. |
| `docs/INSTALL.md` | How to get it onto the console. |

## Building

GitHub Actions builds the APK on every push. Download it from the run's **treadmill-apk** artifact.

Locally:

```sh
./gradlew :core:test                # core tests only, no Android SDK needed: add -PcoreOnly
./gradlew :app:assembleDebug        # needs the Android SDK
```

## Credits

The FitPro protocol notes come from [strideApp](https://github.com/foolishsailor/strideApp)
(Apache-2.0). Not affiliated with iFIT or NordicTrack.
