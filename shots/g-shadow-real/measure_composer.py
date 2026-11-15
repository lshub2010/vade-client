#!/usr/bin/env python3
"""Measure the composer's shadow on a captured emulator frame, with the panel's
geometry taken from the UI tree (not guessed from pixels).

The attach ("添加附件") button is vertically centred in the composer row, and the
composer panel is 68 dp tall (MaterialInputBar padding 12+12 + one 44 dp field line),
so panelTop/Bottom = attachCentreY ∓ 34 dp.  Run right after the screenshot, while the
same frame is still on screen.
"""
import sys

from PIL import Image

import driver


def gray(im, x, y):
    r, g, b = im.getpixel((x, y))
    return (r + g + b) / 3.0


def main(png, x):
    x = int(x)
    im = Image.open(png).convert("RGB")
    node = driver.wait_for("添加附件", 20)
    if not node:
        raise SystemExit("attach button not in the UI tree — is the chat detail open?")
    (x0, y0, x1, y1) = node[2]
    cy = (y0 + y1) // 2
    # 68 dp at 420 dpi = 2.625 px/dp
    half = int(round(34 * 2.625))
    top, bottom = cy - half, cy + half
    print(f"attach={node[2]} centreY={cy} panel≈[{top},{bottom}]")
    above = [gray(im, x, y) for y in range(max(0, top - 60), top - 2)]
    below = [gray(im, x, y) for y in range(bottom + 3, min(im.size[1], bottom + 61))]
    print("above:", [round(v) for v in above])
    print("below:", [round(v) for v in below])
    if above and below:
        canvas = max(above[0], max(below[-10:]))
        print(f"canvas≈{canvas:.1f} peak_below={canvas - min(below):.1f} "
              f"mean_above={sum(canvas - v for v in above) / len(above):.2f}")
    cols = [round(gray(im, x, y)) for y in range(top - 4, bottom + 30, 2)]
    print("profile:", cols)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else 350)
