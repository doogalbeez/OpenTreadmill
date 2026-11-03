# Signing key

Android only installs an update over the app on the console if it is signed with the same key.
That key is **not in this repo**. Anyone holding it could make an "update" that replaces the app.

## Where it lives

- On the build PC: a keystore file outside the repo (for example `~/.opentreadmill/opentreadmill.keystore`),
  with its settings in `~/.gradle/gradle.properties` (your user folder, not the project):

  ```properties
  OPENTREADMILL_KEYSTORE=C:/Users/<you>/.opentreadmill/opentreadmill.keystore
  OPENTREADMILL_KEYSTORE_PASSWORD=<password>
  OPENTREADMILL_KEY_ALIAS=opentreadmill
  ```

  The same names also work as environment variables.

- On GitHub: two repository secrets (**Settings → Secrets and variables → Actions → New repository secret**):

  | Secret | Value |
  | --- | --- |
  | `OPENTREADMILL_KEYSTORE_B64` | the keystore file, base64-encoded |
  | `OPENTREADMILL_KEYSTORE_PASSWORD` | its password |

  To get the base64 text on Windows: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.opentreadmill\opentreadmill.keystore")) | Set-Clipboard`

Without these, builds still work but use Android's throwaway debug key. Such an APK can't update the
installed app, so CI doesn't publish a release from it.

## Back it up

Keep a copy of the keystore and its password somewhere safe (a password manager). If it is lost,
the next build can only be installed by uninstalling the app first, which deletes its profiles and history.

## Making a new one

```sh
keytool -genkeypair -keystore opentreadmill.keystore -storetype PKCS12 -alias opentreadmill \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=OpenTreadmill"
```

A new key means the app on the console has to be uninstalled once before the first install.
