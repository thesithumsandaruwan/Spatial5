# Spatial5: AR-style head-tracked 3D audio for any earbuds

A small (~1.7 MB) Android app that gives earbuds with no motion sensors, such as the Anker
Soundcore P30i, head-tracked spatial audio. Every instrument, voice and effect in a song stays at
its own fixed spot **in the room**. Turn your head and the whole scene stays put, including when
it ends up behind you. It runs in the background and works with the players you already use.

## Two audio modes

| | **Immersive 3D** (default) | **Standard** |
|---|---|---|
| How | Captures the player's audio, mutes the original, and plays a fully re-rendered binaural version | Per-ear EQ applied in place to every app (global effect) |
| What you get | Every sound gets its own direction, rendered with measured human-ear responses (HRTFs), correct ear-timing differences, front/back cues and a small virtual room. Surround (5.1/7.1) plays through virtual speakers | A balance and tone shift between the ears |
| Added delay | ~10 ms of processing plus the audio buffers (head prediction makes up for it) | None |
| Permission | Android's "share screen" prompt (audio only), **asked once**: Pause keeps it, only Turn Off or a reboot ends it | None |
| Dolby Atmos | Turn it **off** (otherwise the sound is virtualised twice) | Keep it **on**: Dolby places the sound around you, Spatial5 moves it with your head |

**Every app is covered, with no adb.**
- **Apps that announce their audio session** (YouTube Music, Poweramp, VLC, Media3 players…)
  get Immersive 3D straight away.
- **Players that don't announce their session**, or that started before Spatial5, are found by a
  scan. Android hands out session IDs from one increasing counter, so a passive Visualizer on the
  most recent ones shows which is playing. A session must be playing in two scans about 2 s
  apart, and the scan only runs while an unexplained *media* player is active.
  - The **owning app is then identified** with short test captures over the installed media apps
    (players seen before first), halving the set each time (~6 tests for 40 apps).
  - Then it goes through the normal per-app 3D path. Advanced → Diagnostics → *events* shows each
    step.
- **Apps that block capture** are un-muted within ~1.5 s, switched to Standard (the global
  effect) and remembered.
