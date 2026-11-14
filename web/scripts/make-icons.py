"""One-off: draws ic_launcher_background.xml + ic_launcher_foreground.xml (108x108 viewport) into
web/public/icon-192.png and icon-512.png. The shapes are copied from the two vector drawables by hand
(PIL cannot read Android vector XML); curves are flattened to 64 segments, drawn 4x and downsampled.
Run: python web/scripts/make-icons.py
"""
import math
import os
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), '..', 'public')
VIEW = 108.0
STEPS = 64


def hexcol(s):
    return tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))


def group(p, pivot=(50, 66), rot=30, scale=1.2):
    """Android <group>: scale then rotate around the pivot."""
    x, y = (p[0] - pivot[0]) * scale, (p[1] - pivot[1]) * scale
    a = math.radians(rot)
    return (pivot[0] + x * math.cos(a) - y * math.sin(a), pivot[1] + x * math.sin(a) + y * math.cos(a))


def quad(p0, c, p1):
    pts = []
    for i in range(STEPS + 1):
        t = i / STEPS
        pts.append(((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0],
                    (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1]))
    return pts


def ellipse(cx, cy, rx, ry):
    return [(cx + rx * math.cos(2 * math.pi * i / STEPS), cy + ry * math.sin(2 * math.pi * i / STEPS)) for i in range(STEPS)]


def rect(x, y, w, h):
    return [(x, y), (x + w, y), (x + w, y + h), (x, y + h)]


def draw(size):
    big = size * 4
    k = big / VIEW
    img = Image.new('RGB', (big, big), hexcol('#0E1A2B'))
    d = ImageDraw.Draw(img)

    def poly(pts, fill, g=False):
        d.polygon([tuple(c * k for c in (group(p) if g else p)) for p in pts], fill=hexcol(fill))

    def line(a, b, fill, width):
        a, b = group(a), group(b)
        w = width * 1.2 * k
        d.line([a[0] * k, a[1] * k, b[0] * k, b[1] * k], fill=hexcol(fill), width=round(w))

    # background stars
    for cx, cy, r in ((30, 28, 1), (44, 22, 0.8), (82, 52, 0.8), (24, 46, 0.8)):
        poly(ellipse(cx, cy, r, r), '#5C7090')
    # moon
    poly(ellipse(62, 25, 4.5, 4.5), '#FFE9A8')
    # foot and mast
    poly(rect(47, 64, 6, 16), '#7F8A99')
    poly([(40, 86), (44, 79), (56, 79), (60, 86)], '#9AA5B4')
    # group (rotated, scaled)
    line((27, 52), (50, 37), '#C9D2DE', 1.2)
    line((73, 52), (50, 37), '#C9D2DE', 1.2)
    poly(quad((24, 52), (50, 84), (76, 52)), '#D7DEE8', g=True)
    poly(ellipse(50, 52, 26, 5), '#F4F7FB', g=True)
    line((50, 53), (50, 39), '#C9D2DE', 2)
    poly(rect(47.5, 34, 5, 5), '#FFB74D', g=True)
    return img.resize((size, size), Image.LANCZOS)


for s in (192, 512):
    draw(s).save(os.path.join(OUT, f'icon-{s}.png'))
