# Third-party notices

The source code in this repository is MIT-licensed (see `LICENSE`). Two data files bundled with
the app come from elsewhere and keep their own terms.

## HRTF measurements: `app/src/main/assets/hrtf_kemar.bin`

Derived from the diffuse-field equalised set of *HRTF Measurements of a KEMAR Dummy-Head
Microphone* by Bill Gardner and Keith Martin, MIT Media Lab (1994),
<https://sound.media.mit.edu/resources/KEMAR.html>.

> This data is Copyright 1994 by the MIT Media Laboratory. It is provided free with no
> restrictions on use, provided the authors are cited when the data is used in any research or
> commercial application.

Only the horizontal-plane magnitudes are used, smoothed and resampled by `tools/make_hrtf.py`.

## Head model: `app/src/main/assets/head.bin`

"Lee Perry-Smith" head scan by Infinite Realities (Lee Perry-Smith), licensed under
[Creative Commons Attribution 3.0](https://creativecommons.org/licenses/by/3.0/), taken from the
three.js examples (`examples/models/gltf/LeePerrySmith/LeePerrySmith.glb`). The geometry was
rescaled, re-centred and quantised by `tools/make_head.py`; textures are not used.

## Libraries

Pulled in by Gradle at build time, not vendored here:

- AndroidX Core, Lifecycle, CameraX, DynamicAnimation: Apache License 2.0
- Kotlin standard library: Apache License 2.0
- ML Kit face detection (`com.google.android.gms:play-services-mlkit-face-detection`):
  Google's ML Kit terms, <https://developers.google.com/ml-kit/terms>. The face model is delivered
  by Google Play services on the device.
