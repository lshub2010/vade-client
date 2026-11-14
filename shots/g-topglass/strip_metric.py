# g-topglass: crop the top strip of a phone screenshot and measure sharpness.
# Metric (task book #3): edge energy = mean of squared adjacent-pixel luma deltas
# (horizontal + vertical), plus max local contrast. Region: status-bar strip,
# middle x-band excludes the system clock (left) and battery/signal cluster (right).
import sys
from PIL import Image

def luma(px):
    r, g, b = px[0], px[1], px[2]
    return 0.299 * r + 0.587 * g + 0.114 * b

def metrics(img, x0, y0, x1, y1):
    w, h = x1 - x0, y1 - y0
    L = [[luma(img.getpixel((x0 + x, y0 + y))) for x in range(w)] for y in range(h)]
    dh = dv = 0.0
    n_h = n_v = 0
    mx = 0.0
    for y in range(h):
        for x in range(w):
            if x + 1 < w:
                d = L[y][x + 1] - L[y][x]
                dh += d * d
                mx = max(mx, abs(d))
                n_h += 1
            if y + 1 < h:
                d = L[y + 1][x] - L[y][x]
                dv += d * d
                mx = max(mx, abs(d))
                n_v += 1
    mean_l = sum(sum(row) for row in L) / (w * h)
    var = sum((v - mean_l) ** 2 for row in L for v in row) / (w * h)
    return {
        "region": (x0, y0, x1, y1),
        "edge_energy_h": round(dh / n_h, 2),
        "edge_energy_v": round(dv / n_v, 2),
        "edge_energy_total": round(dh / n_h + dv / n_v, 2),
        "max_local_contrast": round(mx, 1),
        "luma_mean": round(mean_l, 1),
        "luma_variance": round(var, 1),
    }

def crop_scale(src, dst, box, scale):
    im = Image.open(src).convert("RGB")
    c = im.crop(box)
    c = c.resize((c.width * scale, c.height * scale), Image.NEAREST)
    c.save(dst)
    print(f"saved {dst} (crop={box}, scale x{scale}, out {c.size})")

if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "crop":
        # crop <src> <dst> <x0> <y0> <x1> <y1> <scale>
        crop_scale(sys.argv[2], sys.argv[3],
                   tuple(int(v) for v in sys.argv[4:8]), int(sys.argv[8]))
    elif mode == "metrics":
        # metrics <src> <x0> <y0> <x1> <y1>
        im = Image.open(sys.argv[2]).convert("RGB")
        print(sys.argv[2], metrics(im, *[int(v) for v in sys.argv[3:7]]))
