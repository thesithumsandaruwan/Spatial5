# Head Tracker: head-tracked spatial audio for any earbuds

A small (~1.3 MB) Android app that adds **head tracking** to earbuds that don't have motion
sensors, such as the Anker Soundcore P30i. It runs in the background and works with the players
you already use, on top of your phone's own Dolby Atmos.

When you turn your head, the soundstage stays put (anchored to your phone, like "fixed to device"
spatial audio). Turn left and the sound shifts toward your right ear, and the far ear gets the
duller, head-shadowed sound it would get in real life.

## How it works

The earbuds have no IMU, so the app tracks your head another way:

| Source | What it measures | Best for |
|---|---|---|
| **Face** (default) | Front camera + ML Kit fast face detector on 320×240 frames gives head yaw *relative to the phone*. The phone's gyro fills in between frames and cancels phone movement. | Watching or listening while the phone is in front of you (hand, desk, stand) |
| **Phone motion** | Gyro-only rotation vector. Assumes the phone turns with your body, and re-centres slowly like AirPods do. No camera. | Phone in a pocket, walking around |

### Smoothing pipeline

camera (15 fps) → One Euro filter (low jitter, low lag) → velocity prediction (hides about 60 ms of
camera latency) → gyro correction → 50 Hz control loop with a slew limit → audio parameters.
If your face is lost, the last pose is held briefly, then glides back to centre. There are never
jumps.

### Audio output

Every update turns the head-relative azimuth into per-ear changes with the **Brown–Duda
spherical-head model**:
- a 9-band EQ per ear, using the *difference* between the head shadow at the new angle and at 0°
  (so a centred head is exactly neutral)
- a small broadband level shift that stands in for the interaural time difference

These are applied with Android's built-in `DynamicsProcessing` effect, which also has a limiter so
nothing clips. It is native DSP inside the audio server: the app adds no audio latency and does no
audio processing of its own.

### How it reaches "any player"

1. **Per-player (default):** most players (Spotify, Poweramp, VLC, YouTube Music, Media3 or
   ExoPlayer-based apps, and others) announce their audio session for equaliser apps. The app
   attaches to each session as it opens.
2. **Global mode (toggle):** attaches to the output mix (session 0), for players that don't
   announce a session. It works on many devices but not all (Android deprecated it).

### Battery

Everything sleeps unless media is **actually playing** on **headphones**. In that case the camera is
off, sensors are off and there are no timers. Face detection drops to about 3 fps while it searches
for a face. By default the camera also pauses when the screen is off.

## Install and use

1. Download the APK from the **Actions** tab (the `head-tracker-apk` artifact), or build it with
   `./gradlew assembleRelease`. The file is `app/build/outputs/apk/release/app-release.apk`.
2. Open **Head Tracker**, tap **Start head tracking**, and allow camera and notifications.
3. **Start the tracker before you press play**, or pause and resume your player once, so the app
   can catch the player's audio session. The "Audio:" line shows what is attached.
4. Turn your head and check the indicator. If it moves the wrong way, turn on **Invert direction**.
5. Use **Recenter** (also in the notification) to set "front".
6. If your player isn't listed under "Audio:", turn on **Global mode**.

Requires Android 9+ and Google Play services (for the face model).

## Honest limitations

- **This is an approximation, not AirPods-grade spatial audio.** Apple re-renders each Atmos
  object with full HRTFs inside the decoder. Apps can't reach inside Android's Dolby renderer, and
  the system head-tracking API (`Spatializer`) only accepts sensors in a headset. This app reshapes
  the stereo going into Dolby instead. It gives a convincing, stable "the sound stays there" cue
  for turns of about ±60°. Front and back are not re-rendered, and there is no true delay
  (time-difference) shift.
- Face mode needs the phone to see you, and it tracks yaw (left and right turning) only.
- Apps that send Dolby Atmos as a raw bitstream straight to a hardware decoder can't be processed.
- Another equaliser app that uses `DynamicsProcessing` on the same session can conflict with this
  one.
- Some phones block camera access when the screen is off.
