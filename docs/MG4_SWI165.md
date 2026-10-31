# MG4 SWI165 firmware

DiPlay reads `ro.build.mt2712.version` at start-up and picks a firmware profile
(`common/.../Mg4FirmwareProfile.kt`). Screen size, signing and install steps are the
same as SWI69: platform key, `pm install -r -f -d -t` from `/data/local/tmp`.

| | SWI69 | SWI165 |
|---|---|---|
| Stock launcher package | `com.saicmotor.launcher` | `com.saicmotor.hmi.launcher` |
| Launcher CarPlay card shortcut | On (measured) | Off until the card is measured |
| Default video renderer | TextureView | SurfaceView (overlay) |
| Vehicle data (EVHardware) | Supported | Supported |

## Video renderer

The stock `com.allgo.carplay.service` (AllgoCarplay_EH32) decodes CarPlay video
straight into a `SurfaceView`, so frames go to the display's hardware overlay plane.
DiPlay's original `TextureView` path composites the video on the GPU instead, which
looks washed out next to the stock app on SWI165.

SWI165 now defaults to the `SurfaceView` path. **Settings > Window > Overlay video
renderer** switches between the two on any firmware. The choice applies the next time
CarPlay opens. The diagnostic log records which renderer and firmware profile were used:

```text
Video renderer SurfaceView firmware=SWI165-29176-1100R11 profile=SWI165
```

## Launcher shortcut

The SWI165 launcher's CarPlay card has not been measured, so the transparent touch
target is not created on this firmware. Measuring it needs a screenshot of the stock
home screen (`adb exec-out screencap -p > launcher.png`); add the rectangle to the
SWI165 branch of `Mg4FirmwareProfile.forFirmware`. Cars running a replacement home
screen such as MG4Dash never show the stock card, so they don't need it.

## Signing note

`com.allgo.carplay.service` on SWI165 is signed with the same AOSP platform
certificate as DiPlay (SHA-1 `27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA`).
DiPlay still installs under its own package and leaves the AllGo stack in place,
because the launcher, SystemUI and mirroring control bind to AllGo's services.
