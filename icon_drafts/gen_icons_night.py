# -*- coding: utf-8 -*-
"""生成夜间（dark mode）图标资源：原图白底 → 黑底，输出到 res/mipmap-night*

做法：对近白像素做连通域标记，把 >= MIN_FILL 像素的大区域整体填黑
（外圈背景 + 禁止圈内被红色斜杠切开的白色底盘），微小高光（几百像素，
缩放后不足 1 像素）保留，不破坏贴纸质感。
"""
from PIL import Image, ImageDraw
from collections import deque
import os

HERE = os.path.dirname(__file__)
SRC = os.path.join(HERE, "Kawaii_cartoon_style_app_icon__2026-10-28T07-29-59.png")
RES = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "res"))

NIGHT_BG = (0, 0, 0, 255)          # 夜间背景：纯黑
BG_NEAR_WHITE = 232                 # 视为背景的近白阈值（RGB 全部 >= 该值）
MIN_FILL = 10000                    # 连通域达到该像素数才填黑（过滤微小高光）

LAUNCHER = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
FG_SCALE = 0.64  # 自适应图标安全区 66/108，留余量


def white_to_night(img: Image.Image) -> Image.Image:
    """近白连通域标记：大区域（背景 + 圈内底盘）填黑，微小高光保留，并清理边缘白晕。"""
    img = img.convert("RGBA")
    w, h = img.size
    px = img.load()

    def is_white(x, y):
        r, g, b, _ = px[x, y]
        return r >= BG_NEAR_WHITE and g >= BG_NEAR_WHITE and b >= BG_NEAR_WHITE

    label = bytearray(w * h)  # 0 = 未标记；1 = 待填（大区域）；2 = 保留（小高光）
    fill_regions = []
    for sy in range(h):
        for sx in range(w):
            if is_white(sx, sy) and not label[sy * w + sx]:
                comp = []
                q = deque([(sx, sy)])
                label[sy * w + sx] = 1
                while q:
                    x, y = q.popleft()
                    comp.append((x, y))
                    for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                        if (0 <= nx < w and 0 <= ny < h
                                and not label[ny * w + nx] and is_white(nx, ny)):
                            label[ny * w + nx] = 1
                            q.append((nx, ny))
                if len(comp) < MIN_FILL:
                    for x, y in comp:  # 小高光：回退为保留
                        label[y * w + x] = 2
                else:
                    fill_regions.append(comp)
    for comp in fill_regions:
        for x, y in comp:
            px[x, y] = NIGHT_BG

    # 清理抗锯齿白晕：与黑色区域相邻的浅色像素压黑（两轮扩散）
    for _ in range(2):
        fringe = []
        for y in range(h):
            for x in range(w):
                if px[x, y][:3] == NIGHT_BG[:3]:
                    for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                        if 0 <= nx < w and 0 <= ny < h:
                            r, g, b, a = px[nx, ny]
                            if r > 200 and g > 200 and b > 200 and (r, g, b, a) != NIGHT_BG:
                                fringe.append((nx, ny))
        for x, y in fringe:
            px[x, y] = NIGHT_BG
    return img


def save_webp(img, density, name):
    d = os.path.join(RES, "mipmap-night-" + density)
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, name)
    img.save(path, "WEBP", quality=90)
    print("write:", path, img.size)


src = Image.open(SRC)
night = white_to_night(src)
print("night background applied:", night.size)

# 1) 普通启动图标：整图直接缩放
for density, size in LAUNCHER.items():
    save_webp(night.resize((size, size), Image.LANCZOS), density, "ic_launcher.webp")

# 2) 圆形启动图标：黑色圆形底 + 内接正方形放置整图
for density, size in LAUNCHER.items():
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    dark = Image.new("RGBA", (size, size), NIGHT_BG)
    canvas.paste(dark, (0, 0), mask)
    side = round(size / (2 ** 0.5))
    img = night.resize((side, side), Image.LANCZOS)
    off = (size - side) // 2
    canvas.paste(img, (off, off))
    canvas = Image.composite(canvas, Image.new("RGBA", canvas.size, (0, 0, 0, 0)), mask)
    save_webp(canvas, density, "ic_launcher_round.webp")

# 3) 自适应图标前景：108dp 画布，内容缩进安全区
for density, size in FOREGROUND.items():
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = round(size * FG_SCALE)
    img = night.resize((inner, inner), Image.LANCZOS)
    off = (size - inner) // 2
    canvas.paste(img, (off, off))
    save_webp(canvas, density, "ic_launcher_foreground.webp")

print("done")
