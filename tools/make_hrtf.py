#!/usr/bin/env python3
"""Converts the MIT KEMAR diffuse-field-equalised HRTFs (horizontal plane) into the compact
magnitude table used by the app (app/src/main/assets/hrtf_kemar.bin).

Usage:  python3 tools/make_hrtf.py <unzipped diffuse.zip dir> app/src/main/assets/hrtf_kemar.bin
Data: https://sound.media.mit.edu/resources/KEMAR.html  (c) 1994 MIT Media Lab, Bill Gardner &
Keith Martin. Free to use with attribution.

Only magnitudes are stored (1/12-octave smoothed, in dB). The app adds interaural delay from a
spherical-head model, which is smooth and artefact-free when interpolated.
File layout (little endian): b'HRTF', int32 version=1, int32 nAz, int32 nFreq, float32 azStep,
float32[nFreq] freqs, then per azimuth 0..180: float32[nFreq] ipsilateral dB, float32[nFreq] contralateral dB.
"""
import cmath, math, os, struct, sys, wave

src, dst = sys.argv[1], sys.argv[2]
N_FREQ, F_LO, F_HI, AZ_STEP = 128, 40.0, 20000.0, 5
freqs = [F_LO * (F_HI / F_LO) ** (i / (N_FREQ - 1)) for i in range(N_FREQ)]
FFT = 512; SR = 44100

def load(az):
    w = wave.open(os.path.join(src, 'elev0', 'H0e%03da.wav' % az))
    n = w.getnframes(); d = struct.unpack('<%dh' % (2 * n), w.readframes(n))
    # File convention: azimuth is to the right, channel 1 = right ear (ipsilateral).
    return [x / 32768 for x in d[1::2]], [x / 32768 for x in d[0::2]]

def power_spectrum(ir):
    out = []
    for k in range(FFT // 2 + 1):
        s = sum(ir[n] * cmath.exp(-2j * math.pi * k * n / FFT) for n in range(len(ir)))
        out.append(abs(s) ** 2)
    return out

def smooth_db(ps, f):
    lo, hi = f * 2 ** (-1 / 24), f * 2 ** (1 / 24)
    bins = [k for k in range(len(ps)) if lo <= k * SR / FFT <= hi]
    if not bins:  # narrower than one bin: interpolate
        x = f * FFT / SR; k = int(x); t = x - k
        p = ps[k] * (1 - t) + ps[min(k + 1, len(ps) - 1)] * t
    else:
        p = sum(ps[k] for k in bins) / len(bins)
    return 10 * math.log10(max(p, 1e-12))

table = []
for az in range(0, 181, AZ_STEP):
    ipsi_ir, contra_ir = load(az)
    pi, pc = power_spectrum(ipsi_ir), power_spectrum(contra_ir)
    ipsi = [smooth_db(pi, f) for f in freqs]
    contra = [smooth_db(pc, f) for f in freqs]
    # Below ~300 Hz the 128-tap measurement is unreliable: hold the level and let ILD fade to 0.
    ref = next(i for i, f in enumerate(freqs) if f >= 300)
    for i in range(ref):
        mean = (ipsi[ref] + contra[ref]) / 2; half = (ipsi[ref] - contra[ref]) / 2
        w = max(0.0, math.log(freqs[i] / 60) / math.log(300 / 60))
        ipsi[i], contra[i] = mean + half * w, mean - half * w
    table.append((ipsi, contra))

# Normalise: average power over all directions/ears in 200 Hz..8 kHz = 0 dB.
band = [i for i, f in enumerate(freqs) if 200 <= f <= 8000]
acc = [10 ** (e[i] / 10) for row in table for e in row for i in band]
norm = 10 * math.log10(sum(acc) / len(acc))
with open(dst, 'wb') as fo:
    fo.write(b'HRTF'); fo.write(struct.pack('<iiif', 1, len(table), N_FREQ, float(AZ_STEP)))
    fo.write(struct.pack('<%df' % N_FREQ, *freqs))
    for ipsi, contra in table:
        fo.write(struct.pack('<%df' % N_FREQ, *[v - norm for v in ipsi]))
        fo.write(struct.pack('<%df' % N_FREQ, *[v - norm for v in contra]))
print('wrote', dst, os.path.getsize(dst), 'bytes; norm', round(norm, 2), 'dB')
for az in (0, 30, 90, 150):
    i, c = table[az // AZ_STEP]; k = min(range(N_FREQ), key=lambda j: abs(freqs[j] - 4000))
    print('az', az, '4 kHz ipsi %.1f contra %.1f' % (i[k] - norm, c[k] - norm))
