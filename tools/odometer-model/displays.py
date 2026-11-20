"""Renderers for the readouts of an instrument cluster: seven-segment LCD, dot matrix, TFT text and the rolls of a
mechanical counter. Each renderer returns a transparent RGBA image and the box of every character in it, so the
scene can paste it anywhere and still knows where each digit is.
"""
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT_DIR = Path(__file__).resolve().parent / "fonts"
FONTS = {
    "mono": "NotoSansMono-Regular.ttf",
    "mono-bold": "NotoSansMono-Bold.ttf",
    "round": "Quicksand-Medium.ttf",
    "round-bold": "Quicksand-Bold.ttf",
}
_fonts = {}


def font(name, size):
    key = (name, max(4, int(round(size))))
    if key not in _fonts:
        _fonts[key] = ImageFont.truetype(str(FONT_DIR / FONTS[name]), key[1])
    return _fonts[key]


def font_for_digit_height(name, h):
    """Font whose digits are h pixels tall."""
    probe = font(name, 200)
    top, bottom = probe.getbbox("0")[1::2]
    return font(name, 200 * h / (bottom - top))


@dataclass
class Text:
    image: Image.Image          # RGBA, transparent where nothing is drawn
    chars: list                 # [(char, (x0, y0, x1, y1))] for every visible character, spaces left out


def rgba(color, alpha=1.0):
    return tuple(int(c) for c in color) + (int(round(255 * alpha)),)


# --- seven segments ---------------------------------------------------------------------------------------------

SEGMENTS = {
    "0": "abcdef", "1": "bc", "2": "abdeg", "3": "abcdg", "4": "bcfg", "5": "acdfg", "6": "acdefg", "7": "abc",
    "8": "abcdefg", "9": "abcdfg", "-": "g", " ": "", "C": "adef", "°": "abfg", "E": "adefg", "F": "aefg",
    "H": "bcefg", "L": "def", "P": "abefg", "A": "abcefg", "r": "eg", "n": "ceg", "o": "cdeg", "t": "defg",
    "d": "bcdeg", "b": "cdefg", "U": "bcdef",
}
# Shapes that differ between displays: a 7 with a left bar, a 6 and 9 without their tail.
SEGMENT_VARIANTS = {"7": "abcf", "6": "cdefg", "9": "abcfg"}


def _segment_polygons(w, h, t, gap, slant):
    hw = t / 2
    left, right, top, mid, bottom = hw, w - hw, hw, h / 2, h - hw

    def hor(x0, x1, y):
        return [(x0, y), (x0 + hw, y - hw), (x1 - hw, y - hw), (x1, y), (x1 - hw, y + hw), (x0 + hw, y + hw)]

    def ver(x, y0, y1):
        return [(x, y0), (x + hw, y0 + hw), (x + hw, y1 - hw), (x, y1), (x - hw, y1 - hw), (x - hw, y0 + hw)]

    segs = {
        "a": hor(left + gap, right - gap, top), "g": hor(left + gap, right - gap, mid),
        "d": hor(left + gap, right - gap, bottom),
        "f": ver(left, top + gap, mid - gap), "b": ver(right, top + gap, mid - gap),
        "e": ver(left, mid + gap, bottom - gap), "c": ver(right, mid + gap, bottom - gap),
    }
    return {k: [(x + (h - y) * slant, y) for x, y in p] for k, p in segs.items()}