- **Apps that produced 3D audio once** are remembered as verified, so a quiet gap between songs
  never switches them out. That was the cause of the lag/jitter at song changes, along with:
  - the global effect being created and destroyed during gaps (it's now bypassed instead);
  - the output restarting after 3 s of silence (now 8 s, and the latency measurement resets
    correctly).

### Sound quality

The 3D render is built to sound like the original song, only placed around you:

- **Timbre lock:** while you face forward, a smooth 1/3-octave EQ is learned that makes the 3D
  output's tonal balance match the original. It freezes while you look away, so the natural
  "darker from behind" cue is kept.
- **Bass passes through untouched** (below ~150 Hz): no boom, no phasiness.
- **512-point STFT** (10.7 ms windows), so drums stay sharp.
- **Smoothed per-bin decisions,** so there's no swirly "musical noise".
- **Look-ahead limiter with 1 dB of headroom** instead of hard clipping.
- **Self-adjusting output buffer,** so there's no crackle.
- **High-passed room:** the room only adds mids and highs, so it adds space without mud.

Measured, with an offline test against the original signal:

| | Previous version | Now |
|---|---|---|
| Tonal deviation, 63 Hz – 12.5 kHz | +3.1 dB (250 Hz) … −4.9 dB (2 kHz) | **±0.1 dB** |
| 1 kHz tone, worst harmonic | — | **−81.5 dB** |
| 60 Hz bass level | rendered | **passes through (−1 dB headroom)** |

### Auto-Tune

The app continuously measures each song's stereo width and how reverberant it already is:
- **Narrow mixes** get a slightly wider stage.
- **Dry recordings** get more room, so they leave your head.
- **Reverberant or bass-heavy songs** get less room, so they stay clean.
- **Surround** uses a fixed, light room.

Changes glide in over a few seconds. Turn Auto-Tune off to set Stage Width and Room yourself.

### Immersive 3D renderer

- **Stereo → 3D up-mix:** this works per frequency band, updated every 2.7 ms.
  - The correlated part of each band becomes a **direct source** at the direction given by its
    L/R panning, so each instrument keeps its own spot on the stage.
  - The uncorrelated part (reverb, wide pads) becomes **ambience** to the sides and behind
    (±110°).
- **5.1 / 7.1 input:** detected automatically and rendered as virtual speakers.
- **HRTF:** MIT KEMAR measured, diffuse-field-equalised responses, plus Woodworth interaural
  time difference.
- **Virtual room:** 8 head-tracked early reflections plus a short diffuse tail.
- **Latency compensation:** the measured Bluetooth delay is predicted away (up to 150 ms).

## Head tracking

| Source | How |
|---|---|
| **Face** (default) | Front camera + ML Kit fast face detector on 320×240 frames at 24 fps gives head yaw relative to the phone. **World lock** adds the phone's gyro heading, so the scene stays fixed in the room even when the phone moves. |
| **Phone motion** | Gyro only. Assumes the phone turns with your body (pocket). No camera. |

**Low light** (dark rooms, movie nights):
- **Longer exposures:** the camera may use a variable frame rate, so auto-exposure can lengthen
  frames (a fixed 24 fps capped them at 1/24 s).
- **Exposure follows your face:** it meters on your face once found, and adds +1 EV when the
  scene is dark.
- **Contrast boost:** dark frames go to the detector as a contrast-stretched, gamma-lifted grey
  image (1st–99th percentile stretch).
- **Sharper, more distant faces:** analysis runs at 640×480 and accepts faces down to 10% of the
  frame.
- **Robust re-acquire:** after repeated misses, it alternates with ML Kit's slower "accurate"
  model until it finds you.

The status shows "Low light" while the boost is active.

Smoothing pipeline: One Euro filter → velocity prediction (hides camera latency) → gyro fusion →
100 Hz control loop with a slew limit. **Auto-recenter** (off by default) lets the scene slowly follow
you when you settle into a new direction. Recenter by hand with the button in the app or the
notification. If your face is lost, the last pose is held briefly,
then the scene glides back to the front.

## Install and use

1. Download the APK from the **Actions** tab (artifact `head-tracker-apk`), or build it with
   `./gradlew assembleRelease`.
2. Open **Spatial5** → **Start head tracking**. Allow camera, audio capture and notifications.
   When Android asks to "start recording or casting", choose **Entire screen**. Only audio is
   used, nothing is recorded or stored, and nothing leaves the phone.
3. **Turn off Dolby Atmos / spatial / 3D effects** in your phone's sound settings while Immersive
   3D is on, so the sound isn't virtualised twice.
4. Start tracking *before* pressing play, or pause and resume once. **Now Playing** shows the
   render mode, the measured latency, and per app whether it is in **3D** or **Standard**.
5. Turn your head and check the on-screen indicator. If it moves the wrong way, turn on
   **Invert direction**. Use **Recenter** to set "front".

Requires Android 10+ and Google Play services (for the face model).

## Movies (Cinema profile)

**Auto** (default) switches to Cinema whenever the screen is in landscape, including when a
video app forces it with auto-rotate off. You can also set it to **Always** or **Off**. In Cinema:
- **Sound is anchored to the screen**, not the room, as Apple does for video. Auto-recenter is
  off, and "front" is re-learned from your face when the phone turns.
- **Dialogue is locked to the screen:** near-centre content is pinned dead centre.
- **Front stage is screen-wide** (90°), and effects and ambience spread wider and further back
  (±120°).
- **The room is very dry**, because films carry their own acoustics.
- **Delay is lower:** the output buffer starts at 20 ms (it still grows by itself if the phone
  stutters) to keep lip-sync close.

Face tracking works in any orientation: camera frames are rotated to match the screen as it
turns.

## Standard mode

With no capture, only per-ear level and tone can change, so Standard is tuned to move Dolby's
virtual speakers:
- a saturating balance shift: ~6 dB at a 30° turn, ~8 dB at 60°, mostly by turning down the far
  side;
- per-ear head-shadow EQ from the spherical-head model;
- high-frequency darkening when the scene is behind you.

It can't add the interaural time difference or move individual instruments; that needs Immersive
3D.

## The app

A dark/light adaptive, iOS-style interface:
- a live **3D dummy head**: a real human head-and-shoulders scan, rendered as a studio-lit white
  mannequin that dissolves into the background below the shoulders, turning with you;
- **speakers fixed in the room** around it that glow with the music;
- **Now Playing** at the top, showing each app as 3D or Standard;
- grouped settings cards, segmented controls and switches;
- critically damped **spring animations** that you can interrupt, plus haptics;
- a responsive layout: side by side in landscape and on tablets, with a centred reading column.

It renders only when something changes, so it costs nothing when idle.

## Safety and fallbacks

- A player is muted **only** after capture of that app is confirmed working, so you never hear it
  twice.
- If muting an app leads to silence (the app blocks capture), it is un-muted within about 1.2 s,
  switched to Standard and remembered.
- If capture can't start at all, everything falls back to Standard.
- Disconnect the earbuds and every app is un-muted immediately. If the app is stopped or killed,
  Android removes its effects automatically.

## Honest limitations

- A generic (KEMAR) HRTF isn't *your* ears. Front/back and elevation cues are weaker than with a
  personalised one. Head tracking itself is the strongest front/back cue, which is the point of
  this app.
- The stereo up-mix guesses each sound's direction from its panning. Dense mixes can make sources
  blur slightly together.
- Height (Atmos elevation) isn't reproduced. Only yaw (turning left/right) is tracked.
- Face mode needs the phone to see you.
- Apps that block audio capture get Standard mode only.

## Credits

HRTF data: Bill Gardner and Keith Martin, MIT Media Lab, *HRTF Measurements of a KEMAR Dummy-Head
Microphone* (1994). `tools/make_hrtf.py` converts it into `app/src/main/assets/hrtf_kemar.bin`.

Head model: "Lee Perry-Smith" head scan by Infinite Realities, licensed CC BY 3.0 (via the three.js
examples). `tools/make_head.py` converts it into `app/src/main/assets/head.bin`.
