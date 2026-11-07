# Release build

A release APK is signed with a local key. The key and its password are never committed:
`.gitignore` excludes `apps/android/release.keystore` and `apps/android/keystore.properties`.

## Signing identity

| Item | Location |
| --- | --- |
| Keystore | `apps/android/release.keystore` (RSA 4096, valid 10,000 days) |
| Alias | `dsh-app` |
| Password | `apps/android/keystore.properties` |

Back both files up somewhere safe. Android only accepts an update signed with the same key:
if the keystore is lost, installed phones must uninstall and re-pair, and nothing can update
them in place.

## Build

```bash
cd apps/android
ANDROID_HOME="$HOME/Library/Android/sdk" ANDROID_SDK_ROOT="$ANDROID_HOME" \
  ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

The signed APK lands in `apps/android/app/build/outputs/apk/release/app-release.apk`.
Without `keystore.properties` the release signing config is skipped, so debug builds and unit
tests still work on a machine that only checks out the code.

## Version

Bump `versionCode` and `versionName` in `apps/android/app/build.gradle.kts` before publishing.
Android rejects an APK whose `versionCode` is not greater than the installed one.

## Verify

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
  apps/android/app/build/outputs/apk/release/app-release.apk
shasum -a 256 apps/android/app/build/outputs/apk/release/app-release.apk
```
