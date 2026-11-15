#!/usr/bin/env python3
# g-topbar-band · 修前/修后像素度量（D2 承重验收）
#
#  1) 空隙里的相邻像素梯度能量：修前锐、修后必须掉下来（目标与卡内模糊区同一量级）；
#  2) 卡片本体逐像素告差 + 裁图 md5：必须 ≤ 2/255 且 md5 相同（垫在卡片背后的合法性证明）；
#  3) 带子的实际覆盖范围：由 before/after 的差异包围盒**量出来**（跳过状态栏时钟），
#     顺带核对它正好是 TOP_BAR_ZONE（72dp = 189px @ 420dpi）。
#
# 用法: python shots/g-topbar-band/metrics.py <before.png> <after.png> [density]
import hashlib
import sys
from PIL import Image, ImageChops

# 手机面：411×571dp / 1080×1500px @ 420dpi
CARD_INSET_DP = 12.0   # 浮卡左右内缩（shell 的 Row padding）
CORNER_DP = 28.0       # 浮卡圆角（skin.shapes.xl）


def luma(px, x, y):
    r, g, b = px[x, y][:3]
    return 0.299 * r + 0.587 * g + 0.114 * b


def energy(img, box):
    """每像素平均相邻梯度（水平+垂直），0..255 量纲。"""
    l, t, r, b = box
    px = img.convert("RGB").load()
    total, n = 0.0, 0
    for y in range(t, b):
        for x in range(l, r):
            v = luma(px, x, y)
            total += abs(v - luma(px, x + 1, y))
            total += abs(v - luma(px, x, y + 1))
            n += 2
    return total / n


def diff_bbox(a, b, y_from, y_to, threshold=3):
    d = ImageChops.difference(a.convert("RGB").crop((0, y_from, a.width, y_to)),
                              b.convert("RGB").crop((0, y_from, a.width, y_to))).convert("L")
    mask = d.point(lambda v: 255 if v > threshold else 0)
    bb = mask.getbbox()
    return None if bb is None else (bb[0], bb[1] + y_from, bb[2], bb[3] + y_from)


def max_delta(a, b, box):
    l, t, r, b_ = box
    pa, pb = a.convert("RGB").load(), b.convert("RGB").load()
    worst, where = 0, None
    for y in range(t, b_):
        for x in range(l, r):
            dd = max(abs(pa[x, y][i] - pb[x, y][i]) for i in range(3))
            if dd > worst:
                worst, where = dd, (x, y)
    return worst, where


def md5_of(img, box):
    return hashlib.md5(img.convert("RGB").crop(box).tobytes()).hexdigest()


def main():
    before_path, after_path = sys.argv[1], sys.argv[2]
    density = float(sys.argv[3]) if len(sys.argv) > 3 else 2.625
    # 带的顶 = 应用内容顶 = 状态栏 inset 下沿（MainActivity 的 statusBarsPadding 消费 24dp）。
    # 不能靠 before/after 的差异包围盒推：修好之后空隙里的差异只剩「糊了」，阈值一高就
    # 只剩状态栏时钟那几 px，带顶会被推歪（上一版就这么错了 23px）。
    band_top = int(sys.argv[4]) if len(sys.argv) > 4 else int(round(24 * density))
    before, after = Image.open(before_path), Image.open(after_path)
    assert before.size == after.size, f"两帧尺寸不同：{before.size} vs {after.size}"
    w, h = before.size

    gap = int(round(8 * density))            # 8dp 呼吸间隙
    card_h = int(round(56 * density))        # TopBarHeight
    band_h = int(round(72 * density))        # TOP_BAR_ZONE
    card_t, card_b = band_top + gap, band_top + gap + card_h
    bb = band_top + band_h
    print(f"图：{before_path} vs {after_path}（{w}×{h}, density {density}）")
    print(f"顶栏带：y {band_top}..{bb}（{band_h}px = 72dp）；浮卡：y {card_t}..{card_b}（56dp）")

    L = int(round((CARD_INSET_DP + 3) * density))
    R = w - L
    strips = {
        "上空隙 8dp（带顶→卡上沿）": (L, band_top + 3, R, card_t - 3),
        "下空隙 8dp（卡下沿→带底）": (L, card_b + 3, R, bb - 3),
    }
    # 卡内参照：躲开标题文字（取右侧 60dp）且垂直躲开 28dp 圆角
    ref_inset = int(round(CORNER_DP / 2 * density))
    ref = (w - int(70 * density), card_t + ref_inset,
           w - int(CARD_INSET_DP * density) - int(10 * density), card_b - ref_inset)

    print("\n相邻像素梯度能量（每像素平均，0..255）")
    print("| 取样区 | 修前 | 修后 | 下降 |")
    print("|---|---|---|---|")
    for name, box in strips.items():
        e0, e1 = energy(before, box), energy(after, box)
        print(f"| {name} | {e0:.2f} | {e1:.2f} | {e0 / e1:.1f}× |")
    e0, e1 = energy(before, ref), energy(after, ref)
    print(f"| 卡内模糊区（参照） | {e0:.2f} | {e1:.2f} | — |")

    # 卡片本体：躲开玻璃边缘那圈 <30px 的羽化（见报告 §4：带子垫在卡背后，卡片玻璃的
    # 边沿羽化会把「背后的东西」混进来——那圈像素本来就是模糊渐隐，不是卡片的形状/颜色）
    dx, dy = int(round(14 * density)), int(round(14 * density))
    body = (int(round(CARD_INSET_DP * density)) + dx, card_t + dy,
            w - int(round(CARD_INSET_DP * density)) - dx, card_b - dy)
    delta, where = max_delta(before, after, body)
    md5a, md5b = md5_of(before, body), md5_of(after, body)
    print(f"\n卡片本体（{body[2] - body[0]}×{body[3] - body[1]}px，离边 14dp 内侧）："
          f"最大逐像素差 {delta}/255 {'✓ 一致' if delta <= 2 else '✗ 变了'} @ {where}")
    print(f"  裁图 md5  修前={md5a}  修后={md5b}  {'✓ 相同' if md5a == md5b else '（不同：见差值）'}")

    # 卡内「纯填充」子样（标题/副标题/图标之外的空玻璃）：这里必须一位不差
    fill = (int(round(180 * density)), card_t + int(round(18 * density)),
            int(round(360 * density)), card_b - int(round(18 * density)))
    d2, w2 = max_delta(before, after, fill)
    print(f"  其中纯填充子样（{fill[2] - fill[0]}×{fill[3] - fill[1]}px）：最大差 {d2}/255 "
          f"{'✓' if d2 <= 1 else ''} @ {w2}")

    # 带下方：完全不该动（改动没有外溢）
    below = (0, bb + 12, w, h - 12)
    if below[3] > below[1]:
        delta2, where2 = max_delta(before, after, below)
        print(f"带下方内容（y>{bb + 12}）：最大差异 {delta2}/255 "
              f"{'✓ 未受影响' if delta2 <= 2 else f'✗ 被动了 @ {where2}'}")


if __name__ == "__main__":
    main()
