"""Turns a flat rendering of an instrument cluster into a "phone photo": perspective, surroundings, light (day,
night with display glow or flash, against the light), glass (reflections, dust, scratches), blur, noise and JPEG.
"""
import io
import math

import numpy as np
from PIL import Image, ImageDraw, ImageFilter


def homography(src, dst):
    a, b = [], []
    for (x, y), (u, v) in zip(src, dst):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        b.append(u)
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y])
        b.append(v)
    h = np.linalg.solve(np.array(a, float), np.array(b, float))
    return np.append(h, 1).reshape(3, 3)


def apply(h, pts):
    p = np.asarray(pts, float).reshape(-1, 2)
    q = np.c_[p, np.ones(len(p))] @ h.T
    return q[:, :2] / q[:, 2:3]


def warp(img, h, size, resample=Image.Resampling.BICUBIC):
    inv = np.linalg.inv(h)
    inv /= inv[2, 2]
    return img.transform(size, Image.Transform.PERSPECTIVE, tuple(inv.flatten()[:8]), resample)


def _convex(q):
    signs = []
    for i in range(4):
        a, b, c = q[i], q[(i + 1) % 4], q[(i + 2) % 4]
        signs.append(np.sign((b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])))
    return all(s == signs[0] and s != 0 for s in signs)


def place(rng, panel_size, odo_quad, size):
    """Homography panel → photo: the ODO line ends up fully inside the photo, at an angle and in perspective."""
    w, h = size
    pw, ph = panel_size
    odo = np.asarray(odo_quad, float)
    ow = np.ptp(odo[:, 0])
    corners = np.array([(0, 0), (pw, 0), (pw, ph), (0, ph)], float)
    centre = odo.mean(axis=0)
    margin = 0.04 * w
    for attempt in range(60):
        target = (rng.uniform(0.12, 0.45) if rng.random() < 0.8 else rng.uniform(0.45, 0.65)) * w * 0.97 ** attempt
        s = target / ow
        angle = math.radians(float(np.clip(rng.normal(0, 8), -30, 30)))
        rot = s * np.array([[math.cos(angle), -math.sin(angle)], [math.sin(angle), math.cos(angle)]])
        dst = (corners - centre) @ rot.T
        tilt = rng.uniform(0, 0.16) if rng.random() < 0.85 else 0.0
        span = max(pw, ph) * s
        dst = dst + rng.uniform(-tilt, tilt, (4, 2)) * span
        if not _convex(dst):
            continue
        h0 = homography(corners, dst)
        q = apply(h0, odo)
        if not _convex(q) or np.ptp(q[:, 1]) < 9:
            continue
        lo = margin - q.min(axis=0)
        hi = np.array([w - margin, h - margin]) - q.max(axis=0)
        if np.any(lo > hi):
            continue
        t = rng.uniform(lo, hi)
        shift = np.array([[1, 0, t[0]], [0, 1, t[1]], [0, 0, 1]], float)
        return shift @ h0, {"angle": round(math.degrees(angle), 1), "tilt": round(tilt, 3)}
    raise RuntimeError("could not place the panel")


