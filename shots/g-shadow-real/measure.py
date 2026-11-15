#!/usr/bin/env python3
"""g-shadow-real · pixel metrics for the shared shadow primitive.

Measures, on a real emulator framebuffer (adb exec-out screencap -p), the four
acceptance criteria of the shadow stick:

  1. staircase      -> max |adjacent pixel delta| along a vertical scanline
  2. peak           -> max darkness delta vs the canvas colour (<= 8/255 = 3%)
  3. upper side     -> mean delta over the 40 px ABOVE the element's top edge
                       (must be ~0: light is straight above, shadow goes down only)
  4. lower falloff  -> number of distinct gray steps + monotonicity below the
                       element's bottom edge (>= 3 steps, no hard single edge)
  5. second contour -> the hairline+1..6 px band outside a rounded corner must not
                       contain a dark line (>6/255 below canvas) beyond the hairline

Usage:
  python measure.py scan   <png> <x> <y0> <y1>
  python measure.py band   <png> <x> <top_y> <bottom_y> [--above 40] [--below 40]
  python measure.py corner <png> <x0> <x1> <y>          # horizontal scan
  python measure.py guard  <png> --x 540 --top 2090 --bottom 2260 --canvas 247
"""
import sys
from PIL import Image


def gray(im, x, y):
    r, g, b = im.convert("RGB").getpixel((x, y))
    return (r + g + b) / 3.0


def scan(im, x, y0, y1):
    return [gray(im, x, y) for y in range(y0, y1 + 1)]


def steps(vals):
    return [abs(vals[i + 1] - vals[i]) for i in range(len(vals) - 1)]


def report_band(im, x, top, bottom, above=40, below=40, canvas=None):
    width, height = im.size
    above_vals = scan(im, x, max(0, top - above), top - 1)
    below_vals = scan(im, x, bottom + 1, min(height - 1, bottom + below))
    if canvas is None:
        canvas = max(above_vals + below_vals)
    peak_below = max((canvas - v for v in below_vals), default=0.0)
    peak_above = max((canvas - v for v in above_vals), default=0.0)
    mean_above = sum((canvas - v for v in above_vals)) / max(1, len(above_vals))
    # distinct gray levels in the lower falloff (rounded to 1/255)
    lower_levels = sorted({round(v) for v in below_vals}, reverse=True)
    # monotonicity: fraction of adjacent pairs that do not rise (towards canvas)
    st = steps(below_vals)
    rising = sum(1 for i in range(len(below_vals) - 1) if below_vals[i + 1] < below_vals[i] - 0.6)
    print(f"x={x} top={top} bottom={bottom} canvas={canvas:.1f}")
    print(f"  above: mean_delta={mean_above:.2f} max_delta={peak_above:.1f} "
          f"first_px={above_vals[-1]:.1f}")
    print(f"  below: peak_delta={peak_below:.1f} levels={len(lower_levels)} "
          f"min={min(below_vals):.1f} max_step={max(st) if st else 0:.1f} "
          f"levels_list={lower_levels}")
    print(f"  below_vals={[round(v,1) for v in below_vals]}")
    print(f"  above_vals={[round(v,1) for v in above_vals]}")
    monotone = rising == 0
    print(f"  criteria: peak<=8:{peak_below <= 8} "
          f"adjacent<=2:{max(st) <= 2 if st else True} "
          f"upper<=1:{mean_above <= 1.0} "
          f"levels>=3:{len(lower_levels) >= 3} "
          f"monotone:{monotone}")
    return dict(mean_above=mean_above, peak_below=peak_below,
                max_step=max(st) if st else 0.0, levels=len(lower_levels),
                monotone=monotone)


def main():
    cmd = sys.argv[1]
    path = sys.argv[2]
    im = Image.open(path)
    if cmd == "scan":
        x, y0, y1 = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
        vals = scan(im, x, y0, y1)
        for i, v in enumerate(vals):
            print(y0 + i, round(v, 1))
    elif cmd == "band":
        x, top, bottom = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
        report_band(im, x, top, bottom)
    elif cmd == "corner":
        x0, x1, y = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
        vals = [(x, round(gray(im, x, y), 1)) for x in range(x0, x1 + 1)]
        print(vals)
    else:
        raise SystemExit(f"unknown command {cmd}")


if __name__ == "__main__":
    main()
