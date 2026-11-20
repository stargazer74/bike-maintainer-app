"""Synthetic odometer photos with labels, for training and testing the ODO line detector and the digit reader
(Epic stargazer74/bike-maintainer-backend#129, app#37). Not used in the app build, see docs/odometer-model.md.

usage: python generate.py --count N --seed S [--out DIR] [--kinds lcd7,drum,dotmatrix,tft] [--size 640x480]
       python generate.py --testset            # the fixed test set: --seed 37000 --count 400 --out testset

Writes <seed>-<index>.jpg and .json per image (see docs/odometer-model.md for the label format). Every image is
drawn from its own random stream (seed, index): the same call gives the same images, and any index can be
regenerated alone.
"""
import argparse
import json
import math
import os
import sys
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass, field

import numpy as np
from PIL import Image, ImageDraw

from displays import Text, drum_counter, font_text, render, rgba
from photo import apply, photograph

VERSION = 1
KINDS = ["lcd7", "drum", "dotmatrix", "tft"]
RENDERER = {"lcd7": "seg", "dotmatrix": "dot", "tft": "font"}
S = 2                                            # scenes are drawn at twice the size and scaled down (anti-aliasing)
TESTSET = {"seed": 37000, "count": 400, "out": "testset"}

GLOW = [(255, 160, 50), (255, 245, 230), (110, 200, 255), (120, 255, 170), (255, 80, 50), (230, 230, 255)]
FONTS = ["mono", "mono-bold", "round", "round-bold"]


def pick(rng, items, p=None):
    return items[int(rng.choice(len(items), p=p))]


def jitter(rng, color, d=14):
    return tuple(int(c) for c in np.clip(np.array(color) + rng.integers(-d, d + 1, 3), 0, 255))


# --- values -----------------------------------------------------------------------------------------------------

def odo_value(rng, digits):
    if rng.random() < 0.08:
        return int(rng.integers(0, 1000))        # new vehicle
    return int(min(10 ** rng.uniform(3, digits), 10 ** digits - 1))


def odo_text(rng, kind, value, cells, tenths):
    """The ODO line as shown, and per shown character its role: int, tenths or None (separator, blank)."""
    digits = str(value)
    if kind == "tft" and rng.random() < 0.3 or kind == "dotmatrix" and rng.random() < 0.1:
        sep = pick(rng, [".", ",", "\u2009"] if kind == "tft" else [".", ","])
        groups = []
        while digits:
            groups.insert(0, digits[-3:])
            digits = digits[:-3]
        digits = sep.join(groups)
    elif kind == "lcd7" or kind == "dotmatrix" and rng.random() < 0.5:
        digits = digits.rjust(cells)
    roles = [("int" if c.isdigit() else None) for c in digits]
    if tenths is not None:
        digits += "." + str(tenths)
        roles += [None, "tenths"]
    return digits, roles


def drum_state(rng, n, has_tenths):
    """Digits and turn of each roll. A turning roll shows its lower digit (that is what counts); rolls above a 9
    turn along with it."""
    value = odo_value(rng, n)
    digits = [int(c) for c in str(value).zfill(n)]
    turns = [0.0] * n
    tenths = None
    if has_tenths:
        t = rng.uniform(9, 10) if rng.random() < 0.3 else rng.uniform(0, 10)
        tenths = [int(t), t - int(t)]
        carry = tenths[1] if tenths[0] == 9 else 0.0
    else:
        carry = rng.uniform(0.05, 0.95) if rng.random() < 0.3 else 0.0
    i = n - 1
    while i >= 0 and carry > 0:
        turns[i] = carry
        if digits[i] != 9:
            break
        i -= 1
    turns = [t if t > 0 else float(rng.normal(0, 0.025)) for t in turns]   # play in the rolls
    return value, digits, turns, tenths


# --- panel ------------------------------------------------------------------------------------------------------

@dataclass
class Item:
    type: str
    text: str
    chars: list                                  # [(char, box, role)]


@dataclass
class Block:
    image: Image.Image
    items: list = field(default_factory=list)
    big: bool = False


