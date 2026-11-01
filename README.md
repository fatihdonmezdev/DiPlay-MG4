# MG4CPlay (SWI165 fork)

Wireless CarPlay for the MG4 head unit. This is a personal fork of
[fatihdonmezdev/DiPlay-MG4](https://github.com/fatihdonmezdev/DiPlay-MG4), adapted
for an Australian **MG4 on SWI165 firmware** (`SWI165-29176-1100R11`, Android 9,
AUTUS SAIC MT2712, 1920x720 panel). Upstream is tested on SWI69.

Current build: **0.2.9-mg4.85**. Car-test builds install as
`com.shihab.diplay.hudtest`, alongside any upstream `com.shihab.diplay` install.
Run one at a time.

## What works on SWI165

| Feature | Status |
|---|---|
| Wireless CarPlay over the car hotspot | Working, about 4 s from launch to first frame |
| Picture (overlay video renderer) | Working, fixes the washed-out look |
| Battery and range sent to the iPhone | Working (Apple Maps EV features) |
| Music over Wi-Fi (CarPlay audio) | Working. Broken in mg4.67 and mg4.68 (no CarPlay audio at all); fixed in mg4.69 and confirmed in the car in mg4.71 |
| Phone calls over Wi-Fi | Working in mg4.83: the call played through the car's phone audio path. Some static occurs when Wi-Fi drops call packets (30–60% loss in one car test); the cause is the Wi-Fi link, not the routing |
| Call microphone | Working. Software Opus encoder on Android 9 (mg4.69), about 5 ms per frame (mg4.83) |
| Steering-wheel next and previous | Working (mg4.66, re-confirmed mg4.80) |
| Hands-free released during CarPlay | Working (mg4.80); released again on each reconnect (mg4.81) |
| Square corners | Confirmed in the car (mg4.70), default since mg4.75 |
| Siri and Maps voice prompts | Siri audio clean in mg4.71 test; Maps voice prompts not yet tested |
| Instrument-cluster directions | Test build (mg4.80), not yet confirmed in the car |
| Bluetooth Music mode (fallback) | Fix in mg4.66, awaiting a car test |

The full test record is in [docs/MG4_SWI165.md](docs/MG4_SWI165.md).

## What this fork changes

| Area | Change |
|---|---|
| Firmware profile | Reads `ro.build.mt2712.version` at start-up and applies SWI69 or SWI165 settings. Unknown firmware keeps upstream behaviour. |
| Video | SWI165 draws CarPlay on the hardware video overlay (`SurfaceView`), like the stock AllGo CarPlay app. |
| Audio | SWI165 plays CarPlay music and calls over Wi-Fi through DiPlay. Once CarPlay is up, the car's Bluetooth music link to the iPhone is disconnected; hands-free stays connected and pairing is kept. Call audio plays like the stock CarPlay app: voice audio tagged as car source 53, with the car in call mode (mg4.83). |
| Calls over CarPlay only | Optional, on by default on MG4 (mg4.82). Keeps hands-free released for the whole CarPlay session, so calls can only come through CarPlay. A short press of the wheel phone key answers or ends a CarPlay call (mg4.78). |
| Steering wheel | SWI165 sends wheel keys as a SystemUI broadcast, not Android media keys. DiPlay listens for it in Wi-Fi mode and sends next and previous to CarPlay. Volume and mute stay with the car. |
| Instrument cluster | Test only (mg4.80): sends CarPlay's next turn, distance and street to the cluster's navigation area through the car's `MapService`. |
| Bluetooth Music mode | Kept as a fallback. Switches the car to Bluetooth Music with the media service's `play` command, as read from the car's own MediaService. |
| Build fixes | Replaces upstream calls to Bluetooth functions that were never published in the EVHardware submodule, so the tree compiles. The build script finds the Android SDK and writes the APK to `dist/`. |
| Diagnostics | Reports record the firmware profile, video renderer, audio mode, car audio source, microphone source and every wheel key. |

## Settings

All in DiPlay's settings screen. Changes apply the next time CarPlay connects.

| Setting | Where | Default on SWI165 |
|---|---|---|
| CarPlay audio over Wi-Fi | Audio routing | On. Off uses the car's Bluetooth Music instead. |
| Calls over CarPlay only | Audio routing | On. Disconnects the car's hands-free while CarPlay runs, so calls can't fall back to Bluetooth. A saved choice still wins. |
| Microphone (test) | Audio routing | Automatic. Call processing for calls, voice recognition for Siri. Plain microphone or call processing can be chosen to compare. |
| Overlay video renderer | Display and performance | On. Turn off if the picture flickers or stays black. |
| Square corners | Display and performance | On. Describes the screen like the stock CarPlay app, so iOS doesn't draw rounded corners. |
| Efficient video (HEVC) | Display and performance | On. Half the Wi-Fi data of H.264. The stock CarPlay app uses H.264; turn off to compare colours. |
| Colour range (test) | Display and performance | Video range. Limited (video) or full (PC) range can be chosen to compare washed-out whites. |
| Music buffer | Display and performance | 750 ms. Try 1000 ms if music skips on a weak Wi-Fi link. |
| Directions on the dashboard (test) | MG4 vehicle | Off. Sends CarPlay directions to the instrument cluster (mg4.80). |

Hidden on MG4 because the firmware profile sets them: audio focus, advanced audio channel mapping, the music audio channel and in-app updates (which point at upstream builds).

## Building on a Mac

**Keep the repo out of iCloud.** If it sits in `~/Documents` with iCloud Desktop &
Documents syncing on, iCloud makes `name 2.xml` copies of build files and the build
fails with "Duplicate resources". Either turn that syncing off, or clean before
each build:

```bash
./gradlew --stop && rm -rf build */build vendor/MG4ABRP/EVHardware/lib/build .gradle
```

One-time setup, from the repo folder:

```bash
git submodule update --init --recursive
```

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

The car build needs the CarPlay identity files, which are never committed. Copy
them out of any upstream release APK (`/tmp/diplay-release.apk` here) into
`.private/`, which git ignores:

```bash
mkdir -p .private/runtime-assets && unzip -o /tmp/diplay-release.apk 'assets/offline-mfi/*' -d /tmp/diplay-mfi && cp -R /tmp/diplay-mfi/assets/offline-mfi .private/runtime-assets/
```

Each build, in the same terminal window (Java from `brew install openjdk@21`, or use
Android Studio's at `/Applications/Android Studio.app/Contents/jbr/Contents/Home`):

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" && export DIPLAY_AUTH_ASSETS_DIR="$PWD/.private/runtime-assets"
```

```bash
./scripts/build_mg4.sh
```

The signed APK lands in `dist/MG4CPlay-v<version>.apk` (git-ignored). The first
build downloads Gradle, the NDK and Android SDK 37, so it takes a few minutes.

## Installing

**USB stick (no ADB):** copy the APK to a USB stick, open it in the car's file
manager and tap it to install. Each build has a higher version number, so it
installs over the last one.

**ADB:** the APK is platform-signed and installs as a system app, so the usual
`adb install` is refused by this head unit. Use:

```bash
adb push MG4CPlay-v<version>.apk /data/local/tmp/update.apk
```

```bash
adb shell "pm install -r -f -d -t /data/local/tmp/update.apk"
```

See [docs/MG4_PLATFORM_INSTALL.md](docs/MG4_PLATFORM_INSTALL.md) for why `-f`
matters.

## Diagnostics

In DiPlay: **Diagnostics > Save diagnostic report**. Reports stay on the car
unless you share them. Wait a few seconds after saving before copying the file
off; some uploads have arrived empty when copied straight away.

## Updates

The in-app update check still looks at the **upstream** repository, so it can offer
builds without this fork's SWI165 changes. Build from this repo instead.

## Caveats

This is **not an Apple-certified product**. The APK bundles an experimental
accessory identity recovered from public Carlinkit firmware, not an MFi identity
provisioned for this app. The bundled private key is extractable. Whether iOS
keeps accepting it is unresolved.

DRM-protected video such as Apple TV+ is not supported; this is not a licensed
FairPlay receiver.

## Documentation

- [SWI165 firmware profile and test record](docs/MG4_SWI165.md): start here
- [Wi-Fi audio plan and car test checklist](docs/MG4_SWI165_WIFI_AUDIO_PLAN.md)
- [Stock AllGo CarPlay app notes](docs/MG4_SWI165_ALLGO_NOTES.md): how the car's own CarPlay app handles calls and audio
- [Release test plan: calls, music, Bluetooth and Wi-Fi](docs/MG4_SWI165_TEST_PLAN.md)
- [Release notes](CHANGELOG.md)
- [Platform-signed builds](docs/MG4_PLATFORM_BUILD.md)
- [Installing over ADB](docs/MG4_PLATFORM_INSTALL.md)
- [Upstream build, release and OTA notes](docs/RELEASING.md), [OTA](docs/OTA.md)

## Source and credits

Forked from [fatihdonmezdev/DiPlay-MG4](https://github.com/fatihdonmezdev/DiPlay-MG4)
by [@fatihdonmezdev](https://github.com/fatihdonmezdev). Based on
[xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI
adapts [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is
included in `docs/licenses`. Preserve those notices when distributing modifications.

The Android 9 call microphone uses [Concentus](https://github.com/lostromb/concentus)
(BSD-3-Clause), a pure-Java Opus encoder.

SWI165 media and steering-wheel details were read from the car's own MediaService
APK and cross-checked against [MG4Control](https://github.com/SliDeeN/MG4Control),
[MG4Dash](https://github.com/Skittle6938/MG4Dash) and an SWI68 launcher project.

CarPlay and its icon belong to Apple Inc.; no affiliation or endorsement is implied.
Accessory and signing keys are excluded from source archives; a car build selects
the identity explicitly through `DIPLAY_AUTH_ASSETS_DIR`. Publishing an APK makes its
bundled identity extractable.
