#!/usr/bin/env python3
"""Converts the Lee Perry-Smith head scan (glTF) into the app's compact mesh asset.

Usage: python3 tools/make_head.py LeePerrySmith.glb app/src/main/assets/head.bin
Source: three.js examples (examples/models/gltf/LeePerrySmith/LeePerrySmith.glb).
Model: "Lee Perry-Smith" head scan by Infinite Realities (Lee Perry-Smith), CC BY 3.0.

Output (little endian): b'HEAD', int32 vertexCount, int32 indexCount,
  vertexCount x (int16 x, y, z quantised to [-2.5, 2.5]; int8 nx, ny, nz; int8 pad),
  indexCount x uint16.
The bust is scaled so the head is ~1.5 units wide at the ears, with the ears at y = 0 and the
face looking toward +z (y up).
"""
import json, struct, sys

src, dst = sys.argv[1], sys.argv[2]
d = open(src, 'rb').read(); off = 12; chunks = []
while off < len(d):
    cl, ct = struct.unpack('<I4s', d[off:off + 8]); chunks.append(d[off + 8:off + 8 + cl]); off += 8 + cl
j = json.loads(chunks[0]); b = chunks[1]

def acc(i):
    a = j['accessors'][i]; bv = j['bufferViews'][a['bufferView']]
    o = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
    n = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3}[a['type']]; f = {5126: 'f', 5123: 'H', 5125: 'I'}[a['componentType']]
    cnt = a['count'] * n
    return list(struct.unpack('<%d%s' % (cnt, f), b[o:o + cnt * struct.calcsize(f)]))

prim = j['meshes'][0]['primitives'][0]
idx = acc(prim['indices']); pos = acc(prim['attributes']['POSITION']); nor = acc(prim['attributes']['NORMAL'])
P = [pos[i:i + 3] for i in range(0, len(pos), 3)]; N = [nor[i:i + 3] for i in range(0, len(nor), 3)]

# Ears: the widest points of the head (upper part of the model, away from the shoulders).
top = max(p[1] for p in P); bottom = min(p[1] for p in P)
head = [p for p in P if p[1] > bottom + 0.55 * (top - bottom)]
left = max(head, key=lambda p: p[0]); right = min(head, key=lambda p: p[0])
ear_y = (left[1] + right[1]) / 2; ear_z = (left[2] + right[2]) / 2
scale = 1.5 / (left[0] - right[0])
print('ears at y=%.2f z=%.2f, width %.2f -> scale %.3f' % (ear_y, ear_z, left[0] - right[0], scale))

Q = 2.5
out = bytearray(b'HEAD') + struct.pack('<ii', len(P), len(idx))
lo = [9] * 3; hi = [-9] * 3
for p, n in zip(P, N):
    v = [p[0] * scale, (p[1] - ear_y) * scale, (p[2] - ear_z) * scale]
    for k in range(3): lo[k] = min(lo[k], v[k]); hi[k] = max(hi[k], v[k])
    q = [max(-32767, min(32767, round(c / Q * 32767))) for c in v]
    m = [max(-127, min(127, round(c * 127))) for c in n]
    out += struct.pack('<hhhbbbb', *q, *m, 0)
out += struct.pack('<%dH' % len(idx), *idx)
open(dst, 'wb').write(out)
print('wrote', dst, len(out), 'bytes; bounds', [round(x, 2) for x in lo], [round(x, 2) for x in hi])
