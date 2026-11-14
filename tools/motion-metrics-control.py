"""Control experiment: what do the gradient metrics look like for a *known* isotropic vs a
*known* directional blur?

Without this, "gy fell more than gx" is not evidence of anything — text has more vertical than
horizontal structure to begin with, so the two energies were never comparable. This takes the
sharp baseline frame (freeze-0), applies a synthetic Gaussian blur with the two sigma pairs under
test, and reports the same numbers as `motion-metrics.py` on the *same* auto-detected region.

Comparing the real freeze-6 frame against these two controls is what makes the directionality
claim falsifiable: if freeze-6 matched the isotropic row, the implementation would not be
directional no matter what it looks like.

The controls differ in exactly one thing — sigmaX. Both use the same sigmaY, so the isotropic row
is the "camera defocus" reading and the directional row is what §11 asks for.
"""
import importlib.util
import sys
from pathlib import Path

import numpy as np

# motion-metrics.py has a hyphen in its name, so it cannot be imported by module path.
_HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("motion_metrics", _HERE / "motion-metrics.py")
_mm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mm)

laplacian_var = _mm.laplacian_var
load = _mm.load
sobel = _mm.sobel

# From MotionBlur.kt at radius 6 dp, density 1.0, after the 0.25 px quantization:
#   sigmaX = 6 × 0.12 = 0.72 → 0.75      sigmaY = 6 × 0.55 = 3.30 → 3.25
SIGMA_X_DP6 = 0.75
SIGMA_Y_DP6 = 3.25


def gaussian_blur(img, sigma_x, sigma_y):
    """Separable, edge-replicated, radius 4 sigma."""
    out = img
    for sigma, axis in ((sigma_y, 0), (sigma_x, 1)):
        if sigma <= 0:
            continue
        r = max(1, int(round(4 * sigma)))
        k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
        k /= k.sum()
        pad = [(0, 0), (0, 0)]
        pad[axis] = (r, r)
        p = np.pad(out, pad, mode="edge")
        acc = np.zeros_like(out)
        for i, wgt in enumerate(k):
            sl = [slice(None), slice(None)]
            sl[axis] = slice(i, i + out.shape[axis])
            acc += wgt * p[tuple(sl)]
        out = acc
    return out


def metrics(g):
    gx, gy = sobel(g)
    ex, ey = float((gx ** 2).mean()), float((gy ** 2).mean())
    return laplacian_var(g), ex, ey, ey / ex


def main(baseline):
    sharp, region = load(baseline)
    lap0, gx0, gy0, r0 = metrics(sharp)

    cases = {
        "sharp baseline (freeze-0)": sharp,
        f"CONTROL isotropic  ({SIGMA_Y_DP6}, {SIGMA_Y_DP6})": gaussian_blur(sharp, SIGMA_Y_DP6, SIGMA_Y_DP6),
        f"CONTROL directional ({SIGMA_X_DP6}, {SIGMA_Y_DP6})": gaussian_blur(sharp, SIGMA_X_DP6, SIGMA_Y_DP6),
    }

    print(f"region {sharp.shape[1]}x{sharp.shape[0]} at y0,y1,x0,x1={region}\n")
    print(f"{'case':38s} {'lap_var':>9s} {'gx':>8s} {'gy':>8s} {'gy/gx':>7s} "
          f"{'gx/gx0':>7s} {'gy/gy0':>7s}")
    for name, img in cases.items():
        lap, ex, ey, ratio = metrics(img)
        print(f"{name:38s} {lap:9.2f} {ex:8.2f} {ey:8.2f} {ratio:7.3f} "
              f"{ex / gx0:7.3f} {ey / gy0:7.3f}")

    print("\nRead it as: an isotropic blur drops gx and gy by *similar* factors and the gy/gx")
    print("ratio moves toward or past 1. A directional (Y-only) blur keeps gx much higher than")
    print("gy and the ratio falls well below the sharp baseline.")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "shots/motion/freeze-0.png")
