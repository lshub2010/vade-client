"""Streak profile: the third metric, and the one that actually distinguishes box from Gaussian.

`motion-metrics.py` reports gradient *energy* (gx/gy). It proves "blurred a lot along Y" but it
cannot tell a plateau from a bell: a box kernel and a Gaussian of similar length destroy almost
the same horizontal-edge energy, so `gx`/`gy`/`lap_var` barely separate them. What separates them
is the *shape* of a single streak.

## The observable

Take a glyph's **vertical stem** — a column lit over many consecutive rows — and walk down it
through the smeared horizontal edge at the top of the stem. To first order that edge is a **step**
(0 above the cap height, 1 below it). A blur is a convolution, so

    profile = step ⊛ kernel        ⇒        d(profile)/dy = kernel

The first difference of the sampled profile *is the kernel, recovered from pixels*. Therefore:

  - a **box** kernel produces a profile that ramps **linearly** then stops dead: a straight line
    of constant slope with hard corners. Its first difference is **flat** — N near-identical
    values (at 6 dp / 13 taps, ~0.066–0.070 each).
  - a **Gaussian** produces an erf-shaped S-curve: gentle at both ends, steep in the middle. Its
    first difference is a **bell** — small, rising to a peak, falling back to small.

That gives the metric. Over the ramp (the contiguous run of first differences that carry the
transition):

  peak        — max luminance in the sampled window (8-bit)
  ramp_rows   — rows the transition occupies = the kernel's smear span in px
  slope_cv    — stdev/mean of |first difference| across the ramp   ← the discriminator
  end_ratio   — mean |diff| in the outer thirds / in the middle. Box ≈ 1 (the slope holds right
                up to the corner); a bell ≈ 0.2 (the S-curve flattens out at both ends).
  curvature   — mean |second difference| / mean |first difference|. A straight ramp ≈ 0; an
                erf curve is not.

Measured on the two 6 dp frames this probe ships:

    ab-box-6.png       ramp 13 px   slope_cv 0.031   end_ratio 0.97   curvature 0.008
    ab-gaussian-6.png  ramp  9 px   slope_cv 0.255   end_ratio 0.33   curvature 0.166

`slope_cv` differs by 8× and `curvature` by 20×, which is the quantitative form of "the box
streak has hard ends, the Gaussian streak fades out".

The raw samples and an ASCII plot are printed too, so every number is checkable by eye.

Region detection is reused from `motion-metrics.py` so the crop matches the other metrics exactly.
"""
import importlib.util
import sys
from pathlib import Path

import numpy as np
from PIL import Image

_HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("motion_metrics", _HERE / "motion-metrics.py")
assert _spec is not None and _spec.loader is not None
_mm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mm)

detect_region = _mm.detect_region

# The probe draws "MOTION BLUR" at 34 sp monospace, light on a 0xFF1B1D22 card.
TEXT_MIN = 140          # 8-bit luminance that counts as "lit"
STROKE_MIN_ROWS = 6     # a vertical stem is at least this tall before it is smeared
# A first difference above this counts as "inside the ramp" rather than noise on a flat run.
RAMP_MIN_STEP = 0.02


def load_gray(path):
    rgb = np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)
    y0, y1, x0, x1 = detect_region(rgb)
    gray = np.asarray(Image.open(path).convert("L"), dtype=np.float64)
    return gray, (y0, y1, x0, x1)


def find_stroke(gray, bounds):
    """Return (x, y_top) of a bright vertical stem: a column lit over many consecutive rows.

    Deliberately not "the brightest pixel": the brightest is usually a corner of a horizontal
    bar, whose profile is already a plateau rather than a clean step. A stem gives one edge.
    """
    y0, y1, x0, x1 = bounds
    region = gray[y0:y1, x0:x1]

    rows = (region > TEXT_MIN).sum(axis=1)
    if rows.max() == 0:
        return None
    first_text = int(np.argmax(rows > 8))

    band = region[first_text:first_text + 40]
    best = None
    for x in range(band.shape[1]):
        lit = band[:, x] > TEXT_MIN
        run = 0
        best_run = 0
        for v in lit:
            run = run + 1 if v else 0
            best_run = max(best_run, run)
        if best_run >= STROKE_MIN_ROWS and (best is None or best_run > best[1]):
            best = (x, best_run)
    if best is None:
        return None
    return x0 + best[0], y0 + first_text


