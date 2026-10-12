"""2v2 Football uchun grafika: atlas.png + atlas.atlas + grass.png (dizayn: tools/source/design_mock.png).
Haqiqiy rasmlar bilan almashtirsangiz, region nomlarini saqlang. Ishga tushirish: python3 tools/generate_assets.py"""
import math, os, random
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps

OUT = os.path.join(os.path.dirname(__file__), "..", "android", "src", "main", "assets")
os.makedirs(OUT, exist_ok=True)
SS = 4

def S(v): return int(round(v * SS))
def canvas(w, h=None): h = h or w; return Image.new("RGBA", (S(w), S(h)), (0, 0, 0, 0))
def done(im, w, h=None): return im.resize((w, h or w), Image.LANCZOS)
def circle(d, cx, cy, r, fill=None, outline=None, width=0):
    d.ellipse([S(cx - r), S(cy - r), S(cx + r), S(cy + r)], fill=fill, outline=outline, width=S(width) if width else 0)
def rrect(d, x0, y0, x1, y1, r, fill=None, outline=None, width=0):
    d.rounded_rectangle([S(x0), S(y0), S(x1), S(y1)], radius=S(r), fill=fill, outline=outline, width=S(width) if width else 0)
def poly(d, pts, fill=None):
    d.polygon([(S(x), S(y)) for x, y in pts], fill=fill)
def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))

PALETTE = {  # o'yinchi ranglari (dizayndagi 8 ta ikonkadan)
    "blue": (30, 110, 255), "cyan": (60, 205, 255), "teal": (30, 205, 190),
    "red": (235, 40, 50), "orange": (255, 170, 30), "pink": (255, 100, 200),
}

def glossy(n, col):
    im = canvas(n); d = ImageDraw.Draw(im); c = n / 2; r = n / 2 - 3
    circle(d, c, c, r, fill=(8, 10, 16, 255))
    r2 = r - n * 0.05
    dark = tuple(int(v * 0.5) for v in col) + (255,)
    circle(d, c, c, r2, fill=dark)
    circle(d, c - r2 * 0.06, c - r2 * 0.1, r2 * 0.9, fill=col + (255,))
    light = tuple(min(255, int(v * 1.2 + 40)) for v in col) + (255,)
    circle(d, c - r2 * 0.22, c - r2 * 0.28, r2 * 0.5, fill=light)
    hl = canvas(n); hd = ImageDraw.Draw(hl)
    hd.ellipse([S(c - r2 * 0.55), S(c - r2 * 0.62), S(c - r2 * 0.05), S(c - r2 * 0.22)], fill=(255, 255, 255, 190))
    im = Image.alpha_composite(im, hl)
    return done(im, n)

def marker():
    w, h = 64, 56
    im = canvas(w, h); d = ImageDraw.Draw(im)
    poly(d, [(3, 4), (61, 4), (32, 54)], fill=(8, 10, 16, 255))
    poly(d, [(11, 11), (53, 11), (32, 43)], fill=(255, 255, 255, 255))
    return done(im, w, h)

def soccer_ball(n):
    im = canvas(n); d = ImageDraw.Draw(im); c = n / 2; r = n / 2 - 2
    circle(d, c, c, r, fill=(25, 25, 30, 255))
    circle(d, c, c, r - 2.5, fill=(250, 250, 250, 255))
    def pent(cx, cy, pr, rot):
        poly(d, [(cx + pr * math.cos(rot + i * 2 * math.pi / 5), cy + pr * math.sin(rot + i * 2 * math.pi / 5)) for i in range(5)], fill=(25, 25, 30, 255))
    pent(c, c, r * 0.34, -math.pi / 2)
    for i in range(5):
        a = -math.pi / 2 + i * 2 * math.pi / 5 + math.pi / 5
        pent(c + math.cos(a) * r * 0.82, c + math.sin(a) * r * 0.82, r * 0.22, a)
    return done(im, n)

def shadow(n):
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.ellipse([n * 0.12, n * 0.12, n * 0.88, n * 0.88], fill=(0, 0, 0, 200))
    return im.filter(ImageFilter.GaussianBlur(n * 0.08))

def ring(n):
    im = canvas(n); d = ImageDraw.Draw(im)
    circle(d, n / 2, n / 2, n / 2 - 4, outline=(255, 255, 255, 255), width=5)
    return done(im, n)

