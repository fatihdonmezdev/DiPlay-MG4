# Building, Installing and Releasing

Everything needed to go from a clean checkout to a signed APK on the car and a
published release. Written for the MG4 Comfort / SWI69 / Android 9 target.

> **This fork (SWI165):** the short Mac build path, including where to get the
> identity files and how to install from a USB stick without ADB, is in the
> [README](../README.md#building-on-a-mac). Releases and OTA below describe the
> upstream repository; this fork doesn't publish releases.

## Repository layout

| Path | What it is |
| --- | --- |
| `mobile/` | The app that ships. `applicationId com.shihab.diplay`; the debug build adds `.hudtest` and `-hud-test`, so car-test builds install as `com.shihab.diplay.hudtest`. |
| `common/` | Activities and UI — `DiPlayActivity`, `CarPlayHostActivity`, `OtaUpdater`. |
| `shared/` | The CarPlay stack: AirPlay, iAP2, media, orchestration, networking. |
| `automotive/` | Android Automotive variant. Not the MG4 target. |
| `vendor/MG4ABRP/` | Git submodule. Provides `:evhardware` (`SaicMediaPlayer`, vehicle telemetry). |
| `tools/` | Platform signing keys: `platform.pk8`, `platform.x509.pem`, `platform.key`, `platform.p12`. |
| `.private/` | Local-only inputs: `runtime-assets/offline-mfi/` (CarPlay identity) and `prebuilt-jni/`. Never committed. |

Two GitHub remotes exist:

- **`mg4`** → `fatihdonmezdev/DiPlay-MG4` — the source repo *and* where releases go.
- **`mg4-public`** → `fatihdonmezdev/MG4-Wireless-Carplay` — older public mirror. The
  OTA updater no longer reads it.

## One-time setup

```bash
# Android SDK location
printf 'sdk.dir=%s/Android/Sdk\n' "$HOME" > local.properties

# Submodule (provides :evhardware)
git submodule update --init --recursive
```

Requirements: JDK 17+, Android SDK with build-tools (36.0.0 is what these notes
use), NDK `28.2.13676358`.

## Building a car APK

The build needs the CarPlay identity assets, so always export these first:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export DIPLAY_AUTH_ASSETS_DIR="$PWD/.private/runtime-assets"
export DIPLAY_PREBUILT_JNI_DIR="$PWD/.private/prebuilt-jni"

./gradlew :mobile:assembleStandaloneDebug
```

`assembleStandaloneDebug` is `assembleDebug` plus a check that the authentication
assets are actually present — plain `assembleDebug` produces an identity-free APK
that cannot pair with an iPhone.

Output: `mobile/build/outputs/apk/debug/mobile-debug.apk` (unsigned for our purposes —
it carries the debug key, not the platform key).

### Platform signing

The app runs as `android.uid.system`, which requires the MG4 platform key:

```bash
BT="$ANDROID_HOME/build-tools/36.0.0"
VERSION="0.2.9-mg4.54"

"$BT/zipalign" -f 4 \
  mobile/build/outputs/apk/debug/mobile-debug.apk \
  "/tmp/MG4CPlay-v$VERSION-aligned.apk"

"$BT/apksigner" sign \
  --key tools/platform.pk8 --cert tools/platform.x509.pem \
  --out "/tmp/MG4CPlay-v$VERSION.apk" \
  "/tmp/MG4CPlay-v$VERSION-aligned.apk"
```

`scripts/build_mg4.sh` does all of the above in one go, but its `VERSION` variable
has to be kept in step with `mobile/build.gradle.kts` by hand.

### Verifying before you ship

```bash
# Must print the platform cert: c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8
"$BT/apksigner" verify --print-certs "/tmp/MG4CPlay-v$VERSION.apk" | grep "SHA-256 digest"

# Must show the expected package and version
"$BT/aapt" dump badging "/tmp/MG4CPlay-v$VERSION.apk" | grep "^package:"
```

A mismatched certificate means the OTA signature check will reject the update and
`pm install -r` will refuse to replace the installed app.

## Installing on the head unit

`adb install` does **not** work on this unit. Use push + `pm install -f`:

```bash
adb push "/tmp/MG4CPlay-v$VERSION.apk" /data/local/tmp/update.apk
adb shell "pm install -r -f -d -t /data/local/tmp/update.apk"
adb shell "dumpsys package com.shihab.diplay.hudtest | grep versionName"
```

- `-r` replace, `-f` force internal flash, `-d` allow downgrade, `-t` allow test packages.
- `-f` is the one that matters: without it the OEM package manager answers
  `Not allowed to install non-system apps on internal storage`.

Background and the full list of flag combinations that do *not* work:
[`MG4_PLATFORM_INSTALL.md`](./MG4_PLATFORM_INSTALL.md).

Launch it:

```bash
adb shell "am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.DiPlayActivity"
```

## Version numbering

Bump **both** of these together, in the same commit as the change they ship:

- `mobile/build.gradle.kts` → `versionCode` (integer, +1) and `versionName` (`0.2.9-mg4.NN`).
- `scripts/build_mg4.sh` → `VERSION` (same string, no `v` prefix).

The debug build type appends `-hud-test` to the name, so `0.2.9-mg4.54` installs as
`0.2.9-mg4.54-hud-test`. OTA strips non-digits and compares numerically per
component, so `mg4.9` sorts *below* `mg4.10`.

## Publishing a release

Releases go to **`andrew-snape/DiPlay-MG4`**. The OTA updater reads exactly this repo.

```bash
gh release create "v$VERSION" "/tmp/MG4CPlay-v$VERSION.apk" \
  --repo andrew-snape/DiPlay-MG4 \
  --title "MG4CPlay $VERSION" \
  --notes-file /tmp/notes.md
```

Rules the updater depends on:

| Requirement | Why |
| --- | --- |
| Tag `v0.2.9-mg4.NN` | Parsed for the version comparison. |
| Not a draft, not a prerelease | Both are skipped during the check. |
| Exactly one asset, named `MG4CPlay-v<version>.apk` | The asset filter matches `MG4CPlay-*.apk`. |
| Signed with `tools/platform.pk8` | Signature check compares against the installed app. |

GitHub computes the SHA-256 `digest` on upload and OTA verifies against it — no
separate hash file. If you replace an asset after publishing, use
`gh release upload … --clobber` and confirm the digest changed:

```bash
gh api repos/andrew-snape/DiPlay-MG4/releases/tags/"v$VERSION" --jq '.assets[0].digest'
shasum -a 256 "/tmp/MG4CPlay-v$VERSION.apk"
```

Release notes are user-facing and written in English: say what changed from the
driver's point of view, and say plainly when something still needs a manual step.
Keep a short "carried over" list so one release page is enough context.

## Commit messages

Conventional-commit prefix, imperative subject, lower case after the colon:

```
fix: keep Bluetooth music playing on CarPlay connect
feat: check for updates at startup
perf: decouple CarPlay receive and decode processing
chore: build version 0.2.9-mg4.54
```

The body is where the value is. State what was wrong and *why* the fix works,
especially for platform quirks that will not be obvious in six months:

```
fix: send the driver to Downloads when the install is refused

Neither automatic route works on this head unit. pm install runs in
system_server, which SELinux denies app_data_file, sdcardfs and the
shell-owned /data/local/tmp alike; the OEM package manager answers session
installs with "not allowed to install non-system apps on internal storage".

The package is downloaded and verified regardless, so the fallback now opens
the Downloads app and says which file to tap, matching what DiAuto-MG4 does.
```

Wrap at ~76 characters. Mention the version bump at the end when the commit
carries one. Avoid `-m` with text containing apostrophes or double quotes — write
the message to a file and use `git commit -F`.

## Pushing

```bash
git push mg4 main
```

> The `tools/` directory contains the platform **private key**. That is deliberate
> for this repo, but it means anyone with read access can sign system-privileged
> APKs for the car. Keep that in mind before widening access or mirroring to
> `mg4-public`.

## Release checklist

```
[ ] versionCode + versionName bumped in mobile/build.gradle.kts
[ ] VERSION bumped in scripts/build_mg4.sh
[ ] ./gradlew :mobile:assembleStandaloneDebug  (with the three env vars)
[ ] zipalign + apksigner with tools/platform.pk8
[ ] apksigner verify shows c8a2e9bc…
[ ] aapt dump badging shows the expected package + version
[ ] installed and smoke-tested on the car
[ ] committed and pushed to mg4/main
[ ] gh release create on andrew-snape/DiPlay-MG4
[ ] release digest matches the local shasum
```

## Device quick reference

```bash
adb devices -l                                              # expect: model:SAIC
adb shell "dumpsys package com.shihab.diplay.hudtest | grep -E 'versionName|userId='"
adb shell "dumpsys window | grep mCurrentFocus"             # what is on screen
adb logcat -c && adb logcat -v time | grep -E "DiPlay|xcertplay-usb"
adb shell "rm -f /storage/emulated/0/Download/MG4CPlay*.apk"   # clear stale OTA files
```

`userId=1000` with `sharedUser=android.uid.system` confirms the APK was platform
signed and accepted as a system app. Anything else means the signature is wrong.