def profile_at(gray, x, y_top, length=44):
    """Brightness down one column, starting a few rows above the stroke top."""
    start = max(y_top - 8, 0)
    return gray[start:start + length, x]


def analyse(values, label):
    v = np.asarray(values, dtype=np.float64)
    peak = float(v.max())
    if peak <= 0:
        return None
    norm = v / peak
    diff = np.diff(norm)

    # Ramp = the *first* contiguous run of first differences that carry the transition. Keying on
    # |diff| rather than on the profile itself keeps a saturated flat top out of the measurement,
    # and stopping at the first sub-threshold diff keeps the glyph's *bottom* edge out of it (that
    # edge would otherwise be merged in, and the run would no longer be monotone).
    big = np.abs(diff) > RAMP_MIN_STEP
    if not big.any():
        return None
    start = int(np.flatnonzero(big)[0])
    end = start
    while end + 1 < len(diff) and big[end + 1]:
        end += 1
    seg = diff[start:end + 1]
    # A ramp is monotone. A run that is not means the column crossed two edges (a bar and a stem),
    # which would blend two kernels together and must not be measured.
    if not (np.all(seg >= -RAMP_MIN_STEP) or np.all(seg <= RAMP_MIN_STEP)):
        return None
    seg_abs = np.abs(seg)
    if seg_abs.mean() <= 0:
        return None

    slope_cv = float(seg_abs.std() / seg_abs.mean())
    third = max(len(seg_abs) // 3, 1)
    ends = np.concatenate([seg_abs[:third], seg_abs[-third:]])
    middle = seg_abs[third:len(seg_abs) - third] if len(seg_abs) > 2 * third else seg_abs
    end_ratio = float(ends.mean() / middle.mean()) if middle.mean() > 0 else float("nan")
    second = np.diff(seg)
    curvature = float(np.abs(second).mean() / seg_abs.mean()) if len(second) else float("nan")

    return {
        "label": label,
        "peak": peak,
        "ramp_rows": int(len(seg)),
        "slope_cv": slope_cv,
        "end_ratio": end_ratio,
        "curvature": curvature,
        "seg": seg_abs,
        "norm": norm,
    }


def ascii_plot(values, width=52, rows=30):
    v = np.asarray(values, dtype=np.float64)
    peak = v.max()
    if peak <= 0:
        return []
    lines = []
    for i, s in enumerate(v[:rows] / peak):
        n = int(round(s * width))
        lines.append(f"{i:3d} |{'#' * n}{'.' * (width - n)}| {s:5.3f}")
    return lines


def main(paths):
    rows = []
    for p in paths:
        gray, bounds = load_gray(p)
        found = find_stroke(gray, bounds)
        if found is None:
            print(f"{Path(p).name}: NO_STROKE_FOUND in region {bounds}")
            continue
        x, y_top = found
        values = profile_at(gray, x, y_top)
        r = analyse(values, Path(p).name)
        if r is None:
            print(f"{Path(p).name}: degenerate profile at x={x} y={y_top}")
            continue
        r["x"], r["y"] = x, y_top
        rows.append(r)

        print(f"\n=== {r['label']}  (column x={x}, text top y={y_top}) ===")
        print("profile, first 20 rows (peak-normalised luminance):")
        print("  " + " ".join(f"{s:5.3f}" for s in r["norm"][:20]))
        print("first difference = the kernel, recovered from pixels:")
        print("  " + " ".join(f"{s:5.3f}" for s in r["seg"][:20]))
        print("ascii profile (each row = 1 px down the column):")
        for line in ascii_plot(values):
            print("  " + line)

    print("\n--- summary ---")
    print(f"{'file':20s} {'x':>4s} {'peak':>6s} {'ramp':>5s} {'slope_cv':>9s} "
          f"{'end_ratio':>10s} {'curv':>6s}  kernel shape")
    for r in rows:
        shape = "PLATEAU (box)" if r["slope_cv"] < 0.10 else "BELL (gaussian)"
        print(f"{r['label']:20s} {r['x']:4d} {r['peak']:6.1f} {r['ramp_rows']:5d} "
              f"{r['slope_cv']:9.3f} {r['end_ratio']:10.3f} {r['curvature']:6.3f}  {shape}")
    return rows


if __name__ == "__main__":
    main(sys.argv[1:])
