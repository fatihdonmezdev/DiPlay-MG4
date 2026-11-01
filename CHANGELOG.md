# MG4CPlay 0.2.9-mg4.85 (SWI165 fork), 2026-11-01

- **Temporary: the diagnostic report carries the stock AllGo apps' code.** The cluster reads CarPlay song details only from the stock `com.allgo.remoteui.mediabrowserservice` media session (see [docs/MG4_SWI165_CLUSTER_MEDIA_NOTES.md](docs/MG4_SWI165_CLUSTER_MEDIA_NOTES.md)). That app is exported with no permission and has a `...RemoteUIMediaBrowserServiceInterface` action that looks like its feed, but its APK has no code: it was compiled ahead of time into `oat/<abi>/*.vdex` and `.odex` beside the APK. On MG4, the report now ends with a `Stock app code` section holding those files for `com.allgo.remoteui.mediabrowserservice` and `com.allgo.rui`, base64-encoded with size and SHA-256 (read-only, 6 MB per file, 12 MB in all). This replaces pulling them with ADB and will be removed once read.

# MG4CPlay 0.2.9-mg4.84 (SWI165 fork), 2026-11-01

- **Hide lost call packets.** mg4.83 car test: calls work over the car's phone path (`call track car source=53`, `mode set=2`, then `mode set=0` at hang-up), but the call had some static. The log shows no loss for 40 s, then 30-60% of call packets missing for the last 25 s (gaps up to 2 s, and the whole Wi-Fi link slowed at the same time). The call stream gives no control port, so nothing can be resent, and the platform decoder skipped the gaps. Wi-Fi calls now decode in software (Concentus): a gap of up to 10 frames (200 ms) is filled from the next packet's in-band FEC where the iPhone includes it, and otherwise with Opus packet-loss concealment. The software decoder warms up with the encoder at connect. The log shows `call decoder=software, hides lost packets` and, at hang-up, `call loss packets= fec= concealed= unfilledGaps=`.
- mg4.83 car test: navigation prompts arrive as stream type 101, `audioType=default`, and play on DiPlay's navigation channel with no loss. The iPhone sends `duckAudio` / `unduckAudio` around each prompt; DiPlay doesn't turn music down for them yet.

# MG4CPlay 0.2.9-mg4.83 (SWI165 fork), 2026-11-01

- **Call audio like the stock CarPlay app.** Read from the car's AllGo CarPlay APK (see [docs/MG4_SWI165_ALLGO_NOTES.md](docs/MG4_SWI165_ALLGO_NOTES.md)): it plays calls as `USAGE_VOICE_COMMUNICATION` tagged with the SAIC car source `key_car_source_type=53`, and puts the car in `MODE_IN_CALL` while the call plays. DiPlay's mg4.65 voice track had neither and was silent, so mg4.66 to mg4.82 played calls on the music stream. Wi-Fi calls now use the stock setup and set `MODE_NORMAL` again when the call track stops. Music is unchanged. The log shows `call track car source=53` and `mode set=2 now=2`. If calls are silent, mg4.82 is the fallback.

# MG4CPlay 0.2.9-mg4.82 (SWI165 fork), 2026-11-01

- **Calls over CarPlay only** is now on by default on MG4 (a saved choice still wins). The mg4.80 log's one call with it off arrived over Wi-Fi, but 0.1 s after it started the car's own Bluetooth phone took audio focus (`focus change=-1`), and the car was still in call mode (`mode=2`) at the next start-up. With hands-free released, the iPhone has only CarPlay to send calls to.

# MG4CPlay 0.2.9-mg4.81 (SWI165 fork), 2026-11-01

- **Calls over CarPlay only:** keep hands-free released for the whole session. mg4.80 released it at handoff (and checked at 2 and 5 s), but the iPhone reconnects it on its own later, and calls then went back to Bluetooth. DiPlay now watches the car's hands-free connection and releases it again each time it reconnects during CarPlay, logging `hands-free reconnected ... releasing again`.
- Microphone: warm up the software Opus encoder when CarPlay connects. mg4.80 showed the first half-second of each call at 23 ms per 20 ms frame (worst 173 ms) before settling at 5 ms, which added delay at the start of every call and Siri request.
- Fix the display list (mg4.79) never appearing: the diagnostic privacy filter drops lines containing `name=`.
- mg4.80 car test: music resend requests now reach the iPhone (the music stream's SETUP carries `controlPort`; call and Siri streams don't). Hands-free release works. Steering-wheel raw codes confirmed: 297 volume up, 298 volume down, 299 previous, 300 next, 3 home.

# MG4CPlay 0.2.9-mg4.80 (SWI165 fork), 2026-11-01

