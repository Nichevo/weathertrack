# Signing and upgrades

Android only installs a new APK over an old one when both are signed with the **same key** and the
new one has a **higher versionCode**. This repo handles both:

- **versionCode** is the GitHub Actions run number, so every build is newer than the last.
- **Signing key** is a single release keystore kept as GitHub Actions secrets. It is never committed.
  If it is lost, the next APK cannot upgrade the installed app: you would have to uninstall, which
  deletes the app's stored data (export it first from the Data tab).

## Repository secrets

Add these under **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
| --- | --- |
| `WT_KEYSTORE_BASE64` | The keystore file, base64-encoded on one line (`base64 -w0 release.jks`) |
| `WT_KEYSTORE_PASSWORD` | Keystore password |
| `WT_KEY_ALIAS` | Key alias (`weathertrack` if unchanged) |
| `WT_KEY_PASSWORD` | Key password (same as the keystore password if unchanged) |

Without these secrets CI still builds, but signs with a throwaway debug key and prints a warning,
and no GitHub Release is published.

## Making a new keystore

```sh
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias weathertrack \
  -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=WeatherTrack"
```

Keep a backup of the keystore and its password somewhere private (a password manager works).

## Getting a build

- Every push to `main` with the secrets set publishes a GitHub Release with the APK attached:
  open the repo's **Releases** page on the phone and tap the APK.
- Every push to any branch also uploads the APK as a workflow artifact (Actions tab, needs sign-in).

The release build uses package `app.weathertrack`. Local debug builds use `app.weathertrack.debug`,
so they install side by side and never touch the real app's data.
