# Installing on the console

A factory reset puts the stock iFit software back, so nothing here is permanent. OpenTreadmill has
one built in: **Settings → Factory reset**.

## 1. Get into privileged mode

1. On the main or login screen, tap an empty area (not a button) quickly **10 times**.
2. Wait **7 seconds** without touching the screen.
3. Tap **10 more times**.
4. If it shows a number and asks for a response code, send the number to Claude in the project
   thread and you'll get the code back.

## 2. Turn on ADB

1. In privileged mode, open **Settings → About tablet** and tap **Build number** 7 times.
2. In **Developer options**, turn on **USB debugging** and **ADB over network**.
3. From a computer on the same Wi-Fi: `adb connect <console-ip>:5555`. The console asks
   **Allow USB debugging?**: tick **Always allow from this computer** and tap **OK**.

The console has no web browser, so ADB is the way to install apps.

## 3. Install the app

`opentreadmill.apk` is on the repo's **Releases** page (sign in to GitHub to download it while the
repo is private). Then:

```sh
adb install opentreadmill.apk
```

## 4. Set up the console (one time, over ADB)

```sh
# Free the motor board: only one app can talk to it, so the iFit apps are disabled.
# (Disabling eru also stops iFit firmware updates, which can block privileged mode.)
adb shell pm disable-user --user 0 com.ifit.eru
adb shell pm disable-user --user 0 com.ifit.arda
adb shell pm disable-user --user 0 com.ifit.launcher

# OpenTreadmill as the home screen
adb shell cmd package set-home-activity app.opentreadmill/.MainActivity

# iFit leaves Android thinking first-time setup never finished, which makes it ignore Home
adb shell settings put secure user_setup_complete 1

# Music controls: lets the app see Spotify's song and play state (the app has no internet access)
adb shell cmd notification allow_listener app.opentreadmill/app.opentreadmill.MediaListener

# Floating Home / Back / Apps buttons over other apps (and STOP while the belt moves)
adb shell settings put secure enabled_accessibility_services app.opentreadmill/app.opentreadmill.NavService
adb shell settings put secure accessibility_enabled 1

# Your time zone (the app's Settings → Clock can also set its own)
adb shell settings put global auto_time_zone 0
adb shell service call alarm 3 s16 America/Anchorage
```

Undo any iFit change with `adb shell pm enable <package>`. When the app first asks for USB
permission for the motor board, tick **Use by default** and allow it.

## 5. Music (optional)

The console has no Play Store. [Aurora Store](https://f-droid.org/packages/com.aurora.store/)
(from F-Droid, open source) installs apps from Google Play without Google services:

```sh
adb install com.aurora.store_<version>.apk
adb shell appops set com.aurora.store REQUEST_INSTALL_PACKAGES allow
```

Open Aurora from **Apps** on the home screen, sign in anonymously, and install **Spotify**. Sign in to
Spotify once; from then on your phone can pick the treadmill as a Spotify Connect speaker. The
console can't receive Bluetooth audio from a phone.

## 6. First test, with nobody on the belt

1. Clip the safety key in. Check the app shows **Connected**, not **Simulator**.
2. Try incline first: press ▲ under Incline and watch the deck move. Then go back to 0.
3. Press **Manual**, then **Start**. The belt should run slowly. Try ▲ under Speed a few times.
4. Press **STOP**. Then try pulling the safety key while the belt runs: it must stop and the app
   should show SAFETY KEY OUT.
5. Only then step on.

If something looks wrong, pull the safety key. Send Claude a photo of the screen with any
error message.

## 7. Close the door

ADB over the network lets any computer on your Wi-Fi ask for control of the console (it still has
to be allowed on screen). When you're done installing, turn off **ADB over network** and
**USB debugging** in Developer options, and turn them back on for the next update.

## Updating

Newer builds install over the old one, so profiles and history are kept, as long as they are
signed with the same key ([SIGNING.md](SIGNING.md)). Moving from the old `app.treadmill` app (v0.2
and earlier) to OpenTreadmill is a new app: install it, then uninstall the old one with
`adb uninstall app.treadmill`, and redo step 4.

The newest build is always at `github.com/doogalbeez/OpenTreadmill/releases/latest/download/opentreadmill.apk`
(the repo must be public, or you must be signed in, for that link to work).
