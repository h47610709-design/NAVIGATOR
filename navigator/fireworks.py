"""Haqiqiy animatsion salyut GIF'larini (Pillow bilan) yaratish.

Agar .env da tashqi sticker/GIF berilmagan bo'lsa ham, bot har doim
o'zining animatsion salyutini yuboradi. Fayllar bir marta yaratiladi,
Telegram'ga bir marta yuklanadi va keyin ``file_id`` orqali qayta ishlatiladi.
"""
from __future__ import annotations

import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ASSETS_DIR = Path(__file__).parent / "assets" / "generated"

PALETTES = [
    [(255, 70, 90), (255, 200, 60), (90, 200, 255), (180, 110, 255)],
    [(255, 215, 0), (255, 120, 40), (255, 255, 255), (255, 80, 160)],
    [(80, 255, 160), (60, 170, 255), (255, 240, 120), (255, 110, 220)],
]
CAPTIONS = ["+10", "BARAKALLA!", "ZO'R!"]


def _font(size: int):
    for name in ("DejaVuSans-Bold.ttf", "Arial Bold.ttf", "arialbd.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    try:
        return ImageFont.load_default(size=size)
    except TypeError:  # eski Pillow
        return ImageFont.load_default()


def make_fireworks_gif(path: Path, seed: int = 0, size: int = 320, frames: int = 30, caption: str = "+10") -> Path:
    rng = random.Random(seed)
    palette = PALETTES[seed % len(PALETTES)]
    w = h = size
    bursts = []
    for i in range(5):
        bursts.append({
            "x": rng.randint(int(w * 0.18), int(w * 0.82)),
            "y": rng.randint(int(h * 0.15), int(h * 0.55)),
            "start": i * 5 + rng.randint(0, 2),
            "color": palette[i % len(palette)],
            "n": rng.randint(26, 38),
            "speed": rng.uniform(4.2, 6.0),
        })
    stars = [(rng.randrange(w), rng.randrange(h), rng.randint(60, 140)) for _ in range(40)]
    font = _font(int(size * 0.11))
    images = []
    for f in range(frames):
        img = Image.new("RGB", (w, h), (10, 12, 38))
        d = ImageDraw.Draw(img)
        for sx, sy, b in stars:
            tw = int(b * (0.6 + 0.4 * math.sin(f * 0.7 + sx)))
            d.point((sx, sy), fill=(tw, tw, tw + 30 if tw < 225 else 255))
        for bu in bursts:
            age = f - bu["start"]
            if age < -6:
                continue
            if age < 0:  # ko'tarilayotgan raketa
                py = bu["y"] + (-age) * (h - bu["y"]) / 6
                d.ellipse((bu["x"] - 2, py - 2, bu["x"] + 2, py + 2), fill=(255, 240, 200))
                continue
            life = 14
            if age > life:
                continue
            fade = 1 - age / life
            r, g, b = bu["color"]
            col = (int(r * fade + 10), int(g * fade + 12), int(b * fade + 38))
            for k in range(bu["n"]):
                ang = 2 * math.pi * k / bu["n"]
                dist = bu["speed"] * age * (1 - age / (life * 2.4))
                px = bu["x"] + math.cos(ang) * dist * 2.6
                py = bu["y"] + math.sin(ang) * dist * 2.6 + 0.18 * age * age
                rad = max(1, int(3 * fade + 0.5))
                # iz (trail)
                tx = bu["x"] + math.cos(ang) * dist * 2.0
                ty = bu["y"] + math.sin(ang) * dist * 2.0 + 0.14 * age * age
                d.line((tx, ty, px, py), fill=(col[0] // 2 + 5, col[1] // 2 + 6, col[2] // 2 + 19), width=1)
                d.ellipse((px - rad, py - rad, px + rad, py + rad), fill=col)
            if age < 3:  # chaqnash
                fl = 10 - age * 3
                d.ellipse((bu["x"] - fl, bu["y"] - fl, bu["x"] + fl, bu["y"] + fl), fill=(255, 255, 230))
        # pastki yozuv
        pulse = 1 + 0.06 * math.sin(f * 0.6)
        bbox = d.textbbox((0, 0), caption, font=font)
        tw, th = (bbox[2] - bbox[0]) * pulse, bbox[3] - bbox[1]
        tx, ty = (w - tw) / 2, h * 0.78
        d.text((tx + 2, ty + 2), caption, font=font, fill=(0, 0, 0))
        d.text((tx, ty), caption, font=font, fill=(255, 215, 64))
        images.append(img.convert("P", palette=Image.Palette.ADAPTIVE, colors=64))
    path.parent.mkdir(parents=True, exist_ok=True)
    images[0].save(path, save_all=True, append_images=images[1:], duration=70, loop=0, optimize=True, disposal=2)
    return path


def ensure_fireworks(variants: int = 3) -> list[Path]:
    """Salyut GIF'lari mavjudligini ta'minlaydi (yo'q bo'lsa yaratadi)."""
    out = []
    for i in range(variants):
        p = ASSETS_DIR / f"fireworks_{i}.gif"
        if not p.exists() or p.stat().st_size == 0:
            make_fireworks_gif(p, seed=i, caption=CAPTIONS[i % len(CAPTIONS)])
        out.append(p)
    return out


if __name__ == "__main__":
    for p in ensure_fireworks():
        print(p, p.stat().st_size, "bayt")