- New **MG4 vehicle > Directions on the dashboard (test)** (SWI165, off by default). Sends CarPlay's next turn, distance, street, remaining distance and time to the instrument cluster's navigation area. Read from the car's own APKs: the cluster takes navigation from `com.saicmotor.adapterservice`, whose exported `MapService` accepts the sat-nav's guidance (`guideStatusChange`, `guideInfosChange`, `roadInfoChange`, `remainingDistanceChange`, `remainingTimesChange`) and relays it to the cluster app and the cluster ECU. CarPlay maneuver types are mapped to the cluster's own turn icons. The stock CarPlay app does not do this on SWI165. Each turn change is logged (`Cluster nav:`).

# MG4CPlay 0.2.9-mg4.79 (SWI165 fork), 2026-11-01

- Log every Android display once per session (id, name, size, density, flags, whether it accepts presentations), to find out whether the SWI165 instrument cluster is an Android display. If it is, CarPlay's own instrument-cluster map could be shown there, as DiPlay already does on BYD.

# MG4CPlay 0.2.9-mg4.78 (SWI165 fork), 2026-11-01

- Steering-wheel keys, from the car's SystemUI (`PhysicalKeysBroadcastReceiver`): SystemUI receives raw key codes (297 volume up, 298 volume down, 299 previous, 300 next, 301 mute, 291 phone, 286 and 17 the custom keys, 288 HVAC, 3 home) and reformats the wheel rocker to KEY_CODE 1 to 5 in the wheel's default mode (6 to 10 and 11 to 15 in its other modes). DiPlay now also logs every raw key (`Wheel raw key:`), so the voice button's code can be identified.
- With **Calls over CarPlay only** on, a short press of the steering-wheel phone key answers or ends the CarPlay call (CarPlay telephony hook switch).

# MG4CPlay 0.2.9-mg4.77 (SWI165 fork), 2026-11-01

- New **Display and performance > Colour range (test)**: from the video (default), limited (video range) or full (PC range). Whites still look washed out next to USB CarPlay with both HEVC and H.264, so the codec isn't the cause. The likeliest remaining cause is the colour range the car's video plane assumes: limited-range video shown as full range gives grey blacks and dim whites. The choice is passed to the decoder with BT.709 and SDR transfer, and logged on the decoder line (`colourRangeHint=`) next to the decoder's own output colour.

# MG4CPlay 0.2.9-mg4.76 (SWI165 fork), 2026-11-01

- **Efficient video (HEVC)** is back in Display and performance on MG4, still on by default. Whites still look slightly washed out next to USB CarPlay, and the stock AllGo app (read from its APK) decodes H.264 with a plain decoder format, while DiPlay uses HEVC. Turning the switch off sends H.264 like the stock app, to compare. The log's video output line records the colour standard, range and transfer for each.

# MG4CPlay 0.2.9-mg4.75 (SWI165 fork), 2026-11-01

- **Square corners** is now on by default on SWI165 (confirmed on the car in mg4.70) and no longer marked as a test. Other firmware keeps view areas. A saved choice still wins.
- Video stall watchdog: no keyframe requests while CarPlay is hidden behind another app. mg4.71 logged one every ~10 s in that state, each making the iPhone send a full frame nobody could see. A keyframe is still requested when CarPlay comes back.

# MG4CPlay 0.2.9-mg4.74 (SWI165 fork), 2026-11-01

- Ask the iPhone to resend lost audio packets. The mg4.71 counters showed every resend request was skipped because DiPlay didn't know the iPhone's control port; it now takes it from the audio stream's SETUP when given. The log records the request keys and the port.
- New **Audio routing > Microphone (test)** choice: Automatic (default: call processing for calls, voice recognition for Siri), call processing for calls and Siri, or the plain microphone. For comparing how well Siri and callers hear you; the microphone line in the log shows the source used.
- mg4.71 car test (14:01 to 14:16): music, calls and Siri audio arrived almost clean (call and Siri streams lost nothing; music lost a few short bursts). The car's UDP and hotspot counters stayed at zero, so the loss that remains is between the iPhone and the car.

# MG4CPlay 0.2.9-mg4.73 (SWI165 fork), 2026-11-01

- Microphone diagnostics: every 5 s while the microphone runs (calls and Siri), the log records frames sent, audio time against real time, Opus encode time (average and worst), the longest wait for the microphone and the bitrate. If encoding can't keep up on the head unit, the delay grows over a call, and this line shows it.
- Software Opus encoder effort lowered (complexity 5 to 3) so each 20 ms frame encodes well within real time. mg4.70 testing found the microphone laggy compared with USB CarPlay.

# MG4CPlay 0.2.9-mg4.72 (SWI165 fork), 2026-11-01

- New **Audio routing > Calls over CarPlay only** switch (off by default). With Wi-Fi audio, it also disconnects the car's hands-free (HFP) link to the iPhone after handoff, as factory wireless CarPlay does, so the iPhone can't send calls over Bluetooth. The car's own phone screen and call button don't work while CarPlay runs. DiPlay asks the car to reconnect hands-free when CarPlay closes. Each release and reconnect is logged.
- Fix the mg4.71 build: the About screen's update section referred to the settings screen's MG4 check.

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