def seven_segment(text, h, style):
    """style: fg, ghost (alpha of unlit segments), width, thickness, slant, spacing (all relative to h), variants."""
    w = h * style["width"]
    t = h * style["thickness"]
    gap = t * 0.12
    slant = style["slant"]
    space = h * style["spacing"]
    polys = _segment_polygons(w, h, t, gap, slant)
    lean = h * slant
    pad = int(t)
    cells = []          # (char, x, kind)
    x = pad
    for i, c in enumerate(text):
        if c == ".":
            cells.append((c, x - space / 2 - t * 0.6, "dot"))
            continue
        if c == ":":
            cells.append((c, x, "colon"))
            x += t * 2 + space
            continue
        cells.append((c, x, "digit"))
        x += w + space
    width = int(x - space + lean + 2 * pad)
    img = Image.new("RGBA", (max(width, 1), int(h + 2 * pad)), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    on = rgba(style["fg"])
    off = rgba(style["fg"], style["ghost"])
    chars = []
    for c, cx, kind in cells:
        if kind == "dot":
            r = t * 0.55
            draw.ellipse([cx - r, pad + h - 2 * r, cx + r, pad + h], fill=on)
            continue
        if kind == "colon":
            r = t * 0.55
            for yy in (h * 0.3, h * 0.7):
                xx = cx + t + (h - yy) * slant
                draw.ellipse([xx - r, pad + yy - r, xx + r, pad + yy + r], fill=on)
            chars.append((c, (cx, pad, cx + 2 * t + lean, pad + h)))
            continue
        lit = style.get("variants", {}).get(c, SEGMENTS.get(c, ""))
        for name, poly in polys.items():
            if name not in lit and style["ghost"] <= 0:
                continue
            draw.polygon([(cx + px, pad + py) for px, py in poly], fill=on if name in lit else off)
        if c != " ":
            chars.append((c, (cx, pad, cx + w + lean, pad + h)))
    return Text(img, chars)


# --- dot matrix -------------------------------------------------------------------------------------------------

DOT_FONT = {
    "0": "01110 10001 10011 10101 11001 10001 01110", "1": "00100 01100 00100 00100 00100 00100 01110",
    "2": "01110 10001 00001 00010 00100 01000 11111", "3": "11111 00010 00100 00010 00001 10001 01110",
    "4": "00010 00110 01010 10010 11111 00010 00010", "5": "11111 10000 11110 00001 00001 10001 01110",
    "6": "00110 01000 10000 11110 10001 10001 01110", "7": "11111 00001 00010 00100 01000 01000 01000",
    "8": "01110 10001 10001 01110 10001 10001 01110", "9": "01110 10001 10001 01111 00001 00010 01100",
    "A": "01110 10001 10001 11111 10001 10001 10001", "B": "11110 10001 10001 11110 10001 10001 11110",
    "C": "01110 10001 10000 10000 10000 10001 01110", "D": "11100 10010 10001 10001 10001 10010 11100",
    "E": "11111 10000 10000 11110 10000 10000 11111", "G": "01110 10001 10000 10111 10001 10001 01111",
    "H": "10001 10001 10001 11111 10001 10001 10001", "I": "01110 00100 00100 00100 00100 00100 01110",
    "K": "10001 10010 10100 11000 10100 10010 10001", "L": "10000 10000 10000 10000 10000 10000 11111",
    "M": "10001 11011 10101 10101 10001 10001 10001", "N": "10001 10001 11001 10101 10011 10001 10001",
    "O": "01110 10001 10001 10001 10001 10001 01110", "P": "11110 10001 10001 11110 10000 10000 10000",
    "R": "11110 10001 10001 11110 10100 10010 10001", "S": "01111 10000 10000 01110 00001 00001 11110",
    "T": "11111 00100 00100 00100 00100 00100 00100", "U": "10001 10001 10001 10001 10001 10001 01110",
    ":": "00000 01100 01100 00000 01100 01100 00000", ".": "00000 00000 00000 00000 00000 01100 01100",
    ",": "00000 00000 00000 00000 01100 00100 01000", "-": "00000 00000 00000 11111 00000 00000 00000",
    "°": "01100 10010 10010 01100 00000 00000 00000", "/": "00001 00010 00010 00100 01000 01000 10000",
    " ": "00000 00000 00000 00000 00000 00000 00000",
    "F": "11111 10000 10000 11110 10000 10000 10000", "W": "10001 10001 10001 10101 10101 10101 01010",
}
NARROW = {":": (1, 2), ".": (1, 2), ",": (1, 3)}   # columns actually used by narrow characters


def dot_matrix(text, h, style):
    """style: fg, ghost, dot (radius relative to the pitch), square (bool)."""
    pitch = h / 7
    r = pitch * style["dot"]
    pad = int(pitch)
    cols = []
    x = pad
    for c in text.upper():
        rows = DOT_FONT.get(c, DOT_FONT[" "]).split()
        first, last = NARROW.get(c, (0, 4))
        cols.append((c, x - first * pitch, rows, first, last))
        x += (last - first + 2) * pitch
    img = Image.new("RGBA", (int(x + pad), int(h + 2 * pad)), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    on = rgba(style["fg"])
    off = rgba(style["fg"], style["ghost"])
    chars = []
    for c, cx, rows, first, last in cols:
        for j, row in enumerate(rows):
            for i in range(first, last + 1):
                lit = row[i] == "1"
                if not lit and (style["ghost"] <= 0 or c in NARROW):
                    continue
                px, py = cx + (i + 0.5) * pitch, pad + (j + 0.5) * pitch
                box = [px - r, py - r, px + r, py + r]
                (draw.rectangle if style["square"] else draw.ellipse)(box, fill=on if lit else off)
        if c != " ":
            chars.append((c, (cx + first * pitch, pad, cx + (last + 1) * pitch, pad + h)))
    return Text(img, chars)


# --- TFT / printed text ------------------------------------------------------------------------------------------

def font_text(text, h, style):
    """Text in a TTF font whose digits are h pixels tall. style: fg, font, tracking (relative to h)."""
    f = font_for_digit_height(style["font"], h)
    top, bottom = f.getbbox("0")[1::2]
    pad = int(h * 0.45)
    track = h * style.get("tracking", 0.0)
    advances = []
    for c in text:
        adv = f.getlength(c) * (0.35 if c == "\u2009" else 1.0)
        advances.append(adv)
    width = int(sum(advances) + track * max(len(text) - 1, 0) + 2 * pad)
    img = Image.new("RGBA", (max(width, 1), int(h + 2 * pad)), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    x = pad
    chars = []
    y = pad - top
    for c, adv in zip(text, advances):
        if c not in " \u2009":
            draw.text((x, y), c, font=f, fill=rgba(style["fg"]))
            chars.append((c, (x, pad, x + adv, pad + (bottom - top))))
        x += adv + track
    return Text(img, chars)


def render(kind, text, h, style):
    return {"seg": seven_segment, "dot": dot_matrix, "font": font_text}[kind](text, h, style)


# --- mechanical counter -----------------------------------------------------------------------------------------

def drum_counter(rolls, h, style):
    """Rolls of a mechanical counter, each (digit, turn, colours) with turn in [0, 1) the share of the way to the
    next digit. Returns an opaque window; the boxes are the visible part of each roll."""
    h = int(round(h))
    w = int(h * style["roll_width"])
    sep = max(1, int(h * style["separator"]))
    pad = int(h * style["window_pad"])
    glyph_h = h * style["glyph"]
    pitch = glyph_h * style["pitch"]
    f = font_for_digit_height(style["font"], glyph_h)
    n = len(rolls)
    width = 2 * pad + n * w + (n - 1) * sep
    window = Image.new("RGBA", (width, h + 2 * pad), rgba(style["window"]))
    yy = (np.arange(h) - h / 2) / (h / 2)
    shade = (1 - style["shading"] * yy ** 2)[:, None, None]
    chars = []
    for i, (digit, turn, (fg, bg)) in enumerate(rolls):
        cell = Image.new("RGB", (w, h), tuple(bg))
        draw = ImageDraw.Draw(cell)
        for k in range(-1, 3):
            cy = h / 2 + (k - turn) * pitch + style["baseline"] * h
            draw.text((w / 2 + style["offset"][i] * w, cy + glyph_h / 2), str((digit + k) % 10), font=f,
                      fill=tuple(fg), anchor="ms")
        arr = np.asarray(cell, dtype=np.float32) * shade
        cell = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
        x = pad + i * (w + sep)
        window.paste(cell, (x, pad))
        chars.append((str(digit), (x, pad, x + w, pad + h)))
    return Text(window, chars)
