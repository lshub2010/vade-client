# g-haze-structure pixel metrics.
# Usage: uv run --no-project --with pillow --with numpy python metrics.py <shots-dir>
import sys
from PIL import Image
import numpy as np

D = sys.argv[1] if len(sys.argv) > 1 else "."


def load(name):
    return np.asarray(Image.open(f"{D}/{name}").convert("RGB")).astype(int)


def luma(a):
    return 0.299 * a[:, :, 0] + 0.587 * a[:, :, 1] + 0.114 * a[:, :, 2]


def stats(a, x0, y0, x1, y1, label):
    box = luma(a)[y0:y1, x0:x1]
    print(
        f"{label}: region=({x0},{y0})-({x1},{y1}) "
        f"luma_min={box.min():.0f} luma_max={box.max():.0f} luma_mean={box.mean():.1f} "
        f"var={box.var():.0f}"
    )
    return box


def diff(a, b, x0, y0, x1, y1, label):
    da = luma(a)[y0:y1, x0:x1]
    db = luma(b)[y0:y1, x0:x1]
    d = np.abs(da - db)
    print(f"{label}: max|Δluma|={d.max():.0f} mean|Δluma|={d.mean():.2f} pixels>3: {(d > 3).mean() * 100:.2f}%")
    return d


def edge_energy(a, x0, y0, x1, y1, label):
    # adjacent-pixel luma difference squared mean (horizontal+vertical), g-topglass formula
    L = luma(a)[y0:y1, x0:x1]
    eh = np.diff(L, axis=1) ** 2
    ev = np.diff(L, axis=0) ** 2
    print(
        f"{label}: edge_energy_h={eh.mean():.1f} edge_energy_v={ev.mean():.1f} "
        f"total={eh.mean() + ev.mean():.1f} max_contrast={max(abs(np.diff(L, axis=1)).max(), abs(np.diff(L, axis=0)).max()):.0f}"
    )
    return eh.mean() + ev.mean()


print("== 1 · 顶栏模糊前后对比（同名图比对，必须 ≤3/255） ==")
before = load("before-01-list.png")
after = load("after-01-list.png")
# top-bar zone: window y=63 (status inset) .. 63+189 (72dp @ 2.625); x=30..1050 (capsule spans content col)
diff(before, after, 30, 63, 1050, 252, "TOP-BAR ZONE before vs after")
# sanity: the list body (content, must be identical too)
diff(before, after, 30, 300, 1050, 2200, "LIST BODY before vs after (sanity)")

print("\n== 2 · composer 背后压内容 vs 背后空白（同一构建两帧） ==")
content = load("after-06-composer-content.png")
blank = load("after-02-detail.png")
# composer panel bounds come from the InputBar: x≈190..880, y≈2110..2290 (480x180 框)
stats(content, 200, 2115, 870, 2285, "composer box (content behind)")
stats(blank, 200, 2115, 870, 2285, "composer box (blank behind)")
d = diff(content, blank, 200, 2115, 870, 2285, "composer box: content-vs-blank Δ")
print(f"   → 背后有内容时框内 luma_min 更低 = 文字痕迹可见；Δmean 是两张图的差")

print("\n== 3 · 附件预览：卡后内容锐利度 before vs after（模糊后高频应大降） ==")
bp = load("before-08-preview.png")
ap = load("after-08-preview.png")
# preview card region: x≈60..960, y≈900..1660 (卡体内侧,避开标题行与图片本体的高频)
edge_energy(bp, 80, 1080, 940, 1640, "BEFORE preview card region")
edge_energy(ap, 80, 1080, 940, 1640, "AFTER preview card region")

print("\n== 4 · 参考对照：未模糊的真实内容区（量管线灵敏度） ==")
edge_energy(bp, 30, 700, 1050, 900, "BEFORE 全屏 scrim 下（无卡区，应≈原始内容）")
