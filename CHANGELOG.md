# MG4CPlay 0.2.9-mg4.71 (SWI165 fork), 2026-11-01

- Diagnostics for audio loss: every 5 s while packets are missing, the log records resend requests sent to the iPhone, packets recovered, whether resends could be sent at all, and the car's own network counters (UDP receive errors and buffer overflows, and `ap0` hotspot drops and errors). This tells Wi-Fi loss in the air apart from loss inside the head unit. mg4.70 lost about 1 in 7 music packets and half the call packets before they reached DiPlay.
- Settings tidied for MG4. Hidden because the firmware profile sets them: efficient video (HEVC is now always on for MG4), audio focus (Wi-Fi audio takes it; Bluetooth Music must not), advanced audio channel mapping and the music audio channel (MG4 uses the music stream), and in-app updates (they offered upstream builds without this fork's changes). Navigation audio channel stays until Maps prompts are tested.
- Fix the music buffer labels. They read 300, 500 and 1000 ms but set 500, 750 and 1000 ms. They now read 500 ms, 750 ms (default) and 1000 ms.

# MG4CPlay 0.2.9-mg4.70 (SWI165 fork), 2026-11-01

- Test switch for the rounded black corners the iPhone draws on CarPlay (the stock CarPlay app shows square corners). **Display and performance > Square corners (test)** describes the screen without view areas, the way older factory receivers do. Off by default; the log's video renderer line records `viewAreas=true|false`.

# MG4CPlay 0.2.9-mg4.69 (SWI165 fork), 2026-11-01

- Fix: no CarPlay audio at all in mg4.67 and mg4.68. Those builds stopped offering Opus for the microphone on Android 9, and the iPhone then treated the car as screen-only: CarPlay was not offered as an audio output, so music and calls stayed on Bluetooth while DiPlay had released the car's Bluetooth music. DiPlay offers Opus again.
- Call microphone on Android 9: Android 9 has no Opus encoder, so DiPlay now encodes the microphone in software with Concentus, a pure-Java Opus encoder. The platform encoder is still used where one exists. The microphone log line shows which (`opus=software` or `opus=platform`).

# MG4CPlay 0.2.9-mg4.68 (SWI165 fork), 2026-11-01

- Calls: the call audio player stalled on every call in mg4.65 and mg4.66. Call audio arrived from the iPhone and the player was set up, but it never logged stats, focus or an error, even when the call ended, so it was stuck before playing anything. Calls and Siri now write to the car's audio output without waiting, so a car output that never starts can no longer hang the stream. Music is unchanged.
- The diagnostic log now records each step of every audio stream: decoder ready or failed, audio focus requested, kept or not requested, first sound decoded, playback started, the first write error, and a summary when the stream ends. If a stream has not started playing after 6 seconds, the log names the step it is stuck on.
- AVRCP on SWI165 has no disconnect command. The log now says so instead of reporting `NoSuchMethodException`; AVRCP still drops with the A2DP sink.

# MG4CPlay 0.2.9-mg4.67 (SWI165 fork), 2026-11-01

- Fix the call microphone on Android 9. DiPlay offered Opus for microphone input, so the iPhone chose it for calls, but Android 9 has no Opus encoder and the microphone never started (`Microphone telephony: failed`, codec OPUS, in mg4.66). Opus input is now offered only when the head unit has an Opus encoder; otherwise the iPhone sends plain PCM.
- Confirmed on the car in mg4.66: Wi-Fi music, and steering-wheel next and previous (wheel key codes 5 next, 4 previous, 3 mute, 1 volume up).

# MG4CPlay 0.2.9-mg4.66 (SWI165 fork), 2026-11-01

- Wi-Fi audio: steering-wheel next and previous now control CarPlay. On SWI165 the wheel keys arrive as a SystemUI broadcast handled by the car's MediaService, not as Android media keys, so DiPlay listens for that broadcast while CarPlay runs. Every wheel key is logged; mute and volume stay with the car.
- Wi-Fi audio is now the default on SWI165 (other firmware keeps Bluetooth music). The switch under Audio routing is no longer marked experimental and still turns it off.
- In Wi-Fi audio mode, disconnect the car’s Bluetooth music (A2DP and AVRCP) from the iPhone once CarPlay is up, as factory wireless CarPlay does when the phone asks. Hands-free stays connected and pairing is untouched. This replaces an unused upstream release that switched the whole Bluetooth radio off.
- Wi-Fi audio: fix silent phone calls. With audio over Wi-Fi the iPhone sends calls to CarPlay, and the car did not play call audio from DiPlay. On Android 9 call audio now uses the music stream (which reaches the speakers), and the microphone falls back to the plain mic if the car refuses the call-processing source. The log records the microphone source and call audio route.
- Fix the Bluetooth Music switch on SWI165. Read from the car's own MediaService: its Bluetooth interface has no `requestBtAudioFocus` (DiPlay was sending command 15, which does not exist), and `play` (2) is what switches the car to Bluetooth Music. DiPlay now sends only `play` and logs its result, plus the Bluetooth connection and play state.

# MG4CPlay 0.2.9-mg4.65 (SWI165 fork), 2026-11-01

- Bluetooth Music switch: connect to the car's media service when CarPlay starts instead of at the handoff, keep trying for up to 10 seconds, and record each outcome in the diagnostic report (bind refused, not connected, focus or play rejected). mg4.64 logs showed "never answered" after 3 seconds with no reason.
- If the SAIC media service cannot switch the car to Bluetooth Music, fall back to pressing play on the car's A2DP media browser, the method MG4Dash uses. The log says which path ran and whether that service exists on this firmware.
- Car audio source codes now follow MG4Control's on-car measurements and the SWI68 launcher (6 USB, 14 online, 50 CarPlay, 70 Android Auto), and the log adds the current player type.
- More diagnostics for audio testing: the car's audio source before and after the Bluetooth Music switch (and once in Wi-Fi mode), and the head unit's music and call volumes, mute state and audio mode at session start.
- Update Bouncy Castle 1.79 to 1.86 and JmDNS 3.6.3 to 3.6.5 (patch releases).
- Fix the overlay video renderer and Wi-Fi audio switches not appearing. They had been added to an old settings menu that DiPlay no longer opens. They are now in DiPlay's settings: **Display and performance > Overlay video renderer** and **Audio routing > CarPlay audio over Wi-Fi (experimental)**.

# MG4CPlay 0.2.9-mg4.64 (SWI165 fork), 2026-11-01

- Add an optional **CarPlay audio over Wi-Fi (experimental)** switch under Settings (shown from mg4.65). When on, the iPhone sends music over Wi-Fi and DiPlay plays it, DiPlay takes audio focus for music, and the car is no longer switched to Bluetooth Music. Applies when CarPlay reconnects.
- Off by default on every firmware: the car’s Bluetooth Music (hybrid) path is unchanged.
- The log now records the audio mode, firmware, profile and focus setting once per session.
- Not yet tested on the car. Claiming the car’s CarPlay audio source is still to come if the sound does not reach the speakers.

# MG4CPlay 0.2.9-mg4.63 (SWI165 fork), 2026-11-01

- Fix the "connect Bluetooth for audio" prompt appearing on every connect. The check asked the wrong Bluetooth profile (A2DP source instead of the car's A2DP sink), always found nothing after a 3-second wait, and opened Bluetooth settings over CarPlay. The car now switches to Bluetooth Music when the phone is connected, and only prompts when it is known not to be.

# MG4CPlay 0.2.9-mg4.62 (SWI165 fork), 2026-11-01

- Detect the MG4 firmware generation and apply SWI69 or SWI165 settings; unknown firmware keeps the previous behaviour.
- SWI165: use the `com.saicmotor.hmi.launcher` stock launcher; the CarPlay-card shortcut stays off until that card is measured.
- SWI165: draw CarPlay on the hardware video overlay (SurfaceView), like the stock AllGo CarPlay app, to fix the washed-out picture.
- Add **Settings > Window > Overlay video renderer** to choose the video path on any firmware.
- Build script passes the detected Android SDK to Gradle and writes the signed APK to `dist/`.
- README and docs rewritten for this fork, with Mac build and USB-stick install steps.

# DiPlay 0.2.8 — 2026-09-30

- Keep iPhone location reporting active across the wireless Bluetooth-to-Wi-Fi CarPlay handoff; limit location updates to one per second on wireless and USB.
- Add optional ADB wheel-speed and gear reporting for iPhone dead reckoning when GPS is unavailable. Tunnel use has not yet been verified.
- Add optional iOS 27 video playback on the car screen while parked, with iPhone, touchscreen and steering-wheel controls; close playback when leaving P.
- Explain unsupported DRM-protected video such as Apple TV+, which requires a licensed FairPlay receiver.
- Improve playback error reporting and preserve CarPlay when the head unit cannot play a video.

# DiPlay 0.2.7 — 2026-09-29

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.
- Clarify the BYD-only support scope on the README and all five website editions.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
