# CarPlay song details on the SWI165 cluster

Read from three APKs exported from the car (Nov 2026):
`ClusterInteraction_eh32_aus_local_P` (`com.saicmotor.hmi.clusterprojection`),
`MediaService_eh32_aus_local_P` (`com.saicmotor.service.media`) and
`SaicAdapterService_eh32_aus` (`com.saicmotor.adapterservice`). Read with a small dex
reader (class, field and transaction constants, plus a light disassembly), not jadx.

## Summary

The cluster shows a CarPlay song only when both of these are true:

1. The car's current audio source is **50 (CarPlay media)**. The source comes from the
   `key_car_source_type` extra on the playing track's `AudioAttributes`.
2. The stock AllGo media browser,
   `com.allgo.remoteui.mediabrowserservice/.RemoteUIMediaBrowserService`, publishes a
   media session with the song's metadata.

DiPlay does neither today. Its music track is untagged, and that media browser belongs
to the stock CarPlay stack, which isn't running when DiPlay runs CarPlay.

The adapter service has no music calls. Its `IMapService` is navigation only.

## How the cluster picks the music source

`clusterprojection.repository.AudioSourceManager` listens with SAIC's hidden
`AudioManager.OnAudioSourceChangeCallBack`. `onSourceChanged(AudioAttributes, String)`
reads `getBundle().getInt("key_car_source_type")` and `dealSource` maps it:

| Source | Cluster page | Audio type |
|---|---|---|
| 1, 2, 3 | Radio | |
| 5 | Audio | BT_MUSIC |
| 6, 7, 18 | Audio | USB_MUSIC |
| 14 | Audio | ONLINE_MUSIC |
| 50 | Audio | CAR_PLAY_MUSIC |
| 70 | Audio | ANDROID_AUTO_MUSIC |
| other | None | |

The car's MediaService does the same in its own `presenter.AudioSourceManager`, and
reports it through `IPlayStatusBinderInterface.getCurrentMediaSource` (9).

## Where CarPlay song details come from

`com.saicmotor.sdk.media.mananger.MediaPlayControlManager` (bundled in the cluster app):

- `initListener` builds a `MediaBrowser` for a **hard-coded** component,
  `com.allgo.remoteui.mediabrowserservice` /
  `com.allgo.remoteui.mediabrowserservice.RemoteUIMediaBrowserService`, and connects.
- On connect it creates a `MediaController` from `getSessionToken()` and registers a
  callback.
- `onMetadataChanged` copies `METADATA_KEY_TITLE`, `ARTIST`, `ALBUM`, `ALBUM_ART`
  (bitmap) and `DURATION` into an `AudioInfoBean`.
- Only when the current source is 50 or 70 does it set player 5 (CarPlay) or 6
  (Android Auto) and call `onPlayMusicInfoChanged`. The cluster's `AudioRepository`
  then shows the song.
- `onPlaybackStateChanged` passes on the state and position for the progress bar.

For BT, USB and online sources the cluster asks MediaService instead
(`BtMusicManager.getMetaData`, `UsbMusicManager.getCurrentPlayerMusic`,
`OnlineMusicManager.getOnlineMusic`). There is no MediaService route for CarPlay.

## Options for DiPlay

1. **Tag DiPlay's music track as source 50**, as the stock AllGo app does
   (`Mg4CarAudioSource`, already used for calls with 53). On its own this should switch
   the cluster to its CarPlay music page, but with no song details unless step 2 works.
2. **Provide the media session the cluster looks for.** A `MediaBrowserService` can
   hand out any session token, so a service with the right component name could return
   a DiPlay `MediaSession` fed from iAP2 `NowPlayingUpdate` (0x5001), which DiPlay
   already subscribes to (title 1, duration 4, album 6, artist 12, artwork 26). The
   component name has to match exactly, so this needs one of:
   - a small companion APK with package `com.allgo.remoteui.mediabrowserservice`, if
     that package is **not installed** on the car; or
   - updating the stock package, if it is installed and platform-signed (replaces part
     of the stock CarPlay stack: not recommended).

   The package **is installed** (`RemoteUIMediaBrowserService_EH32.apk`), so the
   companion route is out. See "The stock media browser" below.

   Still to check:
   - A DiPlay `MediaSession` on Android 9 once made the car's Bluetooth stack pause the
     iPhone (see `CarPlayMediaKeys`). That was with A2DP still connected; now that
     DiPlay drops the Bluetooth music link, it needs a fresh car test.

## The stock media browser

`com.allgo.remoteui.mediabrowserservice` (`RemoteUIMediaBrowserService_EH32.apk`) is
installed on the car. Its manifest:

- Runs as `android.uid.system`, version 1.0, target SDK 28.
- One service, `.RemoteUIMediaBrowserService`, in process `:RemoteUIMBSServer`,
  **exported with no permission**. It answers three actions:
  - `android.media.browse.MediaBrowserService` (what the cluster binds to);
  - `com.allgo.remoteui.mediabrowserservice.RemoteUIMediaBrowserService`;
  - `com.allgo.remoteui.mediabrowserservice.RemoteUIMediaBrowserServiceInterface`.
    This looks like the feed: a binder interface the AllGo stack uses to push the song
    and play state into the browser's media session.
- A receiver, `.RemoteUIMediaBrowserServiceStarter`, for `BOOT_COMPLETED` and
  `MEDIA_BUTTON`.

Because the service is exported with no permission, DiPlay can bind it with the
`...Interface` action. If that interface takes metadata, DiPlay can feed the stock
browser directly and the cluster needs nothing else.

The exported APK has **no `classes.dex`**: the code was compiled ahead of time, and the
dex lives beside it on the car as `oat/arm64/*.vdex` (and `.odex`). The interface's
descriptor and transaction codes are in there. Not guessed here.