class Panel:
    def __init__(self, size, color):
        self.img = Image.new("RGBA", (int(size[0]), int(size[1])), rgba(color))
        self.emit = Image.new("L", self.img.size, 0)
        self.items = []

    def paste(self, block, x, y, glow=None):
        """glow: None, 'area' (the whole block lights up at night) or 'glyph' (only what is drawn)."""
        x, y = int(round(x)), int(round(y))
        self.img.alpha_composite(block.image, (x, y))
        if glow == "area":
            self.emit.paste(255, (x, y, x + block.image.width, y + block.image.height))
        elif glow == "glyph":
            lum = block.image.convert("L")
            alpha = block.image.getchannel("A")
            self.emit.paste(Image.composite(lum, Image.new("L", lum.size, 0), alpha), (x, y), alpha)
        for it in block.items:
            self.items.append(Item(it.type, it.text, [(c, (b[0] + x, b[1] + y, b[2] + x, b[3] + y), r)
                                                      for c, b, r in it.chars]))


def compose(parts):
    """parts: [(Text, x, baseline_y, item or None)] → Block. x/baseline are relative; baseline = bottom of digits."""
    placed = []
    for text, x, base, item in parts:
        own_base = text.chars[0][1][3] if text.chars else text.image.height * 0.8
        placed.append((text, x, base - own_base, item))
    x0 = min(x for _, x, _, _ in placed)
    y0 = min(y for _, _, y, _ in placed)
    x1 = max(x + t.image.width for t, x, _, _ in placed)
    y1 = max(y + t.image.height for t, _, y, _ in placed)
    img = Image.new("RGBA", (int(x1 - x0) + 1, int(y1 - y0) + 1), (0, 0, 0, 0))
    items = []
    for text, x, y, item in placed:
        ox, oy = int(round(x - x0)), int(round(y - y0))
        img.alpha_composite(text.image, (ox, oy))
        if item is not None:
            typ, shown, roles = item
            chars = [(c, (b[0] + ox, b[1] + oy, b[2] + ox, b[3] + oy), r) for (c, b), r in zip(text.chars, roles)]
            items.append(Item(typ, shown, chars))
    return Block(img, items)


def visible_roles(kind, text, roles):
    """Roles for the characters the renderer reports (blanks and, on segments, the decimal point have no box)."""
    return [r for c, r in zip(text, roles) if c not in " \u2009" and not (kind == "lcd7" and c == ".")]


def reading(rng, kind, style, label_style, typ, text, h, label=None, unit=None, roles=None, big=False):
    value = render(RENDERER[kind], text, h, style)
    if roles is None:
        roles = [None] * len(text)
    parts = []
    gap = h * rng.uniform(0.2, 0.5)
    lh = h * rng.uniform(0.32, 0.5)
    vx = 0
    if label:
        lab = render_label(kind, label, lh, label_style)
        if rng.random() < 0.5:
            parts.append((lab, 0, -h - gap * 0.4, None))                 # above the value
        else:
            parts.append((lab, 0, 0 if rng.random() < 0.5 else -h / 2 + lh / 2, None))
            vx = lab.image.width + gap * 0.3
    parts.append((value, vx, 0, (typ, text.strip(), visible_roles(kind, text, roles))))
    if unit:
        parts.append((render_label(kind, unit, lh, label_style), vx + value.image.width + gap * 0.2, 0, None))
    block = compose(parts)
    block.big = big
    return block


def render_label(kind, text, h, label_style):
    if kind == "dotmatrix" and label_style.get("dots"):
        return render("dot", text.upper(), h, label_style["dots"])
    return font_text(text, h, label_style)


