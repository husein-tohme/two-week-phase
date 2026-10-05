"""Draws the Two Week Phase icon (512x512) and a banner (1280x640) with a galaxy look. Run: python art/make_art.py"""
import math
import random
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
G = 64            # the icon is drawn on a 64x64 pixel grid, then enlarged 8x so it stays crisp
SCALE = 8

# a classic 9x9 game heart: # outline, R fill, D darker fill, H highlight
HEART = [
    ".##...##.",
    "#RR#.#RR#",
    "#HRR#RRR#",
    "#RRRRRRR#",
    "#RRRRRRD#",
    ".#RRRRD#.",
    "..#RRD#..",
    "...#D#...",
    "....#....",
]
ALIVE = {"#": (46, 6, 22), "R": (236, 40, 62), "D": (170, 18, 44), "H": (255, 170, 180)}
GHOST = {"#": (150, 170, 235), "R": (228, 236, 255), "D": (196, 208, 248), "H": (255, 255, 255)}


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def galaxy_color(x, y, size, cx, cy, rng_noise):
    """Colour of one point of a spiral galaxy seen at a tilt. Coordinates are in pixels of a size x size picture."""
    dx, dy = (x - cx) / (size * 0.5), (y - cy) / (size * 0.5)
    dy /= 0.62                                   # tilt: squash vertically so the disc looks like it lies at an angle
    ang = 0.5                                    # rotate the whole galaxy a little
    rx, ry = dx * math.cos(ang) - dy * math.sin(ang), dx * math.sin(ang) + dy * math.cos(ang)
    r = math.hypot(rx, ry)
    theta = math.atan2(ry, rx)
    arms = 0.5 + 0.5 * math.cos(2 * (theta - 3.3 * math.log(r + 0.05)))     # two spiral arms
    arms = arms ** 2.2
    disc = math.exp(-((r / 0.72) ** 2))          # the disc fades toward the edge
    core = math.exp(-((r / 0.16) ** 2))          # bright centre
    glow = math.exp(-((r / 0.42) ** 2))
    b = 0.95 * arms * disc + 0.25 * glow + 1.25 * core + rng_noise * 0.10 * disc

    space = (6, 5, 20)
    deep = (38, 22, 96)       # outer arms: deep violet-blue
    mid = (150, 52, 190)      # purple
    hot = (255, 110, 190)     # pink
    white = (255, 244, 225)   # core
    if b < 0.18:
        c = mix(space, deep, b / 0.18)
    elif b < 0.45:
        c = mix(deep, mid, (b - 0.18) / 0.27)
    elif b < 0.8:
        c = mix(mid, hot, (b - 0.45) / 0.35)
    else:
        c = mix(hot, white, (b - 0.8) / 0.5)
    # a touch of blue on the far side of the arms, so it isn't all one colour
    c = mix(c, (70, 120, 255), 0.22 * disc * (0.5 + 0.5 * math.sin(theta * 2 + 1.0)) * (1 - core))
    return c


def galaxy(size, cx, cy, seed=7, stars=70):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            px[x, y] = galaxy_color(x, y, size, cx, cy, rnd.random()) + (255,)
    for _ in range(stars):                       # stars scattered in front of everything
        x, y = rnd.randrange(size), rnd.randrange(size)
        k = rnd.random()
        px[x, y] = (255, 255, 255, 255) if k > 0.45 else ((190, 205, 255, 255) if k > 0.2 else (255, 215, 235, 255))
    return img


def heart(img, left, top, colors, size=3, alpha=255):
    px = img.load()
    for row, line in enumerate(HEART):
        for col, ch in enumerate(line):
            if ch == ".":
                continue
            r, g, b = colors[ch]
            for dy in range(size):
                for dx in range(size):
                    x, y = left + col * size + dx, top + row * size + dy
                    if alpha == 255:
                        px[x, y] = (r, g, b, 255)
                    else:                                    # see-through: blend with what is behind
                        br, bg, bb, _ = px[x, y]
                        a = alpha / 255
                        px[x, y] = (int(r * a + br * (1 - a)), int(g * a + bg * (1 - a)), int(b * a + bb * (1 - a)), 255)


def make_icon():
    img = galaxy(G, G * 0.5, G * 0.38)       # the galaxy is the picture; it sits a little above the middle
    heart(img, 12, 44, ALIVE, size=2)        # the owner who is alive
    heart(img, 34, 42, GHOST, size=2, alpha=215)   # the owner who died: a ghost, floating a little higher
    return img.resize((G * SCALE, G * SCALE), Image.NEAREST)


def font(size, bold=True):
    for name in (("segoeuib.ttf", "arialbd.ttf") if bold else ("segoeui.ttf", "arial.ttf")):
        try:
            return ImageFont.truetype("C:/Windows/Fonts/" + name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def make_banner(icon):
    w, h = 1280, 640
    # a wide, faint galaxy behind the text (drawn small, then enlarged so it keeps the same pixel look)
    small = galaxy(160, 160 * 0.80, 160 * 0.30, seed=11, stars=120).crop((0, 0, 160, 80))
    img = small.resize((w, h), Image.NEAREST)
    shade = Image.new("RGBA", (w, h), (6, 5, 20, 185))       # darken it so the words are easy to read
    img = Image.alpha_composite(img, shade)
    img.paste(icon.resize((448, 448), Image.NEAREST), (72, 96))
    d = ImageDraw.Draw(img)
    d.rectangle((72, 96, 519, 543), outline=(120, 90, 200), width=3)
    d.text((580, 150), "TWO WEEK", font=font(104), fill=(255, 255, 255))
    d.text((580, 262), "PHASE", font=font(104), fill=(255, 96, 170))
    d.text((584, 400), "One hardcore world. Two owners.", font=font(36, False), fill=(226, 230, 255))
    d.text((584, 448), "Neither of you can take it from the other.", font=font(36, False), fill=(226, 230, 255))
    return img


if __name__ == "__main__":
    icon = make_icon()
    icon.save(HERE / "icon.png")
    make_banner(icon).convert("RGB").save(HERE / "banner.png")
    print("wrote icon.png and banner.png in", HERE)
