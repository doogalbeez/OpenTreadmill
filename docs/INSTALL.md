# Installing on the console

A factory reset puts the stock iFit software back, so nothing here is permanent.

## 1. Get into privileged mode

1. On the main or login screen, tap an empty area (not a button) quickly **10 times**.
2. Wait **7 seconds** without touching the screen.
3. Tap **10 more times**.
4. If it shows a number and asks for a response code, send the number to Claude in the project
   thread and you'll get the code back.

## 2. Install the app

**Option A: from the console's browser (easiest)**
1. In privileged mode, open Android **Settings → Security** and turn on **Unknown sources**.
2. Get `treadmill.apk` from the repo's **Releases** page. The repo is private, so either sign in to
   GitHub in the console's browser, or download it on your computer or phone and copy it onto a
   USB stick, then open it with the console's file manager.
3. Open the APK and tap **Install**.

**Option B: over ADB**
1. In privileged mode, open **Settings → About tablet** and tap **Build number** 7 times.
2. In **Developer options**, turn on **USB debugging** (and ADB over network if offered).
3. From a computer on the same Wi-Fi: `adb connect <console-ip>:5555`, then `adb install app-debug.apk`.

## 3. Free the motor board from iFit

Only one app can talk to the motor board at a time, so the iFit apps have to be stopped.

- Over ADB: `adb shell pm list packages | grep -i ifit`, then for each one
  `adb shell pm disable-user --user 0 <package>`.
- Without ADB: **Settings → Apps**, open each iFit app, then **Force stop** and **Disable**.

Undo either with `adb shell pm enable <package>` or the **Enable** button.

## 4. Make it the home screen

Press the home button and pick **Treadmill**, then **Always**. When the app asks for USB
permission for the motor board, tick **Use by default** and allow it.

## 5. First test, with nobody on the belt

1. Clip the safety key in. Check the app shows **Connected**, not **Simulator**.
2. Try incline first: press ▲ under Incline and watch the deck move. Then go back to 0.
3. Press **Start**. The belt should run at about 1 mph. Try ▲ under Speed a few times.
4. Press **STOP**. Then try pulling the safety key while the belt runs: it must stop and the app
   should show SAFETY KEY OUT.
5. Only then step on.

If something looks wrong, pull the safety key. Send Claude a photo of the screen with any
error message.
