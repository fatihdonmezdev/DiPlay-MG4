# MG4CPlay (SWI165 fork)

Wireless CarPlay for the MG4 head unit. This is a personal fork of
[fatihdonmezdev/DiPlay-MG4](https://github.com/fatihdonmezdev/DiPlay-MG4), adapted
for an Australian **MG4 on SWI165 firmware** (`SWI165-29176-1100R11`, Android 9,
AUTUS SAIC MT2712, 1920x720 panel). Upstream is tested on SWI69.

Package: `com.shihab.diplay`. Car-test builds from this fork install as
`com.shihab.diplay.hudtest`, alongside the upstream release.

[Upstream releases](https://github.com/fatihdonmezdev/DiPlay-MG4/releases) ·
[Report a problem upstream](https://github.com/fatihdonmezdev/DiPlay-MG4/issues/new)

## What this fork changes

| Change | Detail |
|---|---|
| Firmware profile | Reads `ro.build.mt2712.version` at start-up and applies SWI69 or SWI165 settings. Unknown firmware keeps upstream behaviour. |
| SWI165 launcher | Uses `com.saicmotor.hmi.launcher`. The stock CarPlay-card shortcut stays off until that card is measured. |
| Video renderer | SWI165 draws CarPlay into a `SurfaceView` (hardware overlay), like the stock AllGo CarPlay app, to fix the washed-out picture. **Settings > Window > Overlay video renderer** switches it on any firmware. |
| Mac build without ADB | Build steps for a Mac and install from a USB stick, below. |

Details: [docs/MG4_SWI165.md](docs/MG4_SWI165.md).

## What it does

- Wireless CarPlay over the car's own hotspot, with local authentication: no
  dongle, no Mac, no account, no authentication server.
- Connects in about five seconds from launch to first frame.
- Audio stays on the car's Bluetooth link. CarPlay drives the screen; the MG's own
  A2DP path plays the sound, which is the one that works reliably on this unit.
- The car switches to its Bluetooth Music source automatically when CarPlay
  connects, so a head unit sitting on Radio after start-up still plays.
- Battery and range reporting to the iPhone, plus the parked safety gate, using
  EVHardware's read-only paths (SWI69 and SWI165 are both supported).
- In-app update checks against the upstream repository's releases.
- Local diagnostic export. Reports leave the car only if you choose to share them.
- Installs alongside other projection apps. Run one at a time.

Interface languages: English, Arabic, Russian, Spanish and Simplified Chinese.
Pick one in Settings; on Android 13+ it follows Android's per-app language
setting.

## Building on a Mac

Needs the Android SDK (Android Studio installs it at `~/Library/Android/sdk`) and
the submodule:

```bash
git submodule update --init --recursive
```

The car build needs the CarPlay identity files, which are never committed. Copy
them out of any upstream release APK (`/tmp/diplay-release.apk` here) into
`.private/`, which git ignores:

```bash
mkdir -p .private/runtime-assets && unzip -o /tmp/diplay-release.apk 'assets/offline-mfi/*' -d /tmp/diplay-mfi && cp -R /tmp/diplay-mfi/assets/offline-mfi .private/runtime-assets/
```

Then, in the same terminal window:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

```bash
export DIPLAY_AUTH_ASSETS_DIR="$PWD/.private/runtime-assets"
```

```bash
./scripts/build_mg4.sh
```

The signed APK lands in `dist/MG4CPlay-v<version>.apk` (git-ignored). No
Android Studio? Use `brew install openjdk@21` and set `JAVA_HOME` to
`$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home`.

## Installing

**USB stick (no ADB):** copy the APK to a USB stick, open it in the car's file
manager and tap it to install.

**ADB:** the APK is platform-signed and installs as a system app, so the usual
`adb install` is refused by this head unit. Use:

```bash
adb push MG4CPlay-v<version>.apk /data/local/tmp/update.apk
```

```bash
adb shell "pm install -r -f -d -t /data/local/tmp/update.apk"
```

The `-f` flag is the one that matters. See
[docs/MG4_PLATFORM_INSTALL.md](docs/MG4_PLATFORM_INSTALL.md) for why, and
[docs/RELEASING.md](docs/RELEASING.md) for the full build-and-install path.

## Updates

Updates come from the upstream repository, not this fork. The app checks for
updates at startup and from the **↻** button on the home screen. It downloads the new version, verifies its SHA-256 and signing
certificate, then opens Downloads so you can tap the file to install it.

That last tap is manual because the head unit blocks apps from installing
packages silently. Both `pm install` and the session installer are refused by
the platform, whatever directory the file sits in. Details and troubleshooting:
[docs/OTA.md](docs/OTA.md).

## Caveats

This is **not an Apple-certified product**. The APK bundles an experimental
accessory identity recovered from public Carlinkit firmware, not an MFi identity
provisioned for this app. The bundled private key is extractable. Whether iOS
keeps accepting it, and whether it is suitable for wider distribution, is
unresolved.

DRM-protected video such as Apple TV+ is not supported; this is not a licensed
FairPlay receiver. Netflix does not support AirPlay.

## Documentation

- [SWI165 firmware profile](docs/MG4_SWI165.md): start here for this fork
- [Building, installing and releasing](docs/RELEASING.md)
- [OTA updates](docs/OTA.md)
- [Platform-signed builds](docs/MG4_PLATFORM_BUILD.md)
- [Installing over ADB](docs/MG4_PLATFORM_INSTALL.md)
- [Release notes](CHANGELOG.md)

## Source and credits

Forked from [fatihdonmezdev/DiPlay-MG4](https://github.com/fatihdonmezdev/DiPlay-MG4)
by [@fatihdonmezdev](https://github.com/fatihdonmezdev). Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The
home/settings UI adapts [DiAuto](https://github.com/shihabal3amri/DiAuto),
AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices
when distributing modifications. CarPlay and its icon belong to Apple Inc.; no
affiliation or endorsement is implied.

Accessory and signing keys are excluded from source archives; tests generate
synthetic identities at runtime. Source and CI builds omit runtime identity
assets by default; a car build selects them explicitly through
`DIPLAY_AUTH_ASSETS_DIR`. Publishing an APK makes its bundled identity
extractable.