def _surroundings(rng, size):
    w, h = size
    base = rng.uniform(8, 80) * rng.uniform(0.85, 1.15, 3)
    grain = rng.normal(0, 1, (h // 24 + 2, w // 24 + 2, 1))
    coarse = (grain + 0.25 * rng.normal(0, 1, grain.shape[:2] + (3,))).astype(np.float32)
    coarse = np.asarray(Image.fromarray(((coarse * 30) + 128).clip(0, 255).astype(np.uint8))
                        .resize(size, Image.Resampling.BICUBIC), np.float32) - 128
    ramp = np.linspace(rng.uniform(0.6, 1.0), rng.uniform(0.6, 1.4), h, dtype=np.float32)[:, None, None]
    return np.clip((base + coarse * rng.uniform(0.2, 0.8)) * ramp, 0, 255) / 255


def _soft_shape(size, draw_fn, blur):
    m = Image.new("L", size, 0)
    draw_fn(ImageDraw.Draw(m))
    return np.asarray(m.filter(ImageFilter.GaussianBlur(blur)), np.float32) / 255


def photograph(rng, panel, emit, odo_quad, size):
    """panel: RGB rendering, emit: L mask of what glows at night. Returns (JPEG bytes, homography, conditions)."""
    w, h = size
    hom, cond = place(rng, panel.size, odo_quad, size)
    face = np.asarray(warp(panel, hom, size), np.float32) / 255
    glow = np.asarray(warp(emit, hom, size), np.float32)[..., None] / 255
    mask = np.asarray(warp(Image.new("L", panel.size, 255), hom, size), np.float32)[..., None] / 255
    img = _surroundings(rng, size) * (1 - mask) + face * mask

    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    has_glow = float(glow.max()) > 0.2
    night = rng.random() < 0.22
    cond["light"] = "night" if night else "day"
    if night:
        light = np.full((h, w), rng.uniform(0.03, 0.14), np.float32)
        flash = (not has_glow) or rng.random() < 0.25
        if flash:
            fx, fy = w * rng.uniform(0.3, 0.7), h * rng.uniform(0.3, 0.7)
            sigma = w * rng.uniform(0.35, 0.8)
            light += rng.uniform(0.35, 1.0) * np.exp(-((xx - fx) ** 2 + (yy - fy) ** 2) / (2 * sigma ** 2))
        cond["flash"] = bool(flash)
        img = img * light[..., None] + face * glow * mask * rng.uniform(0.75, 1.1)
        if flash and rng.random() < 0.7:
            r = w * rng.uniform(0.02, 0.07)
            spot = _soft_shape(size, lambda d: d.ellipse([fx - r, fy - r * 0.7, fx + r, fy + r * 0.7], fill=255), r * 0.6)
            img = 1 - (1 - img) * (1 - spot[..., None] * rng.uniform(0.5, 0.95))
    else:
        ambient = rng.uniform(0.55, 1.15)
        d = rng.normal(0, 1, 2)
        d /= np.linalg.norm(d) + 1e-6
        ramp = ((xx - w / 2) * d[0] + (yy - h / 2) * d[1]) / max(w, h)
        light = ambient * (1 + rng.uniform(0, 0.7) * ramp)
        if rng.random() < 0.3:      # shadow of the rider or the phone
            pts = [(rng.uniform(-0.2, 1.2) * w, rng.uniform(-0.2, 1.2) * h) for _ in range(4)]
            shadow = _soft_shape(size, lambda dr: dr.polygon(pts, fill=255), w * rng.uniform(0.01, 0.05))
            light *= 1 - shadow * rng.uniform(0.25, 0.55)
            cond["shadow"] = True
        img = img * light[..., None] + face * glow * mask * rng.uniform(0.0, 0.2)
        if rng.random() < 0.15:     # against the light: bright haze from one side, low contrast
            ramp01 = np.clip(ramp * rng.uniform(1.5, 3) + rng.uniform(0, 0.6), 0, 1)
            haze = rng.uniform(0.3, 0.7) * ramp01[..., None] ** 2
            img = img * (1 - haze) * rng.uniform(0.5, 0.85) + haze * rng.uniform(0.85, 1.0)
            cond["backlight"] = True

    if rng.random() < 0.55:         # reflections in the glass
        for _ in range(rng.integers(1, 4)):
            cx, cy = rng.uniform(0, w), rng.uniform(0, h)
            a = rng.uniform(0, math.pi)
            ln, wd = rng.uniform(0.3, 1.2) * w, rng.uniform(0.03, 0.25) * w
            dx, dy = math.cos(a), math.sin(a)
            pts = [(cx - dx * ln / 2 - dy * wd / 2, cy - dy * ln / 2 + dx * wd / 2),
                   (cx + dx * ln / 2 - dy * wd / 2, cy + dy * ln / 2 + dx * wd / 2),
                   (cx + dx * ln / 2 + dy * wd / 2, cy + dy * ln / 2 - dx * wd / 2),
                   (cx - dx * ln / 2 + dy * wd / 2, cy - dy * ln / 2 - dx * wd / 2)]
            band = _soft_shape(size, lambda dr: dr.polygon(pts, fill=255), wd * rng.uniform(0.1, 0.5))
            tint = rng.uniform(0.8, 1.0, 3)
            img = 1 - (1 - img) * (1 - band[..., None] * tint * rng.uniform(0.08, 0.4))
        cond["reflections"] = True
    if rng.random() < 0.5:          # dust
        n = int(rng.integers(20, 250))

        def specks(dr):
            for _ in range(n):
                x, y, r = rng.uniform(0, w), rng.uniform(0, h), rng.uniform(0.4, 2.0)
                dr.ellipse([x - r, y - r, x + r, y + r], fill=int(rng.uniform(80, 255)))
        dust = _soft_shape(size, specks, 0.6)
        img = 1 - (1 - img) * (1 - dust[..., None] * rng.uniform(0.15, 0.5))
        cond["dust"] = True
    if rng.random() < 0.3:          # scratches
        def lines(dr):
            for _ in range(rng.integers(1, 8)):
                x, y = rng.uniform(0, w), rng.uniform(0, h)
                a, ln = rng.uniform(0, math.pi), rng.uniform(0.05, 0.5) * w
                pts = [(x + math.cos(a + k * 0.03) * ln * k / 6, y + math.sin(a + k * 0.03) * ln * k / 6)
                       for k in range(7)]
                dr.line(pts, fill=int(rng.uniform(100, 255)), width=1)
        scratch = _soft_shape(size, lines, 0.4)
        img = 1 - (1 - img) * (1 - scratch[..., None] * rng.uniform(0.15, 0.4))
        cond["scratches"] = True

    out = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    if rng.random() < 0.6:
        radius = rng.uniform(0.3, 1.8)
        out = out.filter(ImageFilter.GaussianBlur(radius))
        cond["blur"] = round(radius, 2)
    if rng.random() < 0.15:
        length = int(rng.integers(3, 10))
        a = rng.uniform(0, math.pi)
        arr = np.asarray(out, np.float32)
        acc = np.zeros_like(arr)
        for k in range(length):
            o = k - (length - 1) / 2
            acc += np.roll(arr, (int(round(o * math.sin(a))), int(round(o * math.cos(a)))), axis=(0, 1))
        out = Image.fromarray((acc / length).astype(np.uint8))
        cond["motion"] = length

    arr = np.asarray(out, np.float32) / 255
    arr = arr * rng.uniform(0.9, 1.1, 3)                         # white balance
    arr = np.clip(arr, 0, 1) ** rng.uniform(0.8, 1.25)           # exposure curve
    sigma = rng.uniform(0.025, 0.06) if night else rng.uniform(0.004, 0.025)
    arr = arr + rng.normal(0, sigma, arr.shape)
    out = Image.fromarray((np.clip(arr, 0, 1) * 255).astype(np.uint8))
    quality = int(rng.integers(55, 96))
    buf = io.BytesIO()
    out.save(buf, "JPEG", quality=quality)
    cond["jpeg"] = quality
    return buf.getvalue(), hom, cond
