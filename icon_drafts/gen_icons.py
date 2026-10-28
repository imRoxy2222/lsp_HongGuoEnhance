# -*- coding: utf-8 -*-
"""生成 HongGuoEnhance 桌面图标资源（基于用户选定的卡通贴纸风西红柿图）"""
from PIL import Image, ImageDraw, ImageOps
import os

SRC = os.path.join(os.path.dirname(__file__), "Kawaii_cartoon_style_app_icon__2026-10-28T07-29-59.png")
RES = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res"))

src = Image.open(SRC).convert("RGBA")
print("source:", src.size)

# 各密度对应的像素尺寸（dp 基准 48px/mdpi）
LAUNCHER = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# 自适应图标前景画布为 108dp
FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
# 前景内容缩放比例：自适应图标安全区为 66/108，留少量余量取 0.64
FG_SCALE = 0.64


def save_webp(img, density, name):
    d = os.path.join(RES, "mipmap-" + density)
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, name)
    img.save(path, "WEBP", quality=90)
    print("write:", path, img.size)


# 1) 普通启动图标：整图直接缩放
for density, size in LAUNCHER.items():
    img = src.resize((size, size), Image.LANCZOS)
    save_webp(img, density, "ic_launcher.webp")

# 2) 圆形启动图标：白色圆形底 + 内接正方形放置整图，保证禁止圈完整
for density, size in LAUNCHER.items():
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    white = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    canvas.paste(white, (0, 0), mask)
    side = round(size / (2 ** 0.5))
    img = src.resize((side, side), Image.LANCZOS)
    off = (size - side) // 2
    canvas.paste(img, (off, off))
    r, g, b, a = canvas.split()
    canvas = Image.composite(canvas, Image.new("RGBA", canvas.size, (0, 0, 0, 0)), mask)
    save_webp(canvas, density, "ic_launcher_round.webp")

# 3) 自适应图标前景：108dp 画布，内容缩进安全区，避免禁止圈被启动器蒙版裁切
for density, size in FOREGROUND.items():
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = round(size * FG_SCALE)
    img = src.resize((inner, inner), Image.LANCZOS)
    off = (size - inner) // 2
    canvas.paste(img, (off, off))
    save_webp(canvas, density, "ic_launcher_foreground.webp")

print("done")