def digital_style(rng, kind):
    negative = kind == "tft" or rng.random() < (0.45 if kind == "lcd7" else 0.65)
    glow_col = pick(rng, GLOW)
    if negative:
        bg = tuple(int(v) for v in rng.uniform(0, 40, 3))
        fg = jitter(rng, glow_col if rng.random() < 0.6 else (240, 240, 240))
        glow = "glyph"
    else:
        grey = rng.uniform(150, 215)
        bg = tuple(int(v) for v in np.clip(grey + rng.uniform(-12, 12, 3), 0, 255))
        backlit = rng.random() < 0.6
        if backlit:
            bg = tuple(int(0.65 * b + 0.35 * g) for b, g in zip(bg, glow_col))
        fg = jitter(rng, (25, 30, 40), 15)
        glow = "area" if backlit else None
    style = {"fg": fg}
    if kind == "lcd7":
        style.update(ghost=rng.uniform(0.03, 0.12) if rng.random() < 0.6 else 0.0, width=rng.uniform(0.48, 0.62),
                     thickness=rng.uniform(0.09, 0.16), slant=rng.uniform(0, 0.12) if rng.random() < 0.7 else 0.0,
                     spacing=rng.uniform(0.12, 0.3),
                     variants={c: v for c, v in {"7": "abcf", "6": "cdefg", "9": "abcfg"}.items()
                               if rng.random() < 0.3})
    elif kind == "dotmatrix":
        style.update(ghost=rng.uniform(0.04, 0.15) if rng.random() < 0.5 else 0.0, dot=rng.uniform(0.3, 0.48),
                     square=bool(rng.random() < 0.4))
    else:
        style.update(font=pick(rng, FONTS), tracking=rng.uniform(-0.02, 0.08))
    label_fg = fg if rng.random() < 0.6 else jitter(rng, pick(rng, GLOW))
    label_style = {"fg": label_fg, "font": pick(rng, ["round", "round-bold", "mono"])}
    if kind == "dotmatrix" and rng.random() < 0.5:
        label_style["dots"] = dict(style, fg=label_fg, ghost=0.0)
    return style, label_style, bg, glow


def digital_blocks(rng, kind, style, label_style, h):
    km = rng.random() < 0.8
    unit = ("km" if rng.random() < 0.7 else "KM") if km else ("mi" if rng.random() < 0.7 else "MI")
    cells = 6 if rng.random() < 0.7 else 5
    value = odo_value(rng, cells)
    tenths = int(rng.integers(0, 10)) if rng.random() < {"lcd7": 0.15, "dotmatrix": 0.1, "tft": 0.1}[kind] else None
    text, roles = odo_text(rng, kind, value, cells, tenths)
    odo_label = pick(rng, ["ODO", "ODO", "TOTAL", "Gesamt", "Total", None, None])
    if kind == "lcd7" and odo_label and rng.random() < 0.5:
        odo_label = "ODO"
    blocks = [reading(rng, kind, style, label_style, "odo", text, h, odo_label,
                      unit if rng.random() < 0.8 else None, roles)]
    odo = {"value": value, "text": str(value), "tenths": tenths, "unit": "km" if km else "mi"}

    def maybe(p):
        return rng.random() < p

    hh = lambda: h * rng.uniform(0.8, 1.15)     # noqa: E731 - other readings in the same size as the ODO line
    if maybe(0.85):
        for name in (["A", "B"] if maybe(0.3) else [pick(rng, ["", "A", "1", "2"])]):
            trip = f"{int(10 ** rng.uniform(0, 3.6)) if maybe(0.85) else 0}.{rng.integers(0, 10)}"
            lab = pick(rng, ["TRIP", "Trip", "TRP"]) + (" " + name if name else "") if maybe(0.85) else None
            blocks.append(reading(rng, kind, style, label_style, "trip", trip, hh(), lab, unit if maybe(0.6) else None))
    if maybe(0.6):
        hour = int(rng.integers(0, 24)) if maybe(0.6) else int(rng.integers(1, 13))
        clock = f"{hour}:{rng.integers(0, 60):02d}"
        blocks.append(reading(rng, kind, style, label_style, "clock", clock, hh()))
    if maybe(0.4):
        t = int(rng.integers(-12, 39))
        temp = f"{t}°C" if km or maybe(0.5) else f"{int(t * 1.8 + 32)}°F"
        blocks.append(reading(rng, kind, style, label_style, "temperature", temp, hh() * 0.8))
    if maybe(0.3):
        rng_val = str(int(rng.integers(0, 650)))
        blocks.append(reading(rng, kind, style, label_style, "range", rng_val, hh(),
                              pick(rng, ["RANGE", "Range", "Reichweite", None]), unit))
    if maybe(0.2):
        cons = f"{rng.uniform(2.5, 12):.1f}"
        blocks.append(reading(rng, kind, style, label_style, "consumption", cons, hh() * 0.8, None,
                              "l/100km" if km else "mpg"))
    if maybe(0.5):
        speed = str(int(rng.integers(0, 230)))
        blocks.append(reading(rng, kind, style, label_style, "speed", speed, h * rng.uniform(2.0, 4.0), None,
                              "km/h" if km else "mph", big=True))
    if maybe(0.3):
        gear = pick(rng, ["1", "2", "3", "4", "5", "6", "n" if kind == "lcd7" else "N"])
        blocks.append(reading(rng, kind, style, label_style, "gear", gear, h * rng.uniform(1.5, 3.0), big=True))
    return blocks, odo