def joy_base(n):
    im = canvas(n); d = ImageDraw.Draw(im); c = n / 2
    circle(d, c, c, c - 4, fill=(25, 40, 55, 175), outline=(140, 160, 175, 230), width=7)
    circle(d, c, c, c - 16, outline=(90, 110, 125, 120), width=3)
    a = n * 0.34; s = n * 0.07
    for dx, dy in [(0, -1), (1, 0), (0, 1), (-1, 0)]:
        cx, cy = c + dx * a, c + dy * a
        if dx == 0: pts = [(cx, cy + dy * s), (cx - s, cy - dy * s), (cx + s, cy - dy * s)]
        else: pts = [(cx + dx * s, cy), (cx - dx * s, cy - s), (cx - dx * s, cy + s)]
        poly(d, pts, fill=(150, 165, 180, 220))
    return done(im, n)

def joy_knob(n):
    im = canvas(n); d = ImageDraw.Draw(im); c = n / 2
    circle(d, c, c, c - 3, fill=(95, 105, 118, 255))
    circle(d, c - 2, c - 3, c - 9, fill=(170, 176, 186, 255))
    circle(d, c - 6, c - 8, c * 0.5, fill=(205, 210, 218, 255))
    return done(im, n)

def action_btn(n, rim, icon):
    im = canvas(n); d = ImageDraw.Draw(im); c = n / 2
    circle(d, c, c, c - 4, fill=(8, 22, 48, 225))
    circle(d, c, c, c - 4, outline=rim + (255,), width=10)
    circle(d, c, c, c - 15, outline=tuple(int(v * 0.5) for v in rim) + (255,), width=2)
    if icon == "shoot":     # nishon (crosshair)
        circle(d, c, c - n * 0.06, n * 0.17, outline=(255, 255, 255, 255), width=5)
        circle(d, c, c - n * 0.06, n * 0.04, fill=(255, 255, 255, 255))
        for dx, dy in [(0, -1), (1, 0), (0, 1), (-1, 0)]:
            x0, y0 = c + dx * n * 0.17, c - n * 0.06 + dy * n * 0.17
            x1, y1 = c + dx * n * 0.26, c - n * 0.06 + dy * n * 0.26
            d.line([(S(x0), S(y0)), (S(x1), S(y1))], fill=(255, 255, 255, 255), width=S(5))
    else:                   # uzatma (to'p + strelka)
        circle(d, c - n * 0.13, c - n * 0.06, n * 0.1, fill=(255, 255, 255, 255))
        d.line([(S(c - n * 0.02), S(c - n * 0.06)), (S(c + n * 0.2), S(c - n * 0.06))], fill=(255, 255, 255, 255), width=S(8))
        poly(d, [(c + n * 0.3, c - n * 0.06), (c + n * 0.17, c - n * 0.17), (c + n * 0.17, c + n * 0.05)], fill=(255, 255, 255, 255))
    return done(im, n)

def vgrad(w, h, top, bot):
    g = Image.new("RGBA", (w, h)); gd = ImageDraw.Draw(g)
    for y in range(h): gd.line([(0, y), (w, y)], fill=lerp(top, bot, y / max(1, h - 1)) + (255,))
    return g

def button(w, h, top, bot, border, r=26):
    im = canvas(w, h)
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S(w) - 1, S(h) - 1], radius=S(r), fill=255)
    im.paste(vgrad(S(w), S(h), top, bot), (0, 0), mask)
    hl = canvas(w, h)
    rrect(ImageDraw.Draw(hl), 5, 5, w - 5, h * 0.5, r - 4, fill=(255, 255, 255, 45))
    im = Image.alpha_composite(im, hl)
    rrect(ImageDraw.Draw(im), 1.5, 1.5, w - 1.5, h - 1.5, r, outline=border + (255,), width=3)
    return done(im, w, h)

def panel(w, h, r=22):
    im = canvas(w, h); d = ImageDraw.Draw(im)
    rrect(d, 1.5, 1.5, w - 1.5, h - 1.5, r, fill=(8, 18, 46, 225), outline=(46, 78, 140, 255), width=3.5)
    return done(im, w, h)

def score_shape(w, h, top, bot, rim):
    im = canvas(w, h)
    pts = [(26, 0), (w, 0), (w, h), (0, h)]
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).polygon([(S(x), S(y)) for x, y in pts], fill=255)
    im.paste(vgrad(S(w), S(h), top, bot), (0, 0), mask)
    d = ImageDraw.Draw(im)
    d.line([(S(26), S(2)), (S(w), S(2)), (S(w), S(h - 2)), (S(2), S(h - 2)), (S(26), S(2))], fill=rim + (255,), width=S(4))
    return done(im, w, h)

