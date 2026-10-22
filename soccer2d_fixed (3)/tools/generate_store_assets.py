"""Ikonkalar va do'kon materiallari. Manbalar: tools/source/app_icon.png (ilova ikonkasi) va
tools/source/design_mock.png (dizayn maketi). Ishga tushirish: python3 tools/generate_store_assets.py"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..")
SRC = os.path.join(os.path.dirname(__file__), "source")
RES = os.path.join(ROOT, "android", "src", "main", "res")

icon = Image.open(os.path.join(SRC, "app_icon.png")).convert("RGBA")
bg = icon.getpixel((4, 4))[:3]

# 1) eski (legacy) ikonkalar
for folder, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    p = os.path.join(RES, f"mipmap-{folder}"); os.makedirs(p, exist_ok=True)
    icon.resize((size, size), Image.LANCZOS).save(os.path.join(p, "ic_launcher.png"))

# 2) adaptive ikonka (Android 8+): fon rangi + markazdagi rasm (xavfsiz zonaga sig'adi)
for folder, size in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
    p = os.path.join(RES, f"mipmap-{folder}")
    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * 0.66)
    fg.alpha_composite(icon.resize((inner, inner), Image.LANCZOS), ((size - inner) // 2, (size - inner) // 2))
    fg.save(os.path.join(p, "ic_launcher_foreground.png"))
os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
open(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"), "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/ic_launcher_background"/>\n'
    '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
    '</adaptive-icon>\n')
os.makedirs(os.path.join(RES, "values"), exist_ok=True)
open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    f'    <color name="ic_launcher_background">#{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}</color>\n</resources>\n')

# 3) Play Store ikonkasi 512x512
os.makedirs(os.path.join(ROOT, "store"), exist_ok=True)
icon.resize((512, 512), Image.LANCZOS).convert("RGB").save(os.path.join(ROOT, "store", "icon-512.png"))

# 4) Feature graphic 1024x500: maketdagi o'yin maydoni + sarlavha
mock = Image.open(os.path.join(SRC, "design_mock.png")).convert("RGB")
fg = mock.crop((118, 0, 1418, 635)).resize((1024, 500), Image.LANCZOS)
shade = Image.new("RGBA", fg.size, (0, 0, 0, 0)); sd = ImageDraw.Draw(shade)
for y in range(fg.height // 2, fg.height):
    sd.line([(0, y), (fg.width, y)], fill=(2, 10, 30, int(215 * (y - fg.height / 2) / (fg.height / 2))))
fg = Image.alpha_composite(fg.convert("RGBA"), shade)
d = ImageDraw.Draw(fg)
try: f = ImageFont.load_default(size=92)
except TypeError: f = ImageFont.load_default()
d.text((512, 425), "2v2 FOOTBALL", font=f, fill=(255, 255, 255), anchor="mm", stroke_width=6, stroke_fill=(5, 20, 60))
fg.convert("RGB").save(os.path.join(ROOT, "store", "feature-1024x500.png"))
print("store/icon assets OK, fon rangi", bg)
