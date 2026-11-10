# Not used in the app build. Reproduces the digit model and the scan test photos, see docs/digit-model.md.
"""Builds the sample photos for the app's scan tests from rendered worksheet pages (4 px per point).

usage: synth.py <contract.json> <pages dir> <out dir>
Writes <name>.jpg and <name>.json (expected values) per sample.
"""
import gzip, json, math, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

import os
rng = np.random.default_rng(int(os.environ.get("SEED", "120")))
contract = json.load(open(sys.argv[1]))
pages_dir, out_dir = sys.argv[2], sys.argv[3]
SCALE = 4.0

with gzip.open("data/t10k-images-idx3-ubyte.gz") as f:
    digits_img = np.frombuffer(f.read(), np.uint8, offset=16).reshape(-1, 28, 28)
with gzip.open("data/t10k-labels-idx1-ubyte.gz") as f:
    digits_lbl = np.frombuffer(f.read(), np.uint8, offset=8)
by_digit = {d: np.nonzero(digits_lbl == d)[0] for d in range(10)}


def sheet(template, cylinders=None, vpc=None):
    for s in contract["sheets"]:
        if s["template"] == template and s.get("cylinders") == cylinders and s.get("valvesPerCylinder") == vpc:
            return s
    raise KeyError(template)


def draw_digit(page, d, left, top):
    """Pastes an MNIST test digit (dark ink) into the box at (left, top) points."""
    src = digits_img[rng.choice(by_digit[d])].astype(np.float32) / 255.0
    ys, xs = np.nonzero(src > 0.1)
    src = src[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    box_w, box_h = 18 * SCALE, 24 * SCALE
    target_h = box_h * rng.uniform(0.55, 0.72)
    scale = min(target_h / src.shape[0], box_w * rng.uniform(0.6, 0.8) / src.shape[1])
    w = max(1, int(src.shape[1] * scale))
    h = max(1, int(src.shape[0] * scale))
    img = np.asarray(Image.fromarray((src * 255).astype(np.uint8)).resize((w, h), Image.BILINEAR)) / 255.0
    x0 = int(left * SCALE + (box_w - w) / 2 + rng.uniform(-3, 3))
    y0 = int(top * SCALE + (box_h - h) / 2 + rng.uniform(-3, 3))
    ink = rng.uniform(20, 60)  # pen colour (dark grey/blue-black)
    region = page[y0:y0 + h, x0:x0 + w]
    page[y0:y0 + h, x0:x0 + w] = np.minimum(region, 255 - img * (255 - ink))


def tick(page, left, top):
    im = Image.fromarray(page.astype(np.uint8))
    dr = ImageDraw.Draw(im)
    s = SCALE
    x, y = left * s, top * s
    pts = [(x + 3 * s, y + 8 * s), (x + 7 * s, y + 13 * s), (x + 15 * s, y + 1 * s)]
    if rng.random() < 0.5:
        dr.line(pts, fill=40, width=int(1.6 * s))
    else:
        dr.line([(x + 2 * s, y + 2 * s), (x + 14 * s, y + 14 * s)], fill=40, width=int(1.5 * s))
        dr.line([(x + 14 * s, y + 2 * s), (x + 2 * s, y + 14 * s)], fill=40, width=int(1.5 * s))
    page[:] = np.asarray(im, dtype=np.float32)


def fill(page, fields, empty_share=0.2):
    expected = {}
    for f in fields:
        if f["kind"] == "CHECKBOX":
            checked = bool(rng.random() < 0.6)
            if checked:
                tick(page, f["left"], f["top"])
            expected[f["id"]] = checked
            continue
        if rng.random() < empty_share:
            expected[f["id"]] = ""
            continue
        n_int, n_dec = f["integerDigits"], f["decimalDigits"]
        # Integer: 1..n_int digits right-aligned (no leading zero unless single), decimals: 1..n_dec left-aligned.
        k_int = int(rng.integers(1, n_int + 1))
        ints = [int(rng.integers(1, 10))] + [int(rng.integers(0, 10)) for _ in range(k_int - 1)]
        if k_int == 1 and n_dec > 0 and rng.random() < 0.5:
            ints = [0]
        k_dec = int(rng.integers(1, n_dec + 1)) if n_dec else 0
        decs = [int(rng.integers(0, 10)) for _ in range(k_dec)]
        boxes = [None] * (n_int - k_int) + ints + decs + [None] * (n_dec - k_dec)
        left = f["left"]
        for i, d in enumerate(boxes):
            box_left = left + i * 18 + (8 if n_dec and i >= n_int else 0)
            if d is not None:
                draw_digit(page, d, box_left, f["top"])
        value = "".join(map(str, ints))
        if decs:
            value += "." + "".join(map(str, decs))
        expected[f["id"]] = value
    return expected


def perspective_coeffs(dst, src):
    """PIL wants coefficients mapping output (photo) pixels to input (page) pixels."""
    a = []
    for (x, y), (u, v) in zip(dst, src):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    b = [c for p in src for c in p]
    return np.linalg.solve(np.array(a, dtype=np.float64), np.array(b, dtype=np.float64))


def photograph(page, corners, size=(1500, 2000), light=None, noise=4.0, blur=0.6, background=95):
    """Places the page with its four corners at `corners` (photo pixels) on a background, then light and noise."""
    h, w = page.shape
    src = [(0, 0), (w, 0), (w, h), (0, h)]
    coeffs = perspective_coeffs(corners, src)
    pg = Image.fromarray(page.astype(np.uint8))
    warped = pg.transform(size, Image.PERSPECTIVE, coeffs, Image.BILINEAR, fillcolor=None)
    mask = Image.new("L", (w, h), 255).transform(size, Image.PERSPECTIVE, coeffs, Image.BILINEAR)
    bg = Image.fromarray((background + rng.normal(0, 12, (size[1], size[0]))).clip(0, 255).astype(np.uint8))
    bg = bg.filter(ImageFilter.GaussianBlur(3))
    photo = Image.composite(warped, bg, mask)
    arr = np.asarray(photo, dtype=np.float32)
    if light is not None:
        arr = arr * light(size)
    if blur:
        arr = np.asarray(Image.fromarray(arr.clip(0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(blur)), dtype=np.float32)
    arr = arr + rng.normal(0, noise, arr.shape)
    rgb = np.stack([arr * 1.0, arr * 0.97, arr * 0.9], -1).clip(0, 255).astype(np.uint8)  # warm white balance
    return Image.fromarray(rgb, "RGB")


def dim(size):
    w, h = size
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    gradient = 0.35 + 0.55 * (xx / w) * 0.6 + 0.4 * (yy / h) * 0.4
    shadow = 1 - 0.35 * np.exp(-((xx - 0.3 * w) ** 2) / (2 * (0.12 * w) ** 2))
    return (gradient * shadow * 0.85).clip(0.25, 1.0)


def sample(name, page_file, sheet_def, page_no, corners, **kw):
    page = np.asarray(Image.open(f"{pages_dir}/{page_file}").convert("L"), dtype=np.float32).copy()
    fields = sheet_def["pages"][page_no - 1]["fields"]
    expected = fill(page, fields)
    photo = photograph(page, corners, **kw)
    photo.save(f"{out_dir}/{name}.jpg", quality=82)
    qr = f"BMWS;v=1;t={sheet_def['template']};id=42"
    if "cylinders" in sheet_def:
        qr += f";c={sheet_def['cylinders']};vpc={sheet_def['valvesPerCylinder']}"
    qr += f";p={page_no}/{len(sheet_def['pages'])}"
    json.dump({"qr": qr, "values": expected}, open(f"{out_dir}/{name}.json", "w"), indent=1, sort_keys=True)
    print(name, len(expected), "fields")


# Straight: page fills most of the frame, turned by 2 degrees.
def turned(cx, cy, w, h, deg):
    a = math.radians(deg)
    pts = [(-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)]
    return [(cx + x * math.cos(a) - y * math.sin(a), cy + x * math.sin(a) + y * math.cos(a)) for x, y in pts]


sample("valves-straight", "VALVE_CLEARANCE-1.png", sheet("VALVE_CLEARANCE", 8, 3), 1,
       turned(750, 1000, 1300, 1838, 2))
sample("tires-oblique", "TIRES-1.png", sheet("TIRES"), 1,
       [(260, 180), (1330, 330), (1420, 1850), (90, 1700)])
sample("chain-dim", "CHAIN-1.png", sheet("CHAIN"), 1, turned(760, 990, 1260, 1782, -4), light=dim, noise=7)
sample("brakes-oblique-dim", "BRAKES-1.png", sheet("BRAKES"), 1,
       [(180, 260), (1240, 120), (1430, 1900), (120, 1760)], light=dim, noise=6, blur=1.0)
# Page 2 of a multi-page valve clearance sheet (cylinders 4–6), at an angle.
sample("valves-page2-oblique", "VALVE_CLEARANCE-2.png", sheet("VALVE_CLEARANCE", 8, 3), 2,
       [(150, 140), (1380, 230), (1300, 1930), (210, 1830)])
