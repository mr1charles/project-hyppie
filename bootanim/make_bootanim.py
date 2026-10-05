#!/usr/bin/env python3
"""Project Hyppie startup pages: credits, what works, what doesn't. Plays once in front of HyperOS's own boot animation.

Builds overlay-hyperos/vendor/etc/hyppie/bootanimation.zip (stored, not compressed: the boot animation requires it).
The original HyperOS frames are kept (repeated to the new frame rate) as the final, looping part.
"""
import math, os, shutil, subprocess, sys, zipfile
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

P = Path.home() / "ProjectHyppie"
OUT = P / "overlay-hyperos/vendor/etc/hyppie/bootanimation.zip"
ORIG = P / "bootanim/orig.zip"            # HyperOS's own bootanimation.zip (pulled from /product/media)
W, H, FPS = 1200, 2670, 20
BG, FG, MUTE = (11, 6, 20), (239, 234, 248), (169, 156, 196)
VIOLET, VIOLET2, GREEN, AMBER = (155, 92, 255), (201, 167, 255), (69, 212, 131), (255, 182, 72)
NOTO, NOTOB = "/usr/share/fonts/noto/NotoSans-Regular.ttf", "/usr/share/fonts/noto/NotoSans-Bold.ttf"
SYM = "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf"
F = lambda p, s: ImageFont.truetype(p, s)

def background():
    img = Image.new("RGB", (W, H), BG)
    glow = Image.new("RGB", (W, H), (0, 0, 0)); d = ImageDraw.Draw(glow)
    d.ellipse((W / 2 - 520, 250 - 520, W / 2 + 520, 250 + 520), fill=(70, 34, 130))
    glow = glow.filter(ImageFilter.GaussianBlur(220))
    return Image.blend(img, Image.composite(glow, img, glow.convert("L")), 0.9)

def spark(img, cx, cy, r, color=VIOLET2):
    pts = [(cx + (r if i % 2 == 0 else r * .22) * math.cos(math.pi / 2 + i * math.pi / 4),
            cy - (r if i % 2 == 0 else r * .22) * math.sin(math.pi / 2 + i * math.pi / 4)) for i in range(8)]
    g = Image.new("RGB", img.size, (0, 0, 0)); ImageDraw.Draw(g).polygon(pts, fill=VIOLET)
    g = g.filter(ImageFilter.GaussianBlur(r * .35)); img.paste(Image.blend(img, Image.composite(g, img, g.convert("L")), 1), (0, 0))
    ImageDraw.Draw(img).polygon(pts, fill=color)

def center(d, y, text, font, fill):
    w = d.textlength(text, font=font); d.text(((W - w) / 2, y), text, font=font, fill=fill); return y

def page_title():
    img = background(); d = ImageDraw.Draw(img)
    spark(img, W / 2, 760, 150)
    d = ImageDraw.Draw(img)
    center(d, 1000, "Project Hyppie", F(NOTOB, 118), FG)
    center(d, 1160, "HyperOS 3 for the Galaxy A13", F(NOTO, 56), VIOLET2)
    center(d, 1290, "SM-A135M  ·  Public beta 1.0", F(NOTO, 42), MUTE)
    center(d, 2330, "Unofficial community port", F(NOTO, 36), MUTE)
    return img

def rows(title, subtitle, items, mark, color, footer=None, size=44, gap=118, top=520):
    img = background(); d = ImageDraw.Draw(img)
    center(d, 250, title, F(NOTOB, 96), FG)
    center(d, 380, subtitle, F(NOTO, 40), MUTE)
    y = top
    for it in items:
        head, sub = it if isinstance(it, tuple) else (it, None)
        ch = gap - 24
        d.rounded_rectangle((90, y, W - 90, y + ch), 40, fill=(24, 14, 42), outline=(48, 30, 80), width=2)
        d.text((150, y + ch / 2 - 40), mark, font=F(SYM, 70), fill=color)
        hy = y + (ch / 2 - size * .75 if not sub else ch / 2 - size * .95)
        d.text((260, hy), head, font=F(NOTOB, size), fill=FG)
        if sub: d.text((260, hy + size * 1.25), sub, font=F(NOTO, int(size * .68)), fill=MUTE)
        y += gap
    if footer:
        for i, line in enumerate(footer):
            center(d, 2270 + i * 52, line, F(NOTO, 36), MUTE if i else VIOLET2)
    return img

