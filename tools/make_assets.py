#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成应用图标（多密度）与算法测试用样图。纯 Python + Pillow，可重复执行。"""
import os
import math
import random
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "res")
TOOLS = os.path.join(ROOT, "tools")


def make_icon(size=768):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    S = float(size)
    # 深色圆角底
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=S * 0.22, fill=(26, 27, 33, 255))
    m = S * 0.155
    # 相框轮廓（白线）
    d.rounded_rectangle([m, m * 1.04, S - m, S - m * 0.94],
                        radius=S * 0.055, outline=(255, 255, 255, 240), width=int(S * 0.026))
    # 太阳
    cx, cy, r = S * 0.35, S * 0.37, S * 0.082
    d.ellipse([cx - r, cy - r, cx + r, cy + r],
              outline=(255, 255, 255, 240), width=int(S * 0.023))
    # 山脊线
    pts = [(m * 1.25, S * 0.665), (S * 0.41, S * 0.425), (S * 0.565, S * 0.60),
           (S * 0.72, S * 0.455), (S - m * 1.25, S * 0.665)]
    d.line(pts, fill=(255, 255, 255, 240), width=int(S * 0.027), joint="curve")
    # 地平线
    d.line([(m * 1.25, S * 0.735), (S - m * 1.25, S * 0.735)],
           fill=(255, 255, 255, 175), width=int(S * 0.018))
    # 橙色斜线（象征“描线”）
    for k in range(3):
        y = S * (0.29 + 0.115 * k)
        d.line([(S * 0.735, y), (S * 0.865, y - S * 0.095)],
               fill=(255, 158, 74, 255), width=int(S * 0.025))
    return im


def make_sample(w=900, h=680):
    """一张有渐变、色块、轮廓和轻噪点的彩色测试图，用来验证线稿算法。"""
    random.seed(20261115)
    im = Image.new("RGB", (w, h))
    px = im.load()
    # 天空：橙 -> 蓝 渐变
    for y in range(int(h * 0.62)):
        t = y / (h * 0.62)
        r = int(255 * (1 - t) + 92 * t)
        g = int(166 * (1 - t) + 168 * t)
        b = int(96 * (1 - t) + 232 * t)
        for x in range(w):
            px[x, y] = (r, g, b)
    # 湖面：蓝 -> 深蓝 渐变
    for y in range(int(h * 0.62), h):
        t = (y - h * 0.62) / (h * 0.38)
        for x in range(w):
            px[x, y] = (int(56 - 32 * t), int(146 - 78 * t), int(214 - 92 * t))
    d = ImageDraw.Draw(im)
    # 太阳
    d.ellipse([w * 0.70 - 58, h * 0.13 - 58, w * 0.70 + 58, h * 0.13 + 58],
              fill=(255, 241, 176), outline=(255, 214, 92), width=5)
    # 远山（紫）
    d.polygon([(0, h * 0.62), (w * 0.20, h * 0.34), (w * 0.38, h * 0.62)], fill=(126, 96, 176))
    d.line([(0, h * 0.62), (w * 0.20, h * 0.34), (w * 0.38, h * 0.62)],
           fill=(78, 54, 122), width=4)
    # 近山（绿）
    d.polygon([(w * 0.28, h * 0.62), (w * 0.53, h * 0.40), (w * 0.80, h * 0.62)],
              fill=(64, 158, 116))
    d.line([(w * 0.28, h * 0.62), (w * 0.53, h * 0.40), (w * 0.80, h * 0.62)],
           fill=(28, 104, 76), width=5)
    # 雪顶
    d.polygon([(w * 0.47, h * 0.446), (w * 0.53, h * 0.40), (w * 0.59, h * 0.446),
               (w * 0.552, h * 0.433), (w * 0.525, h * 0.453), (w * 0.50, h * 0.432)],
              fill=(244, 248, 255))
    # 水面波纹
    for k in range(7):
        y = int(h * (0.655 + k * 0.045))
        d.line([(w * 0.06, y), (w * (0.30 + 0.06 * (k % 3)), y)],
               fill=(226, 240, 255), width=3)
    # 前景花朵
    for (fx, fy, rad, col) in [(w * 0.14, h * 0.84, 26, (232, 78, 96)),
                               (w * 0.24, h * 0.90, 20, (255, 196, 72)),
                               (w * 0.82, h * 0.86, 28, (238, 122, 186)),
                               (w * 0.90, h * 0.93, 18, (255, 236, 120))]:
        d.line([(fx, fy + rad), (fx, h)], fill=(46, 128, 74), width=5)
        for a in range(0, 360, 60):
            ang = math.radians(a)
            cx, cy = fx + math.cos(ang) * rad * 0.72, fy + math.sin(ang) * rad * 0.72
            d.ellipse([cx - rad * 0.55, cy - rad * 0.55, cx + rad * 0.55, cy + rad * 0.55],
                      fill=col, outline=(150, 60, 70), width=2)
        d.ellipse([fx - rad * 0.34, fy - rad * 0.34, fx + rad * 0.34, fy + rad * 0.34],
                  fill=(255, 244, 190), outline=(170, 130, 50), width=2)
    # 轻微噪点，用于验证“底色干净”
    for _ in range(int(w * h * 0.006)):
        x, y = random.randrange(w), random.randrange(h)
        r, g, b = px[x, y]
        q = random.randint(-14, 14)
        px[x, y] = (max(0, min(255, r + q)), max(0, min(255, g + q)), max(0, min(255, b + q)))
    return im.filter(ImageFilter.SMOOTH)


def main():
    icon = make_icon()
    for folder, s in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72),
                      ("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144),
                      ("mipmap-xxxhdpi", 192)]:
        icon.resize((s, s), Image.LANCZOS).save(os.path.join(RES, folder, "ic_launcher.png"))
    sample = make_sample()
    sample.save(os.path.join(TOOLS, "sample.png"))
    sample.save(os.path.join(ROOT, "docs", "sample.png"))
    print("已生成 launcher 图标 5 种密度 + tools/sample.png + docs/sample.png")


if __name__ == "__main__":
    main()
