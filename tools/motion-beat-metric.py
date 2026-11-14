"""Beat (echo) metric: vertical autocorrelation of the streak bands — the fourth metric.

The parent probe reported that on the pathological fixture (8× identical `MOTION BLUR`, 34 sp
monospace) the box kernel renders every letter as 2–3 evenly stacked horizontal bands, while
the same kernel on the small `REFERENCE` swatch is smooth. Zooming into the pixels shows what
those bands *are*: a glyph's own cap band / dim middle / baseline band, only partially merged
by the 13 px kernel — and at 34 sp the dim middle is ≈ the kernel length, the worst possible
resonance. "Echo strength" is therefore: **how much of that band structure survives the
smear** — and it is a property of (kernel, glyph geometry), not of the kernel alone.

## The observable

For each text band (a run of rows with lit pixels — one line of text), take the row profile
`r(y) = mean over text-carrying columns` and measure, inside the band rows only (the band's
real dark surroundings bound the window, no synthetic padding):

  acf2      — lag-2 autocorrelation of the mean-removed profile. A solid smear plateau
              correlates with itself at short lags (→ high); sharp text does not (→ ~0).
  trough    — min over lags 2..12 of the normalised ACF, with its lag = **主周期** (the
              bright-band pitch the eye reads as stacked copies). **强度** = |trough|.
  seam      — central-dip depth: `1 − min(middle third) / mean(flanking maxima)` of the raw
              profile. The direct "dark seam between two bright bands" number.

All three are averaged over bands 3..6 (bands 1–2 and the last sit against the layer's CLAMP
edges and are excluded). Sharp text is the reference: its band structure is the *cause*, so
its numbers are expected to differ from every blurred frame's, not to be 0.

Verdict thresholds for `seam`, calibrated on this probe's own A/B (where the box echo is
visible by eye and the Gaussian's is not):

    seam ≥ 0.08   ECHO    — stacked-band structure clearly survives
    seam ≥ 0.015  FAINT   — measurable, barely visible
    seam <  0.015 NONE    — the smear reads as one solid streak

Method note: a *region-wide* ACF (the first version of this tool) is dominated by the smear's
own ramp — every 13 px kernel correlates with itself at lags 2–5, banded or not. Restricting
to per-band profiles with real boundaries is what makes the numbers comparable across kernels.
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

PAD = 24              # rows of real dark gap kept on each side of a band (bounds the window)
MIN_BAND_ROWS = 8     # a text band shorter than this is noise (13 sp rows are ~10 px)
LAG_MIN, LAG_MAX = 2, 12
BANDS_USED = slice(2, 6)   # skip the two edge bands: layer CLAMP boundary pollutes them
ECHO, FAINT = 0.08, 0.015


def load_region_gray(path):
    rgb = np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)
    y0, y1, x0, x1 = detect_region(rgb)
    gray = np.asarray(Image.open(path).convert("L"), dtype=np.float64)[y0:y1, x0:x1] / 255.0
    return gray, (y0, y1, x0, x1)


def find_bands(gray):
    """Runs of consecutive rows with lit pixels — one run per line of text."""
    lit = (gray > 0.5).sum(axis=1)
    bands, y, h = [], 0, len(lit)
    while y < h:
        if lit[y] > 20:
            y2 = y
            while y2 < h and lit[y2] > 5:
                y2 += 1
            if y2 - y >= MIN_BAND_ROWS:
                bands.append((y, y2))
            y = y2
        else:
            y += 1
    return bands


def row_profile(gray):
    """Mean brightness per row over columns that ever carry text."""
    energy = (gray * gray).mean(axis=0)
    keep = energy > (10.0 / 255) ** 2
    if not keep.any():
        return None
    return gray[:, keep].mean(axis=1)


def band_metrics(seg, lagmax):
    """acf2, trough lag, trough value, seam depth for one band's raw profile."""
    z = seg - seg.mean()
    s = z.std()
    if s < 1e-9:
        return None
    z = z / s
    acf = {L: float(np.mean(z[:-L] * z[L:])) for L in range(LAG_MIN, lagmax + 1)}
    t_lag, t_val = min(acf.items(), key=lambda kv: kv[1])
    t3 = max(len(seg) // 3, 1)
    peaks = (seg[:t3].max() + seg[-t3:].max()) / 2.0
    mid = seg[t3:len(seg) - t3].min() if len(seg) > 2 * t3 else seg.min()
    seam = 1.0 - mid / peaks if peaks > 1e-6 else 0.0
    return {"acf2": acf[LAG_MIN], "trough_lag": t_lag, "trough": t_val, "seam": seam}


def analyse(path):
    gray, bounds = load_region_gray(path)
    prof = row_profile(gray)
    if prof is None:
        return None
    bands = find_bands(gray)
    per_band = []
    for y0, y1 in bands[BANDS_USED]:
        if y0 - PAD < 0 or y1 + PAD > len(prof):
            continue
        lagmax = min(LAG_MAX, max(3, (y1 - y0) // 2))
        m = band_metrics(prof[y0:y1], lagmax)
        if m is not None:
            m["y0"], m["y1"] = y0, y1
            per_band.append(m)
    if not per_band:
        return None
    mean = {k: float(np.mean([m[k] for m in per_band]))
            for k in ("acf2", "trough_lag", "trough", "seam")}
    seam = mean["seam"]
    mean["verdict"] = "ECHO" if seam >= ECHO else ("FAINT" if seam >= FAINT else "NONE")
    mean["file"] = Path(path).name
    mean["n_bands"] = len(bands)
    mean["bands_used"] = len(per_band)
    return mean


def main(paths):
    rows = []
    for p in paths:
        r = analyse(p)
        if r is None:
            print(f"{Path(p).name}: NO_TEXT_BANDS inside the detected region")
            continue
        rows.append(r)
        print(f"\n=== {r['file']}  ({r['bands_used']} of {r['n_bands']} bands measured) ===")
        print(f"  acf2 (plateau flatness)   {r['acf2']:+.3f}")
        print(f"  trough (主周期/强度)       L{r['trough_lag']:.1f}  {r['trough']:+.3f}")
        print(f"  seam (central dip depth)  {r['seam']:.3f}  → {r['verdict']}")

    if rows:
        print("\n--- summary ---")
        print(f"{'file':24s} {'acf2':>6s} {'period':>6s} {'strength':>9s} {'seam':>6s}  verdict")
        for r in rows:
            print(f"{r['file']:24s} {r['acf2']:6.3f} {r['trough_lag']:6.1f} "
                  f"{abs(r['trough']):9.3f} {r['seam']:6.3f}  {r['verdict']}")
            print(f"BEAT_RESULT file={r['file']} period={r['trough_lag']:.1f} "
                  f"strength={abs(r['trough']):.4f} acf2={r['acf2']:.4f} "
                  f"seam={r['seam']:.4f} verdict={r['verdict']}")
    return rows


if __name__ == "__main__":
    main(sys.argv[1:])
