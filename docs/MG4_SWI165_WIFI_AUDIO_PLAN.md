# Plan: Wi-Fi audio on SWI165

Status: steps 1, 2, 3.1, 4 and 5 are implemented in `0.2.9-mg4.64` and untested on the car. Step 3.2 is still pending.

## Goal

Let SWI165 cars play CarPlay music over Wi-Fi through DiPlay itself, instead of the
"hybrid" path where music goes over the car's Bluetooth Music player. Hybrid stays the
default until Wi-Fi audio is proven on the car, and is always one switch away.

## How hybrid works today

| Piece | File | What it does |
|---|---|---|
| Session config | `common/.../CarPlayHostActivity.kt` (`disableAudioOutput = true`) | Tells the iPhone this is a screen-only receiver |
| Info plist | `shared/.../airplay/AirPlayInfoPlist.kt` | Drops audio features, `RESOURCE_AUDIO`, `audioFormats` and `audioLatencies` when audio is off |
| Media sink | `shared/.../media/AndroidMediaSink.kt` (`onAudioStarted`, `onAudioRtp`) | Discards any `"media"` stream; navigation and Siri still play locally |
| Handoff | `shared/.../orchestration/CarPlayController.kt` (`switchCarSourceToBluetooth`) | Moves the car to Bluetooth Music after Wi-Fi connects |
| Focus | `AndroidMediaSink.kt` (`AudioFocusCoordinator`) | Never forces audio focus, so the car's Bluetooth player isn't paused |

All of these assume hybrid. Wi-Fi audio means turning each one the other way, together.

## Steps

### 1. One audio mode setting

- Add `AudioMode { HYBRID_BLUETOOTH, CARPLAY_WIFI }` and save it in `AirPlayPersistence`
  (`audio_mode`), using the same "absent means firmware default" pattern as the overlay
  video renderer.
- Add `defaultAudioMode` to `Mg4FirmwareProfile`. It is `HYBRID_BLUETOOTH` for every
  firmware to start with, SWI165 included.
- Settings > Audio: a two-option choice, **Car Bluetooth (recommended)** and **CarPlay
  over Wi-Fi (experimental)**, with the note "Applies when CarPlay reconnects".

### 2. Wire the mode through the session

- `CarPlayHostActivity`: set `disableAudioOutput = (mode == HYBRID_BLUETOOTH)`.
- `AndroidMediaSink`: add an `audioMode` constructor parameter. Only drop `"media"`
  streams in hybrid. In Wi-Fi mode, `"media"` goes through `audioRenderer(...)` like
  every other stream.
- `CarPlayController`: skip `switchCarSourceToBluetooth()` and its Bluetooth prompt in
  Wi-Fi mode. Keep closing the RFCOMM bootstrap as now.

### 3. Get the sound to the speakers on SWI165

This is the part that may not work, and why it's a test build. Three things to try, in
order, each behind a diagnostic log line:

1. **Plain Android route.** On Android 9 the media channel already uses
   `STREAM_MUSIC` (`channelOverride`). Request audio focus in Wi-Fi mode (today it is
   never forced). If music is heard, stop here.
2. **Claim the car's projection source.** If the car stays silent or keeps playing
   Radio, ask the SAIC media service for its CarPlay source (`0x32`) before playing,
   the way the stock AllGo app does. That needs the focus call on
   `ICpAaBinderInterface`; its transaction code has to be read from the SWI165 launcher
   or MediaService APK in `Car Exports/apps` first. A new `Mg4ProjectionSource`, beside
   `Mg4BluetoothSource`.
3. **Fallback.** If neither works, the setting stays available but marked
   "not working on this firmware", and hybrid remains the default.

### 4. Logging for the car test

One line per session so the log answers the test on its own:

```text
Audio mode=CARPLAY_WIFI firmware=SWI165-29176-1100R11 focus=granted source=0x32 route=STREAM_MUSIC
```

Plus the existing per-stream `audio route` lines for media, navigation and Siri.

### 5. Tests

- Unit: `AirPlayInfoPlist` lists `RESOURCE_AUDIO` and `audioFormats` only in Wi-Fi mode.
- Unit: the sink drops `"media"` only in hybrid mode.
- Unit: the profile default is `HYBRID_BLUETOOTH` for SWI69, SWI165 and unknown firmware.

## Car test checklist

Run with the overlay video renderer on, and the iPhone paired to the car as normal.

| Check | Pass |
|---|---|
| Music starts within 2 seconds of pressing play | |
| Volume knob and steering-wheel volume change it | |
| Steering-wheel next, previous and pause work | |
| Maps prompts lower the music, then it comes back | |
| Siri: you can hear it and it hears you | |
| Phone call: both sides hear each other, music resumes after | |
| Radio doesn't keep playing underneath | |
| Switching back to Car Bluetooth and reconnecting restores music | |
| Picture still good (no new flicker or stutter) | |

## Risks

- **Calls move to CarPlay.** With audio advertised, the iPhone may send call audio
  over Wi-Fi instead of the car's hands-free, so the microphone path matters. If calls
  break, that alone keeps Wi-Fi audio experimental.
- **Car source fights.** The SAIC media system may switch back to Radio or Bluetooth on
  its own; step 3.2 exists for that.
- **Latency.** Wi-Fi audio has its own buffer (`MediaAudioBuffer`); upstream measured
  400 to 600 ms bursts on SWI69. Expect to tune it.

## Rough size

About 150 to 250 lines across five files, plus tests. Step 3.2 depends on reading one
binder code from the car's own APKs first.
