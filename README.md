# Head Tracker: AR-style head-tracked 3D audio for any earbuds

A small (~1.4 MB) Android app that gives earbuds with no motion sensors, such as the Anker
Soundcore P30i, head-tracked spatial audio. Every instrument, voice and effect in a song stays at
its own fixed spot **in the room**. Turn your head and the whole scene stays put, including when
it ends up behind you. It runs in the background and works with the players you already use.

## Two audio modes

| | **Immersive 3D** (default) | **Light** |
|---|---|---|
| How | Captures the player's audio, mutes the original, and plays a fully re-rendered binaural version | Per-ear EQ applied in place to the player's audio |
| What you get | Every sound gets its own direction, rendered with measured human-ear responses (HRTFs), correct ear-timing differences, front/back cues and a small virtual room. Surround (5.1/7.1) plays through virtual speakers | A balance and tone shift between the ears |
| Added delay | About 25 ms of processing plus the capture/playback buffers (head prediction makes up for it) | None |
| Best for | Music, anything where you want the AR feeling | Video, if you notice lip-sync |

### Immersive 3D renderer

- **Stereo → 3D up-mix:** this works per frequency band (~47 Hz wide), updated every 5.3 ms.
  - The correlated part of each band becomes a **direct source** at the direction given by its
    L/R panning. So the guitar panned left, the vocal in the centre and the hi-hat on the right
    each keep their *own* spot on a 80°-wide stage (adjustable).
  - The uncorrelated part (reverb, room, wide pads) and anti-phase content become **ambience**
    placed to the sides and behind (±110°).
  - Sound that is only in one channel is treated as a hard-panned instrument, not as ambience.
- **5.1 / 7.1 input:** detected automatically and rendered as virtual speakers at the standard
  angles (L/R ±30°, C 0°, sides ±90°, rears ±115–145°).
- **HRTF:** the MIT KEMAR measured, diffuse-field-equalised horizontal-plane responses, plus
  interaural time difference from Woodworth's spherical-head formula, interpolated per degree.
- **Virtual room:** 8 early reflections, each at a real direction in the room and rotating with
  your head, plus a short diffuse tail. This is what moves sound *out* of your head.
- **Latency compensation:** the app measures the real Bluetooth delay and renders for where your
  head *will be* when the sound reaches your ears, up to 150 ms ahead.
- **Cost:** about 50× faster than real time on a desktop JVM, so a small fraction of one phone CPU
  core.

## Head tracking

| Source | How |
|---|---|
| **Face** (default) | Front camera + ML Kit fast face detector on 320×240 frames at 24 fps gives head yaw relative to the phone. **World lock** adds the phone's gyro heading, so the scene stays fixed in the room even when the phone moves. |
| **Phone motion** | Gyro only. Assumes the phone turns with your body (pocket). No camera. |

Smoothing pipeline: One Euro filter → velocity prediction (hides camera latency) → gyro fusion →
100 Hz control loop with a slew limit. **Auto-recenter** lets the scene slowly follow you when you
settle into a new direction, like AirPods do. If your face is lost, the last pose is held briefly,
then the scene glides back to the front.

## Install and use

1. Download the APK from the **Actions** tab (artifact `head-tracker-apk`), or build it with
   `./gradlew assembleRelease`.
2. Open **Head Tracker** → **Start head tracking**. Allow camera, audio capture and notifications.
   When Android asks to "start recording or casting", choose **Entire screen**. Only audio is
   used, nothing is recorded or stored, and nothing leaves the phone.
3. **Turn off Dolby Atmos / spatial / 3D effects** in your phone's sound settings while Immersive
   3D is on, so the sound isn't virtualised twice.
4. Start tracking *before* pressing play, or pause and resume once. The status lines show the
   render mode, the measured latency, and per app whether it is in **3D** or **light**.
5. Turn your head and check the on-screen indicator. If it moves the wrong way, turn on
   **Invert direction**. Use **Recenter** to set "front".

### Optional: catch every player

Players that announce their audio session (Spotify, Poweramp, VLC, YouTube Music, Media3/ExoPlayer
apps and others) work automatically. To also catch players that don't, connect the phone to a
computer once and run:

```
adb shell pm grant dev.headtracker android.permission.DUMP
```

The app then finds every playing app's audio session by itself.

Requires Android 10+ and Google Play services (for the face model).

## Safety and fallbacks

- A player's original audio is muted **only** after capture of that app is confirmed working, so
  you never hear it twice.
- Some apps forbid capture. If muting one leads to silence, the app detects it within about 2.5 s,
  un-mutes it and uses Light mode for that app from then on.
- If capture can't start at all, everything falls back to Light mode.
- Unplug or disconnect the earbuds and every app is un-muted at once. If the app is stopped or
  killed, Android removes its effects automatically.

## Honest limitations

- A generic (KEMAR) HRTF isn't *your* ears. Front/back and elevation cues are weaker than with a
  personalised one. Head tracking itself is the strongest front/back cue, which is the point of
  this app.
- The stereo up-mix guesses each sound's direction from its panning. Dense mixes can make sources
  blur slightly together.
- Height (Atmos elevation) isn't reproduced. Only yaw (turning left/right) is tracked.
- Face mode needs the phone to see you.
- Apps that block audio capture get Light mode only. The DUMP grant doesn't change that.

## Credits

HRTF data: Bill Gardner and Keith Martin, MIT Media Lab, *HRTF Measurements of a KEMAR Dummy-Head
Microphone* (1994). `tools/make_hrtf.py` converts it into `app/src/main/assets/hrtf_kemar.bin`.
