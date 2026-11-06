# OpenTreadmill

An open replacement for the iFit app on a NordicTrack treadmill console. It runs on the existing
Android console and talks straight to the motor board over USB. No account, no subscription,
and the app itself has no internet access at all.

Built for the **NordicTrack Commercial 1750 (NTL14221.0)**, which uses the FitPro motor board.
Other Gen 6 NordicTrack/ProForm consoles with a FitPro board will probably work too.

## What it does

- **Console screen:** stats across the top (time, distance, calories, pace, climb), incline on the
  left, speed on the right, Start/Pause and a STOP that is always on screen
- **18 built-in workouts** (walk, hills, intervals, run) with speed and incline charts, and a
  length control that stretches or shrinks the main part
- **Workout builder:** your own workouts, step by step, saved on the console
- **During a workout:** ±1 minute on the current step; in Manual, a time goal and a 400 m track
- **Profiles:** each person has their own weight, units, colour theme, history and saved workouts
- **Themes:** six colour schemes; STOP and safety warnings stay red in all of them
- **Music:** play/pause/skip and volume for Spotify (or any music app) without leaving the workout
- **Navigation:** a pop-out tab on the right edge with Home / Back / Apps over other apps, and an Apps list on the home screen
- **Clock:** time zone and 12/24-hour setting
- History with totals and CSV export; miles or kilometres
- **Factory reset** in Settings (typed confirmation, then "Are you sure?") back to the stock iFit software
- A built-in simulator, so the app runs on any Android device without a treadmill

## Safety

- **The physical safety key is the real stop.** Keep it clipped on. Pulling it cuts the motor no matter what the app does.
- The app clamps every command to the limits the motor board reports, never starts the belt faster
  than 2 mph, and ramps speed changes gently.
- STOP and Pause send the board's own Pause on its own (the board refuses the whole message if it
  also carries speed 0), retry until the board confirms, and keep re-sending while the belt moves.
- Start works from every state the board can be in, including Results after a long pause. If the
  board still won't start, the app says so instead of acting as if the belt were running.
- Pulling the safety key is latched: speed is refused until the key is back and you press Start.
- While the belt can move, the app stays on its console screen. Opening other apps is blocked, and
  the floating buttons over other apps add a red STOP whenever the belt is moving.
- Settings → Board details shows the board's mode and a live command log for troubleshooting
  (also in logcat: `adb logcat -s OpenTreadmill`).
- It never sends the firmware-update or bootloader commands, which can reset or brick the board.
- This is hobby software with no warranty. Test it first with nobody on the belt.

## Security

- The app has **no internet permission**: profiles, history and what it sees of other apps can't leave the console.
- The signing key is kept out of the repo ([docs/SIGNING.md](docs/SIGNING.md)).
- ADB over the network is the one way into the console over Wi-Fi: turn it off when you're not
  installing builds (see [docs/INSTALL.md](docs/INSTALL.md)).

## Layout

| Path | What's in it |
| --- | --- |
| `core/` | Plain Kotlin, no Android: FitPro frame codec, the `Controller` that drives the board, a `SimulatedBoard` that copies the real board's quirks, workout engine, stats. Unit-tested against the simulator. |
| `app/` | The Android app: USB HID transport, view model, Compose UI, profiles, music and navigation services. |
| `docs/INSTALL.md` | How to get it onto the console. |
| `docs/SIGNING.md` | Where the signing key lives and how CI gets it. |

## Building

GitHub Actions builds the APK on every push. Download it from the run's **opentreadmill-apk** artifact.
Releases are only published when the signing key is set up in the repo's secrets.

Locally:

```sh
./gradlew :core:test                # core tests only, no Android SDK needed: add -PcoreOnly
./gradlew :app:assembleDebug        # needs the Android SDK, and the signing key to update the console's copy
```

## Credits

The FitPro protocol notes come from [strideApp](https://github.com/foolishsailor/strideApp)
(Apache-2.0). Not affiliated with iFIT or NordicTrack.
