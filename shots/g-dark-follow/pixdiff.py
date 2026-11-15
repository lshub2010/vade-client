import struct, zlib, sys

def read_png(path):
    """Minimal PNG reader for screencap output (8-bit RGB/RGBA, no interlace)."""
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', 'not a png'
    pos, idat, w, h, bitd, color = 8, b'', 0, 0, 0, 0
    while pos < len(data):
        ln = struct.unpack('>I', data[pos:pos+4])[0]
        typ = data[pos+4:pos+8]
        chunk = data[pos+8:pos+8+ln]
        if typ == b'IHDR':
            w, h, bitd, color = struct.unpack('>IIBB', chunk[:10])
        elif typ == b'IDAT':
            idat += chunk
        pos += 12 + ln
    raw = zlib.decompress(idat)
    ch = {0: 1, 2: 3, 4: 2, 6: 4}[color]
    stride = w * ch
    out = bytearray(w * h * ch)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 0:
            pass
        elif f == 1:
            for i in range(ch, stride):
                line[i] = (line[i] + line[i-ch]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                b = prev[i]
                c = prev[i-ch] if i >= ch else 0
                pp = a + b - c
                pa, pb, pc = abs(pp-a), abs(pp-b), abs(pp-c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        else:
            raise ValueError('filter %d' % f)
        out[y*stride:(y+1)*stride] = line
        prev = line
    return w, h, ch, out

def compare(path_a, path_b, top_skip=130, bottom_skip=60):
    wa, ha, ca, A = read_png(path_a)
    wb, hb, cb, B = read_png(path_b)
    assert (wa, ha) == (wb, hb), 'size mismatch %s vs %s' % ((wa, ha), (wb, hb))
    assert ca == cb, 'channel mismatch'
    maxd, sad, changed, n = 0, 0, 0, 0
    rows_max = {}
    for y in range(top_skip, ha - bottom_skip):
        base = y * wa * ca
        rowmax = 0
        for i in range(base, base + wa * ca):
            d = abs(A[i] - B[i])
            sad += d
            if d > maxd: maxd = d
            if d > rowmax: rowmax = d
            if d > 8: changed += 1
            n += 1
        rows_max[y] = rowmax
    worst_rows = sorted(rows_max.items(), key=lambda kv: -kv[1])[:5]
    return dict(size=(wa, ha), max=maxd, mean=round(sad/n, 3), changed_gt8=changed,
                pct=round(100.0*changed/n, 3), worst_rows=worst_rows)

pairs = [
    ('b1-base-light.png',                  'f1-mine-light-sessions.png',        'base vs mine · sessions LIGHT（亮色像素不变 · 排除状态栏/手势条）'),
    ('b1-base-light.png',                  'b2-base-night.png',                 'base light vs base night-yes（审计复现：基线无暗色入口 → 应≈0）'),
    ('f1-mine-light-sessions.png',         'probe-now-night-yes.png',           'mine sessions LIGHT vs DARK（同页实时切换 → 应大幅不同，正证暗色生效）'),
    ('f1-mine-light-sessions.png',         'f5-mine-light-sessions-roundtrip.png', 'mine light vs round-trip light（往返还原 · 排除状态栏时钟）'),
    ('f1-mine-light-sessions.png',         'f2-mine-light-capabilities.png',    '正对照：不同页面 → 必须大幅不同（证明比对方法本身有效）'),
]
for a, b, label in pairs:
    r = compare(a, b)
    print('%-46s max=%3d mean=%6.3f >8:%8d (%5.2f%%) worst_rows=%s' %
          (label, r['max'], r['mean'], r['changed_gt8'], r['pct'], r['worst_rows']))