def page_credits():
    img = background(); d = ImageDraw.Draw(img)
    center(d, 250, "Credits", F(NOTOB, 100), FG); center(d, 385, "Built on the work of many people", F(NOTO, 40), MUTE)
    items = [("Xiaomi  ·  xiaomi.eu", "HyperOS 3 base system"), ("LineageOS", "Galaxy A21s 64-bit Exynos 850 vendor"),
             ("Galaxy A13 community", "device tree and kernel 4.19"), ("TWRP  ·  Magisk", "recovery and root"),
             ("Aperture", "camera app from LineageOS"), ("mr1charles", "Project Hyppie, AI Assistant, build tools")]
    y = 520
    for head, sub in items:
        d.rounded_rectangle((90, y, W - 90, y + 228), 40, fill=(24, 14, 42), outline=(48, 30, 80), width=2)
        d.ellipse((150, y + 98, 184, y + 132), fill=VIOLET)
        d.text((240, y + 36), head, font=F(NOTOB, 56), fill=FG); d.text((240, y + 124), sub, font=F(NOTO, 38), fill=MUTE)
        y += 262
    center(d, 2330, "HyperOS and Galaxy are trademarks of Xiaomi and Samsung", F(NOTO, 34), MUTE)
    return img

def page_works():
    return rows("What works", "Tested on a real SM-A135M", [
        "Touch and display", "Wi-Fi, Bluetooth, GPS", "4 cameras (Aperture)", "16 sensors", "Speaker, mic, vibration",
        "Super Island and Liquid Glass", "Magisk root", "microSD as internal storage", "AI Assistant"], "✓", GREEN, size=56, gap=200, top=520)

def page_limits():
    return rows("Not supported yet", "This is a beta", [
        ("Xiaomi Camera app", "locked to Xiaomi phones, use Aperture"), ("Fingerprint", "detected, enrolling untested"),
        ("SELinux is permissive", "less secure than stock"), ("Calls, SMS, mobile data", "not tested without a SIM"),
        ("Auto-brightness", "the A13 has no light sensor"), ("China-only HyperAI features", "Xiaomi cloud services")],
        "✗", AMBER, footer=["HyperOS is starting. First boot takes a few minutes.", "mr1charles.github.io/project-hyppie"], size=54, gap=262, top=520)

PAGES = [(page_title(), 3.2), (page_credits(), 5.0), (page_works(), 5.0), (page_limits(), 6.0)]

def fade(img, k):    # k in 0..1, fade up from the background colour
    return Image.blend(Image.new("RGB", img.size, BG), img, k)

def main():
    tmp = P / "bootanim/build"; shutil.rmtree(tmp, ignore_errors=True); tmp.mkdir(parents=True)
    desc = [f"{W} {H} {FPS}"]
    for i, (img, secs) in enumerate(PAGES):
        part = tmp / f"part{i}"; part.mkdir()
        n = 8
        for f in range(n):                       # eased fade-in
            k = (f + 1) / n; k = 1 - (1 - k) ** 3
            fade(img, k).save(part / f"{f:03d}.png", optimize=True)
        pause = max(1, round(secs * FPS) - n)
        desc.append(f"p 1 {pause} part{i}")
    # keep HyperOS's own boot animation as the last, looping part (frames repeated to the new fps)
    with zipfile.ZipFile(ORIG) as z:
        od = z.read("desc.txt").decode().split("\n")
        ofps = int(od[0].split()[2]); frames = sorted(n for n in z.namelist() if n.startswith("part0/") and n.endswith(".png"))
        rep = max(1, FPS // ofps); k = 0
        last = tmp / f"part{len(PAGES)}"; last.mkdir()
        for n in frames:
            for _ in range(rep):
                (last / f"{k:03d}.png").write_bytes(z.read(n)); k += 1
        po = od[1].split()                       # e.g. "p 0 5 part0"
        desc.append(f"p 0 {int(po[2]) * rep} part{len(PAGES)}")
    (tmp / "desc.txt").write_text("\n".join(desc) + "\n")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    if OUT.exists(): OUT.unlink()
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_STORED) as z:     # stored: required by the boot animation
        z.write(tmp / "desc.txt", "desc.txt")
        for part in sorted(tmp.glob("part*")):
            for f in sorted(part.glob("*.png")): z.write(f, f"{part.name}/{f.name}")
    print("wrote", OUT, OUT.stat().st_size // 1024, "KB;", " | ".join(desc))
    for i, (img, _) in enumerate(PAGES): img.resize((300, 667)).save(P / f"bootanim/page{i}.png")

main()
