#!/usr/bin/env python3
"""g-shadow-android · pixel criteria for the downward shadow (from UI-tree geometry).

Adapted from shots/g-shadow-real/measure.py; PNG decoded with the stdlib only
(zlib + unfilter — the interpreters on this host have no PIL).

  python measure.py scan <png> <x> <y0> <y1>       # one column, y -> gray
  python measure.py band <png> <x> <top> <bottom>  # above/below criteria report
  python measure.py corner <png> <x0> <x1> <y>     # one row, x -> gray
"""
import struct
import sys
import zlib


class Png:
    """Minimal RGBA8 PNG reader (screencap output: non-interlaced, 8-bit)."""

    def __init__(self, path):
        data = open(path, "rb").read()
        assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
        pos, idat, w, h, bitd, color = 8, bytearray(), 0, 0, 0, 0
        while pos < len(data):
            (length,) = struct.unpack(">I", data[pos:pos + 4])
            ctype = data[pos + 4:pos + 8]
            chunk = data[pos + 8:pos + 8 + length]
            if ctype == b"IHDR":
                w, h, bitd, color = struct.unpack(">IIBB", chunk[:10])
            elif ctype == b"IDAT":
                idat += chunk
            elif ctype == b"IEND":
                break
            pos += 12 + length
        assert bitd == 8, f"bit depth {bitd}"
        nch = {0: 1, 2: 3, 4: 2, 6: 4}[color]
        self.width, self.height, self.nch = w, h, nch
        raw = zlib.decompress(bytes(idat))
        stride = w * nch
        px = bytearray(stride * h)
        prev = bytearray(stride)
        off = 0
        for y in range(h):
            f = raw[off]
            line = bytearray(raw[off + 1:off + 1 + stride])
            off += 1 + stride
            if f == 1:
                for i in range(nch, stride):
                    line[i] = (line[i] + line[i - nch]) & 0xFF
            elif f == 2:
                for i in range(stride):
                    line[i] = (line[i] + prev[i]) & 0xFF
            elif f == 3:
                for i in range(stride):
                    a = line[i - nch] if i >= nch else 0
                    line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
            elif f == 4:
                for i in range(stride):
                    a = line[i - nch] if i >= nch else 0
                    b = prev[i]
                    c = prev[i - nch] if i >= nch else 0
                    p = a + b - c
                    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                    pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                    line[i] = (line[i] + pr) & 0xFF
            px[y * stride:(y + 1) * stride] = line
            prev = line
        self.px = px

    def gray(self, x, y):
        i = (y * self.width + x) * self.nch
        r, g, b = self.px[i], self.px[i + 1], self.px[i + 2]
        return (r + g + b) / 3.0


def scan(im, x, y0, y1):
    return [im.gray(x, y) for y in range(y0, y1 + 1)]


def steps(vals):
    return [abs(vals[i + 1] - vals[i]) for i in range(len(vals) - 1)]


def report_band(im, x, top, bottom, above=40, below=40, canvas=None):
    height = im.height
    above_vals = scan(im, x, max(0, top - above), top - 1)
    below_vals = scan(im, x, bottom + 1, min(height - 1, bottom + below))
    if canvas is None:
        canvas = max(above_vals + below_vals)
    peak_below = max((canvas - v for v in below_vals), default=0.0)
    peak_above = max((canvas - v for v in above_vals), default=0.0)
    mean_above = sum((canvas - v for v in above_vals)) / max(1, len(above_vals))
    lower_levels = sorted({round(v) for v in below_vals}, reverse=True)
    st = steps(below_vals)
    rising = sum(1 for i in range(len(below_vals) - 1) if below_vals[i + 1] < below_vals[i] - 0.6)
    print(f"x={x} top={top} bottom={bottom} canvas={canvas:.1f}")
    print(f"  above: mean_delta={mean_above:.2f} max_delta={peak_above:.1f} first_px={above_vals[-1]:.1f}")
    print(f"  below: peak_delta={peak_below:.1f} levels={len(lower_levels)} "
          f"min={min(below_vals):.1f} max_step={max(st) if st else 0:.1f} levels_list={lower_levels}")
    print(f"  below_vals={[round(v, 1) for v in below_vals]}")
    print(f"  above_vals={[round(v, 1) for v in above_vals]}")
    monotone = rising == 0
    ok = (peak_below <= 8 and (max(st) <= 2 if st else True) and mean_above <= 1.0
          and len(lower_levels) >= 3 and monotone)
    print(f"  criteria: peak<=8:{peak_below <= 8} adjacent<=2:{max(st) <= 2 if st else True} "
          f"upper<=1:{mean_above <= 1.0} levels>=3:{len(lower_levels) >= 3} monotone:{monotone} => {'PASS' if ok else 'FAIL'}")
    return dict(mean_above=mean_above, peak_below=peak_below,
                max_step=max(st) if st else 0.0, levels=len(lower_levels),
                monotone=monotone, ok=ok)


def main():
    cmd = sys.argv[1]
    im = Png(sys.argv[2])
    if cmd == "scan":
        x, y0, y1 = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
        for i, v in enumerate(scan(im, x, y0, y1)):
            print(y0 + i, round(v, 1))
    elif cmd == "band":
        x, top, bottom = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
        report_band(im, x, top, bottom)
    elif cmd == "corner":
        x0, x1, y = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
        print([(x, round(im.gray(x, y), 1)) for x in range(x0, x1 + 1)])
    else:
        raise SystemExit(f"unknown command {cmd}")


if __name__ == "__main__":
    main()