def layout(rng, blocks, gap):
    big = [b for b in blocks if b.big]
    small = [blocks[i] for i in rng.permutation(len(blocks)) if not blocks[i].big]
    rows = []
    i = 0
    while i < len(small):
        n = 2 if rng.random() < 0.45 and i + 1 < len(small) else 1
        rows.append(small[i:i + n])
        i += n
    for b in big:
        rows.insert(int(rng.integers(0, len(rows) + 1)), [b])
    width = max(sum(b.image.width for b in row) + gap * (len(row) - 1) for row in rows)
    placed = []
    y = 0.0
    align = rng.random()
    for row in rows:
        rh = max(b.image.height for b in row)
        if len(row) == 2:
            placed.append((row[0], 0, y + (rh - row[0].image.height) / 2))
            placed.append((row[1], width - row[1].image.width, y + (rh - row[1].image.height) / 2))
        else:
            b = row[0]
            x = 0 if align < 0.3 else (width - b.image.width) / 2 if align < 0.7 else width - b.image.width
            placed.append((b, x, y))
        y += rh + gap * rng.uniform(0.2, 1.0)
    return placed, width, y - gap * 0.2


def gauge(rng, panel, cx, cy, r, face, ink, glow, unit_text, needle=True):
    """A round dial: face, ticks, numbers (labelled as 'scale' distractors), unit and needle."""
    d = ImageDraw.Draw(panel.img)
    d.ellipse([cx - r * 1.04, cy - r * 1.04, cx + r * 1.04, cy + r * 1.04], fill=rgba(jitter(rng, (90, 90, 95), 30)))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=rgba(face))
    if glow == "area":
        mask = Image.new("L", panel.img.size, 0)
        ImageDraw.Draw(mask).ellipse([cx - r, cy - r, cx + r, cy + r], fill=200)
        panel.emit.paste(mask, (0, 0), mask)
    top = pick(rng, [140, 160, 180, 200, 220, 240, 260])
    step = 20 if top <= 200 else pick(rng, [20, 40])
    a0, a1 = math.radians(225), math.radians(-45)
    ang = lambda v: a0 + (a1 - a0) * v / top        # noqa: E731
    tick_layer = Image.new("RGBA", panel.img.size, (0, 0, 0, 0))
    td = ImageDraw.Draw(tick_layer)
    for v in range(0, top + 1, step // 2):
        a = ang(v)
        inner = 0.8 if v % step == 0 else 0.87
        td.line([(cx + r * 0.93 * math.cos(a), cy - r * 0.93 * math.sin(a)),
                 (cx + r * inner * math.cos(a), cy - r * inner * math.sin(a))],
                fill=rgba(ink), width=max(2, int(r * (0.022 if v % step == 0 else 0.012))))
    panel.paste(Block(tick_layer), 0, 0, glow="glyph" if glow == "glyph" else None)
    style = {"fg": ink, "font": pick(rng, ["round-bold", "mono-bold", "round"])}
    nh = r * rng.uniform(0.07, 0.1)
    for v in range(0, top + 1, step):
        a = ang(v)
        t = font_text(str(v), nh, style)
        block = compose([(t, 0, 0, ("scale", str(v), [None] * len(str(v))))])
        x = cx + r * 0.64 * math.cos(a) - block.image.width / 2
        y = cy - r * 0.64 * math.sin(a) - block.image.height / 2
        panel.paste(block, x, y, glow="glyph" if glow == "glyph" else None)
    u = font_text(unit_text, r * 0.06, style)
    panel.paste(Block(u.image), cx - u.image.width / 2, cy - r * 0.42, glow="glyph" if glow == "glyph" else None)
    if needle:
        v = rng.uniform(0.08, 0.95) * top
        a = ang(v)
        col = rgba(jitter(rng, pick(rng, [(255, 90, 20), (230, 30, 30), (240, 240, 240)])))
        d.line([(cx - r * 0.15 * math.cos(a), cy + r * 0.15 * math.sin(a)),
                (cx + r * 0.85 * math.cos(a), cy - r * 0.85 * math.sin(a))], fill=col, width=max(3, int(r * 0.03)))
        d.ellipse([cx - r * 0.08, cy - r * 0.08, cx + r * 0.08, cy + r * 0.08], fill=rgba((20, 20, 22)))


def digital_scene(rng, kind):
    style, label_style, bg, glow = digital_style(rng, kind)
    h = rng.uniform(20, 45) * S                 # digit height of the ODO line
    blocks, odo = digital_blocks(rng, kind, style, label_style, h)
    gap = h * rng.uniform(0.5, 1.0)
    placed, dw, dh = layout(rng, blocks, gap)
    pad = h * rng.uniform(0.4, 1.0)
    dw, dh = dw + 2 * pad, dh + 2 * pad
    housing = jitter(rng, pick(rng, [(20, 20, 22), (40, 40, 44), (70, 70, 75), (15, 15, 30)]), 10)
    margin = [dw * rng.uniform(0.05, 0.4) for _ in range(4)]   # left, top, right, bottom
    dial = rng.random() < 0.45
    dial_r = dh * rng.uniform(0.55, 0.9) if dial else 0
    dial_left = rng.random() < 0.5
    extra = 2 * dial_r + margin[0] if dial else 0
    pw = margin[0] + dw + margin[2] + extra
    ph = margin[1] + max(dh, 2 * dial_r) + margin[3]
    panel = Panel((pw, ph), housing)
    dx = margin[0] + (extra if dial and dial_left else 0)
    dy = margin[1] + (max(dh, 2 * dial_r) - dh) / 2
    d = ImageDraw.Draw(panel.img)
    bezel = h * rng.uniform(0.1, 0.4)
    d.rounded_rectangle([dx - bezel, dy - bezel, dx + dw + bezel, dy + dh + bezel], radius=h * 0.4,
                        fill=rgba(jitter(rng, (10, 10, 12), 8)))
    screen = Image.new("RGBA", (int(dw), int(dh)), rgba(bg))
    if kind == "tft" or rng.random() < 0.4:
        top_c, bot_c = np.array(bg, float), np.clip(np.array(bg) + rng.uniform(-25, 35, 3), 0, 255)
        ramp = np.linspace(0, 1, int(dh))[:, None, None]
        arr = top_c * (1 - ramp) + bot_c * ramp
        screen = Image.fromarray(np.repeat(arr, int(dw), axis=1).astype(np.uint8)).convert("RGBA")
    if kind == "tft" and rng.random() < 0.6:    # widgets: bars and frames that are not text
        sd = ImageDraw.Draw(screen)
        for _ in range(rng.integers(1, 5)):
            x0, y0 = rng.uniform(0, dw * 0.8), rng.uniform(0, dh * 0.9)
            sd.rounded_rectangle([x0, y0, x0 + rng.uniform(0.05, 0.4) * dw, y0 + rng.uniform(0.01, 0.05) * dh],
                                 radius=3, fill=rgba(jitter(rng, pick(rng, GLOW)), rng.uniform(0.3, 0.9)))
    panel.paste(Block(screen), dx, dy, glow="area" if glow == "area" or kind == "tft" else None)
    for block, x, y in placed:
        panel.paste(block, dx + pad + x, dy + pad + y, glow="glyph" if glow else None)
    if dial:
        cx = margin[0] + dial_r if dial_left else dx + dw + margin[0] + dial_r
        face = jitter(rng, (15, 15, 18), 10) if rng.random() < 0.75 else jitter(rng, (225, 225, 215), 15)
        ink = (235, 235, 235) if sum(face) < 300 else (20, 20, 20)
        gauge(rng, panel, cx, ph / 2, dial_r, face, ink, "glyph" if sum(face) < 300 else "area",
              pick(rng, ["x1000 r/min", "km/h", "1/min x1000"]))
    return panel, odo


def drum_scene(rng):
    r = rng.uniform(380, 520)
    margin = r * rng.uniform(0.08, 0.5)
    pw, ph = 2 * r + 2 * margin + r * rng.uniform(0, 0.8), 2 * r + 2 * margin
    panel = Panel((pw, ph), jitter(rng, pick(rng, [(20, 20, 22), (45, 45, 50), (70, 70, 75)]), 10))
    cx, cy = pw / 2 + rng.uniform(-0.1, 0.1) * r, ph / 2
    dark_face = rng.random() < 0.7
    face = jitter(rng, (15, 15, 18), 10) if dark_face else jitter(rng, (228, 226, 212), 15)
    ink = (235, 235, 235) if dark_face else (20, 20, 20)
    km = rng.random() < 0.8
    gauge(rng, panel, cx, cy, r, face, ink, "glyph" if dark_face else "area", "km/h" if km else "MPH")

    light_rolls = rng.random() < (0.15 if dark_face else 0.5)
    roll_fg, roll_bg = ((20, 20, 20), jitter(rng, (235, 235, 228), 10)) if light_rolls else \
        ((235, 235, 235), jitter(rng, (18, 18, 20), 8))
    tenth_cols = pick(rng, [(roll_fg, roll_bg), ((245, 245, 245), (190, 25, 25)), ((200, 20, 20), (240, 240, 235)),
                            ((20, 20, 20), (240, 240, 235))])
    style = {"font": pick(rng, ["mono-bold", "round-bold", "mono"]), "roll_width": rng.uniform(0.62, 0.82),
             "separator": rng.uniform(0.04, 0.14), "window_pad": rng.uniform(0.05, 0.2),
             "glyph": rng.uniform(0.55, 0.72), "pitch": rng.uniform(1.25, 1.5), "shading": rng.uniform(0.25, 0.6),
             "baseline": rng.uniform(-0.04, 0.04), "window": jitter(rng, (12, 12, 14), 8)}

    def counter(n, has_tenths, h):
        value, digits, turns, tenths = drum_state(rng, n, has_tenths)
        rolls = [(d, t, (roll_fg, roll_bg)) for d, t in zip(digits, turns)]
        roles = ["int"] * n
        if tenths:
            rolls.append((tenths[0], tenths[1], tenth_cols))
            roles.append("tenths")
        st = dict(style, offset=list(rng.uniform(-0.04, 0.04, len(rolls))))
        text = drum_counter(rolls, h, st)
        return text, roles, value, digits, turns, tenths

    n = 6 if rng.random() < 0.55 else 5
    h = r * rng.uniform(0.075, 0.11)
    t_odo, roles, value, digits, turns, tenths = counter(n, rng.random() < 0.6, h)
    shown = "".join(map(str, digits))
    odo_block = compose([(t_odo, 0, 0, ("odo", shown, roles))])
    ox = cx - odo_block.image.width / 2 + rng.uniform(-0.05, 0.05) * r
    oy = cy + r * rng.uniform(0.2, 0.36)
    bez = h * 0.12
    ImageDraw.Draw(panel.img).rectangle([ox - bez, oy - bez, ox + odo_block.image.width + bez,
                                         oy + odo_block.image.height + bez], fill=rgba(jitter(rng, (60, 60, 64), 20)))
    panel.paste(odo_block, ox, oy, glow="glyph")
    lab_style = {"fg": ink, "font": pick(rng, ["round", "round-bold"])}
    if rng.random() < 0.5:
        u = font_text("km" if km else "MILES", h * 0.4, lab_style)
        panel.paste(Block(u.image), ox + odo_block.image.width + h * 0.15, oy + h * 0.3, glow="glyph")
    if rng.random() < 0.6:
        t_trip, troles, tval, tdig, _, ttenths = counter(int(rng.integers(3, 5)), True, h * rng.uniform(0.8, 1.0))
        tshown = "".join(map(str, tdig)) + "." + str(ttenths[0])
        trip_block = compose([(t_trip, 0, 0, ("trip", tshown, troles))])
        tx = cx - trip_block.image.width / 2 + rng.uniform(-0.1, 0.1) * r
        ty = oy + odo_block.image.height + h * rng.uniform(0.4, 0.9)
        if ty + trip_block.image.height < cy + r * 0.92:
            ImageDraw.Draw(panel.img).rectangle([tx - bez, ty - bez, tx + trip_block.image.width + bez,
                                                 ty + trip_block.image.height + bez],
                                                fill=rgba(jitter(rng, (60, 60, 64), 20)))
            panel.paste(trip_block, tx, ty, glow="glyph")
            if rng.random() < 0.5:     # reset knob
                kr = h * 0.4
                kx, ky = tx + trip_block.image.width + h * 0.8, ty + trip_block.image.height / 2
                ImageDraw.Draw(panel.img).ellipse([kx - kr, ky - kr, kx + kr, ky + kr],
                                                  fill=rgba(jitter(rng, (50, 50, 55), 20)))
    odo = {"value": value, "text": shown, "tenths": tenths[0] if tenths else None, "unit": "km" if km else "mi",
           "turns": [round(t, 3) for t in turns] + ([round(tenths[1], 3)] if tenths else [])}
    return panel, odo


# --- one image ----------------------------------------------------------------------------------------------------

def box_quad(b):
    x0, y0, x1, y1 = b
    return [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]


def union(boxes):
    return (min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes))


