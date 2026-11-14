"""Objective metrics for the §11 motion-blur probe frames.

Three numbers per image, all computed on the *list* region only:

  lap_var  — variance of the 4-neighbour Laplacian. Sharp edges → large, blur → ~0.
  gx       — mean squared horizontal gradient (∂I/∂x, Sobel). Driven by vertical strokes: the
             left/right sides of glyph stems.
  gy       — mean squared vertical gradient (∂I/∂y, Sobel). Driven by horizontal edges: the
             top/bottom of strokes and the horizontal bars of a glyph.

A blur applied mostly along Y destroys horizontal edges (gy collapses) while leaving the
vertical stem sides comparatively intact (gx stays relatively high). Isotropic blur kills both.
So the diagnostic is the *ratio* gy/gx, which is scale-free: it drops for a directional blur and
rises for an isotropic one, regardless of how much total energy was lost.

The region is auto-detected because a PrintWindow capture includes the OS title bar and the HUD
column — both are sharp in every frame and would dilute (or invert) the result. Detection uses
the colours from MotionProbe.kt: the list background is 0xFF101114, the HUD background is
0xFF17191E, and the title bar is light.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HUD_BG = (0x17, 0x19, 0x1E)

# Inset past the window frame and the list's own 12dp content padding edge.
INSET = 8


def detect_region(rgb):
    """Return (y0, y1, x0, x1) of the list area: below the title bar, left of the HUD."""
    h, w, _ = rgb.shape
    mid = w // 2

    # Title bar: the first row whose mid-column pixel is dark is the first content row.
    y0 = 0
    for y in range(h):
        if int(rgb[y, mid].max()) < 0x40:
            y0 = y
            break

    # Bottom: the last row that is not the black window border.
    y1 = h
    for y in range(h - 1, y0, -1):
        if int(rgb[y, mid].max()) >= 0x30:
            y1 = y + 1
            break

    # HUD left edge: near the bottom the HUD is one flat block of HUD_BG.
    probe = rgb[max(y1 - 8, y0)]
    x1 = w
    for x in range(w - 1, 0, -1):
        px = probe[x]
        if not all(abs(int(px[i]) - HUD_BG[i]) <= 1 for i in range(3)):
            x1 = x + 1
            break

    return y0 + INSET, y1 - INSET, INSET, x1 - INSET


def load(path):
    rgb = np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)
    y0, y1, x0, x1 = detect_region(rgb)
    gray = np.asarray(Image.open(path).convert("L"), dtype=np.float64)[y0:y1, x0:x1]
    return gray, (y0, y1, x0, x1)


def sobel(g):
    """Separable Sobel, edges replicated so the border does not fake a gradient."""
    p = np.pad(g, 1, mode="edge")
    gx = (p[:-2, 2:] + 2 * p[1:-1, 2:] + p[2:, 2:]) - (p[:-2, :-2] + 2 * p[1:-1, :-2] + p[2:, :-2])
    gy = (p[2:, :-2] + 2 * p[2:, 1:-1] + p[2:, 2:]) - (p[:-2, :-2] + 2 * p[:-2, 1:-1] + p[:-2, 2:])
    return gx / 8.0, gy / 8.0


def laplacian_var(g):
    p = np.pad(g, 1, mode="edge")
    lap = p[:-2, 1:-1] + p[2:, 1:-1] + p[1:-1, :-2] + p[1:-1, 2:] - 4 * g
    return float(lap.var())


def measure(path):
    g, region = load(path)
    gx, gy = sobel(g)
    ex = float((gx ** 2).mean())
    ey = float((gy ** 2).mean())
    return {
        "path": path,
        "region": f"{g.shape[1]}x{g.shape[0]}",
        "bounds": region,
        "lap_var": laplacian_var(g),
        "gx": ex,
        "gy": ey,
        "ratio": ey / ex if ex > 0 else float("nan"),
    }


def main(paths):
    rows = [measure(p) for p in paths]
    print(f"{'file':22s} {'region':>10s} {'bounds y0,y1,x0,x1':>22s} {'lap_var':>10s} "
          f"{'gx(horiz)':>10s} {'gy(vert)':>10s} {'gy/gx':>7s}")
    for r in rows:
        print(
            f"{Path(r['path']).name:22s} {r['region']:>10s} {str(r['bounds']):>22s} "
            f"{r['lap_var']:10.2f} {r['gx']:10.2f} {r['gy']:10.2f} {r['ratio']:7.3f}"
        )
    if len(rows) >= 2:
        base = rows[0]
        print("\nrelative to the first row (fraction of the sharp baseline):")
        print(f"{'file':22s} {'lap':>7s} {'gx':>7s} {'gy':>7s} {'(gy/gx) rel':>13s}")
        for r in rows:
            print(
                f"{Path(r['path']).name:22s} "
                f"{r['lap_var'] / base['lap_var']:7.4f} "
                f"{r['gx'] / base['gx']:7.4f} "
                f"{r['gy'] / base['gy']:7.4f} "
                f"{r['ratio'] / base['ratio']:13.3f}"
            )


if __name__ == "__main__":
    main(sys.argv[1:])
