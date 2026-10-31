# MG4CPlay

Wireless CarPlay for the MG4 head unit, maintained by
[@fatihdonmezdev](https://github.com/fatihdonmezdev).

Tested target: **MG4 Comfort, SWI69 firmware, Android 9, AUTUS SAIC MT2712**.
SWI165 has its own firmware profile (launcher package and video renderer); see
[docs/MG4_SWI165.md](docs/MG4_SWI165.md). Other MG4 firmware may work. Package:
`com.shihab.diplay`.

[Releases](https://github.com/fatihdonmezdev/DiPlay-MG4/releases) ·
[Report a problem](https://github.com/fatihdonmezdev/DiPlay-MG4/issues/new)

## What it does

- Wireless CarPlay over the car's own hotspot, with local authentication — no
  dongle, no Mac, no account, no authentication server.
- Connects in about five seconds from launch to first frame.
- Audio stays on the car's Bluetooth link. CarPlay drives the screen; the MG's own
  A2DP path plays the sound, which is the one that works reliably on this unit.
- The car switches to its Bluetooth Music source automatically when CarPlay
  connects, so a head unit sitting on Radio after start-up still plays.
- Battery and range reporting to the iPhone, plus the parked safety gate, using
  EVHardware's read-only SWI69 paths.
- In-app update checks against this repository's releases.
- Local diagnostic export. Reports leave the car only if you choose to share them.
- Installs alongside other projection apps. Run one at a time.

Interface languages: English, Arabic, Russian, Spanish and Simplified Chinese.
Pick one in Settings; on Android 13+ it follows Android's per-app language
setting.

## Installing

The APK is platform-signed and installs as a system app, so the usual
`adb install` is refused by this head unit. Use:

```bash
adb push MG4CPlay-v<version>.apk /data/local/tmp/update.apk
adb shell "pm install -r -f -d -t /data/local/tmp/update.apk"
```

The `-f` flag is the one that matters — see
[docs/MG4_PLATFORM_INSTALL.md](docs/MG4_PLATFORM_INSTALL.md) for why, and
[docs/RELEASING.md](docs/RELEASING.md) for the full build-and-install path.

## Updates

The app checks for updates at startup and from the **↻** button on the home
screen. It downloads the new version, verifies its SHA-256 and signing
certificate, then opens Downloads so you can tap the file to install it.

That last tap is manual because the head unit blocks apps from installing
packages silently — both `pm install` and the session installer are refused by
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

- [Building, installing and releasing](docs/RELEASING.md) — start here
- [OTA updates](docs/OTA.md)
- [Platform-signed builds](docs/MG4_PLATFORM_BUILD.md)
- [SWI165 firmware profile](docs/MG4_SWI165.md)
- [Installing over ADB](docs/MG4_PLATFORM_INSTALL.md)
- [Release notes](CHANGELOG.md)

## Source and credits

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The
home/settings UI adapts [DiAuto](https://github.com/shihabal3amri/DiAuto),
AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices
when distributing modifications. CarPlay and its icon belong to Apple Inc.; no
affiliation or endorsement is implied.

Accessory and signing keys are excluded from source archives; tests generate
synthetic identities at runtime. Source and CI builds omit runtime identity
assets by default — a car build selects them explicitly through
`DIPLAY_AUTH_ASSETS_DIR`. Publishing an APK makes its bundled identity
extractable.