def goal(net, glow):
    w, h = 100, 250
    im = canvas(w, h); d = ImageDraw.Draw(im)
    d.rectangle([S(6), S(8), S(w), S(h - 8)], fill=net + (115,))
    for x in range(14, w, 12): d.line([(S(x), S(8)), (S(x), S(h - 8))], fill=glow + (200,), width=S(1.6))
    for y in range(20, h - 8, 12): d.line([(S(6), S(y)), (S(w), S(y))], fill=glow + (200,), width=S(1.6))
    for x0, y0, x1, y1 in [(0, 0, 9, h), (0, 0, w, 9), (0, h - 9, w, h)]:
        d.rectangle([S(x0), S(y0), S(x1), S(y1)], fill=(236, 240, 250, 255))
    circle(d, w - 6, 5, 6.5, fill=(255, 255, 255, 255)); circle(d, w - 6, h - 5, 6.5, fill=(255, 255, 255, 255))
    return done(im, w, h)

def logo():
    W, H = 640, 300
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    f1 = ImageFont.load_default(size=175); f2 = ImageFont.load_default(size=80)
    parts = [("2", (60, 170, 255)), ("v", (245, 245, 255)), ("2", (255, 70, 80))]
    widths = [d.textlength(t, font=f1) for t, _ in parts]
    x = (W - sum(widths)) / 2
    for (t, col), wd in zip(parts, widths):
        d.text((x, 135), t, font=f1, fill=col, anchor="lm", stroke_width=9, stroke_fill=(8, 20, 55))
        x += wd
    d.text((W / 2, 245), "FOOTBALL", font=f2, fill=(255, 255, 255), anchor="mm", stroke_width=7, stroke_fill=(8, 20, 55))
    b = soccer_ball(86); im.alpha_composite(b, (W // 2 - 43, 0))
    return im

items = {}
for name, col in PALETTE.items(): items["ball_" + name] = glossy(128, col)
items["marker"] = marker()
items["ball"] = soccer_ball(64)
items["shadow"] = shadow(64)
items["ring"] = ring(128)
items["joy_base"] = joy_base(256)
items["joy_knob"] = joy_knob(128)
items["btn_shoot"] = action_btn(192, (60, 235, 70), "shoot")
items["btn_pass"] = action_btn(192, (40, 150, 255), "pass")
items["btn_green"] = button(256, 96, (70, 215, 80), (28, 150, 45), (8, 70, 20))
items["btn_dark"] = button(256, 96, (34, 66, 120), (16, 34, 78), (70, 120, 190))
items["panel"] = panel(256, 128)
blue = score_shape(256, 96, (50, 120, 255), (20, 70, 215), (200, 225, 255))
items["score_blue"] = blue
items["score_red"] = ImageOps.mirror(score_shape(256, 96, (255, 80, 80), (215, 30, 45), (255, 215, 215)))
items["goal_blue"] = goal((30, 80, 220), (130, 175, 255))
items["goal_red"] = ImageOps.mirror(goal((215, 40, 55), (255, 150, 160)))
items["logo"] = logo()
items["white"] = Image.new("RGBA", (4, 4), (255, 255, 255, 255))

def pack(size):
    order = sorted(items.items(), key=lambda kv: -kv[1].height)
    x = y = row = 0; pos = {}
    for name, im in order:
        if x + im.width > size: x = 0; y += row; row = 0
        if y + im.height > size: return None
        pos[name] = (x, y); x += im.width + 2; row = max(row, im.height + 2)
    return pos

size = 1024
pos = pack(size)
if pos is None: size = 2048; pos = pack(size)
atlas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
lines = ["atlas.png", f"size: {size}, {size}", "format: RGBA8888", "filter: Linear, Linear", "repeat: none"]
for name, (x, y) in pos.items():
    im = items[name]; atlas.paste(im, (x, y))
    lines += [name, "  rotate: false", f"  xy: {x}, {y}", f"  size: {im.width}, {im.height}",
              f"  orig: {im.width}, {im.height}", "  offset: 0, 0", "  index: -1"]
atlas.save(os.path.join(OUT, "atlas.png"))
open(os.path.join(OUT, "atlas.atlas"), "w").write("\n".join(lines) + "\n")

# o't: vertikal chiziqlar (dizayndagi kabi to'q-yorqin yashil), NPOT emas
random.seed(7)
g = Image.new("RGB", (128, 128)); px = g.load()
for yy in range(128):
    for xx in range(128):
        base = (38, 134, 44) if xx < 64 else (45, 148, 52)
        n = random.randint(-3, 3)
        px[xx, yy] = (base[0] + n, base[1] + n, base[2] + n)
g.save(os.path.join(OUT, "grass.png"))
print("assets OK, atlas", size, "ta", len(items), "region")
