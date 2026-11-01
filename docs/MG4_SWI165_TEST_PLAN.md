# SWI165 release test plan: calls, music, Bluetooth and Wi-Fi

For checking a build on the car before it's called a release. Written for mg4.84.

## Before you start

| Item | Setting |
|---|---|
| Build | The version shown on DiPlay's About page matches the one you built |
| CarPlay audio over Wi-Fi | On |
| Calls over CarPlay only | On (turn off only for section E) |
| Microphone (test) | Automatic, unless the step says otherwise |
| iPhone | Bluetooth on, paired to the car, Low Power Mode off, on the mount |
| Car | Fully off for a few minutes first, so each run starts from a cold start |

Note the time at each step, so it can be matched to the log. Save one diagnostic report at the
end, wait a few seconds, then copy it off.

## A. Start-up

| # | Step | Pass | Log lines to check |
|---|---|---|---|
| A1 | Start the car, open DiPlay, wait for CarPlay | Picture within about 10 s | `AirPlay session active`, `first frame rendered` |
| A2 | Check what's connected over Bluetooth on the iPhone | Car shows as CarPlay only, not as a Bluetooth audio or phone device | `A2DP sink release ... result=true`, `Hands-free release ... result=true` |
| A3 | Wait 60 s | Still nothing on Bluetooth audio or phone | Any `hands-free reconnected ... releasing again` lines (fine if present: the watcher worked) |

## B. Music

| # | Step | Pass | Log lines |
|---|---|---|---|
| B1 | Play music for 2 minutes | No stutter, no loud clipping | `audio stats ... underruns=0`, `Audio loss ... resendRequests` (resends being sent is good) |
| B2 | Wheel next, previous | Track changes once per press | `Wheel key: code=5/4 ... -> CarPlay` |
| B3 | Wheel volume up and down, then mute | Volume changes; mute silences | Raw codes 297, 298, 301 |
| B4 | Pause on the iPhone, wait 30 s, play | Resumes within 2 s | New `Audio: playback started` |
| B5 | Car's own radio button, then back to CarPlay | Radio stops; CarPlay music comes back | `car source:` lines |

## C. Calls (CarPlay)

| # | Step | Pass | Log lines |
|---|---|---|---|
| C1 | Outgoing call from CarPlay, 30 s, count out loud | Both sides hear each other; no echo; delay under about a second | `audioType=telephony`, `Microphone telephony: started ... opus=software`, `Microphone telephony stats ... encodeAvgUs` under 10000 |
| C2 | Same call: does the iPhone show the car as CarPlay, not Bluetooth? | CarPlay | No `mode=2` in `Audio environment` |
| C3 | Incoming call while music plays | Rings through the car speakers; music pauses | Look for an `alert` or `telephony` stream and whether it played (see risk R1) |
| C4 | Answer with the steering-wheel phone key | Answers | `Wheel raw key: code=291 ... -> CarPlay call` |
| C5 | End with the phone key | Hangs up | Same |
| C6 | Music after the call | Resumes on its own | `audioType=media` restarts |
| C7 | Call with music paused, then start music mid-call | Call keeps priority; music stays quiet | Focus lines |

## D. Siri and Maps

| # | Step | Pass | Log lines |
|---|---|---|---|
| D1 | Voice button on the wheel | Siri opens | Raw code for the voice button (not yet known) |
| D2 | "What's the weather in Geelong", three times per Microphone (test) option | Siri hears it correctly | `Microphone speechrecognition: started source=...` |
| D3 | Start an Apple Maps route; wait for a spoken prompt | Prompt is heard; music lowers, then comes back | `audioType=default` or `alert` stream, and whether it played (see risk R1) |
| D4 | Dashboard (Directions on the dashboard on) | Next turn, distance, street on the cluster | `Cluster nav: guidance started`, `Cluster nav: apple=...` |

## E. Bluetooth fall-backs and recovery

| # | Step | Pass | Log lines |
|---|---|---|---|
| E1 | Close DiPlay (home, or swipe away) during music | Car's Bluetooth phone reconnects within about 30 s | `hands-free reconnect requested` |
| E2 | Close DiPlay, then make a call | Call goes over Bluetooth hands-free | n/a |
| E3 | Turn the car off and on again, open DiPlay | CarPlay returns; A1 to A3 pass again | |
| E4 | Calls over CarPlay only off, make a call | Note where it goes (CarPlay or Bluetooth) | `mode=` and stream lines |
| E5 | CarPlay audio over Wi-Fi off (Bluetooth Music mode), play music | Plays over the car's Bluetooth Music | `car source: ... switched`, `source=5 (bluetooth)` |

## F. Wi-Fi stress

| # | Step | Pass | Log lines |
|---|---|---|---|
| F1 | Put the iPhone in a pocket or bag for 2 minutes of music | Short skips at most | `Audio loss`: `ap0RxDrop` and `udpRcvbufErrors` stay `+0` (loss in the air, not in the car) |
| F2 | Turn the iPhone's Wi-Fi off for 10 s, then on | CarPlay reconnects by itself within about 30 s | `transport error`, `Reconnecting`, new `AirPlay session active` |
| F3 | During a call, walk 10 m from the car and back | Call carries on over the iPhone, or recovers | Note what happened |
| F4 | Car's own hotspot or Wi-Fi setting changed | n/a: note only | `wireless hotspot backend` line |
| F5 | mg4.87: switch to **Wi-Fi Direct** (car hotspot off, car Wi-Fi on), band **Automatic**, then connect | CarPlay starts; note the band. If it stops with "Wi-Fi Direct is switched off", note it and go back to Car hotspot | `Wi-Fi P2P legacy state=`, `fiveGhzCapable=`, `Wi-Fi P2P legacy plan`, `setChannels`, `Wi-Fi P2P ready mode=LEGACY band=` |
| F6 | If F5 fails or is on 5 GHz and stutters, set band **2.4 GHz only** and repeat | CarPlay starts on 2.4 GHz | `band=2.4 GHz` |
| F7 | Repeat B (music) and C (calls) on Wi-Fi Direct and compare with the car hotspot | Fewer skips and less call static than the hotspot | `Audio loss` lines and `call loss packets=` at hang-up, for both modes |

## Known risks to check in code before release

| # | Risk | Why | What to check |
|---|---|---|---|
| R1 | Ringtone and Maps prompts may be silent | They use the navigation channel, which plays as `USAGE_*` audio, not the music stream. Calls were silent on this firmware until they were moved to the music stream | C3 and D3. If silent, route `alert` and `default` to the music stream in Wi-Fi mode, as calls already are |
| R2 | Hands-free given back on every DiPlay close | Leaving CarPlay closes the session (`Activity destroyed`) and reconnects hands-free, then the next session releases it again | E1. If it flaps, keep the session alive in the background instead |
| R3 | No call audio if Wi-Fi drops mid-call | Hands-free is released, so the iPhone has nowhere to send the call except its own earpiece | F3. The iPhone should fall back to its earpiece; confirm |
| R4 | Call and Siri streams can't ask for resends | The iPhone doesn't send a control port for these streams | mg4.84 hides call gaps up to 200 ms (FEC, then concealment); check `call loss` at hang-up |
| R5 | Bluetooth music doesn't come back after CarPlay | A2DP is released and not reconnected on close | E1: check whether Bluetooth music needs a manual reconnect |
| R6 | Siri mishears | Microphone source or processing | D2 results per option |

## What to send back

- The diagnostic report.
- The times of each step, with anything that failed.
- For D2: what Siri heard each time, for each Microphone (test) option.