def make(seed, index, kinds, size):
    rng = np.random.default_rng([seed, index])
    kind = kinds[index % len(kinds)]           # evenly spread over the kinds
    panel, odo = drum_scene(rng) if kind == "drum" else digital_scene(rng, kind)
    full = panel.img.convert("RGB")
    small = (full.width // S, full.height // S)
    img = full.resize(small, Image.Resampling.LANCZOS)
    emit = panel.emit.resize(small, Image.Resampling.BILINEAR)
    odo_item = next(it for it in panel.items if it.type == "odo")
    digits = [(c, tuple(v / S for v in b), r) for c, b, r in odo_item.chars if r in ("int", "tenths")]
    odo_box = union([b for _, b, _ in digits])
    jpeg, hom, cond = photograph(rng, img, emit, box_quad(odo_box), size)

    def tq(box):
        return [[round(float(x), 1), round(float(y), 1)] for x, y in apply(hom, box_quad(box))]

    def inside(q):
        return all(0 <= x < size[0] and 0 <= y < size[1] for x, y in q)

    turns = odo.pop("turns", None)
    label = {
        "version": VERSION, "seed": seed, "index": index, "kind": kind, "width": size[0], "height": size[1],
        **odo,
        "odo": tq(odo_box),
        "digits": [dict({"digit": int(c), "quad": tq(b), "tenths": r == "tenths"},
                        **({"turn": turns[i]} if turns else {}))
                   for i, (c, b, r) in enumerate(digits)],
        "distractors": [],
        "conditions": cond,
    }
    for it in panel.items:
        if it.type == "odo" or not it.chars:
            continue
        q = tq(tuple(v / S for v in union([b for _, b, _ in it.chars])))
        if any(0 <= x < size[0] and 0 <= y < size[1] for x, y in q):
            label["distractors"].append({"type": it.type, "text": it.text, "quad": q, "complete": inside(q)})
    return jpeg, label


def write(args):
    seed, index, kinds, size, out = args
    jpeg, label = make(seed, index, kinds, size)
    name = f"{seed}-{index:06d}"
    with open(os.path.join(out, name + ".jpg"), "wb") as f:
        f.write(jpeg)
    with open(os.path.join(out, name + ".json"), "w") as f:
        json.dump(dict(label, image=name + ".jpg"), f, ensure_ascii=False)
    return name


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--count", type=int)
    p.add_argument("--seed", type=int)
    p.add_argument("--start", type=int, default=0, help="first index (to extend a set)")
    p.add_argument("--out", default="out")
    p.add_argument("--kinds", default=",".join(KINDS))
    p.add_argument("--size", default="640x480")
    p.add_argument("--workers", type=int, default=os.cpu_count())
    p.add_argument("--testset", action="store_true", help=f"the fixed test set {TESTSET}")
    a = p.parse_args(argv)
    if a.testset:
        a.seed, a.count, a.out = TESTSET["seed"], TESTSET["count"], TESTSET["out"]
    if a.count is None or a.seed is None:
        p.error("--count and --seed are required (or --testset)")
    kinds = a.kinds.split(",")
    unknown = set(kinds) - set(KINDS)
    if unknown:
        p.error(f"unknown kinds {sorted(unknown)}, known: {KINDS}")
    size = tuple(int(v) for v in a.size.lower().split("x"))
    os.makedirs(a.out, exist_ok=True)
    jobs = [(a.seed, i, kinds, size, a.out) for i in range(a.start, a.start + a.count)]
    with ProcessPoolExecutor(max_workers=a.workers) as pool:
        for n, _ in enumerate(pool.map(write, jobs, chunksize=8), 1):
            if n % 100 == 0 or n == len(jobs):
                print(f"{n}/{len(jobs)}", file=sys.stderr)
    with open(os.path.join(a.out, "manifest.json"), "w") as f:
        json.dump({"version": VERSION, "seed": a.seed, "start": a.start, "count": a.count, "kinds": kinds,
                   "size": list(size)}, f, indent=1)


if __name__ == "__main__":
    main()
