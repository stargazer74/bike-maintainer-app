"""
Trains the ODO line detector (app#38): a small U-Net that marks the total-distance line in a photo.

Input: the photo in grey, scaled to 192×144, normalised per photo (mean 0, std 1). Output: a mask of the ODO line
at 96×72 (stride 2). The app turns the mask into four corners (`OdoDetector` in the app, same steps as `quad_of`
below) and lets the user adjust them.

    python generate.py --count 30000 --seed 1 --out out/train     # training photos
    python generate.py --testset                                   # fixed test set, seed 37000
    python train_detector.py prepare out/train cache/train.npz     # grey + masks, once
    python train_detector.py prepare testset cache/test.npz
    python train_detector.py train 40                              # → odo-detector.bin, odo-reference.txt
    python train_detector.py eval                                  # report on the test set
    python train_detector.py eval cache/real.npz                   # same for real photos (prepared like the test set)

Plain PyTorch, on the GPU if there is one (CUDA), else on the CPU. The weights are written as float32 tensors (format `BMO1`, like `digits.bin`), with the
batch norms folded into the convolutions.
"""

import json
import math
import os
import struct
import sys
import time

import numpy as np
from PIL import Image, ImageDraw

IN_W, IN_H = 192, 144
OUT_W, OUT_H = 96, 72
SS = 4  # supersampling of the target mask

# Encoder / decoder widths. Keep in sync with OdoDetector.kt.
E1, E2, E3, E4, B = 12, 24, 32, 48, 64
D4, D3, D2 = 48, 32, 12


# --------------------------------------------------------------------------------------------- data


def grey_input(img: Image.Image) -> np.ndarray:
    """The photo as the model sees it: grey, 192×144 (area average), uint8."""
    g = img.convert("L")
    # Pillow's reduce/resize with BOX is an area average – the app does the same.
    return np.asarray(g.resize((IN_W, IN_H), Image.BOX), dtype=np.uint8)


def target_mask(quad, width, height) -> np.ndarray:
    """Share of each output cell covered by the ODO quad, 0..1 (uint8 0..255)."""
    sx, sy = OUT_W * SS / width, OUT_H * SS / height
    big = Image.new("L", (OUT_W * SS, OUT_H * SS), 0)
    ImageDraw.Draw(big).polygon([(x * sx, y * sy) for x, y in quad], fill=255)
    a = np.asarray(big, dtype=np.float32).reshape(OUT_H, SS, OUT_W, SS).mean(axis=(1, 3))
    return a.astype(np.uint8)


def prepare(src, dst):
    files = sorted(f for f in os.listdir(src) if f.endswith(".json") and f != "manifest.json")
    xs, ys, labels = [], [], []
    for i, f in enumerate(files):
        with open(os.path.join(src, f)) as fh:
            label = json.load(fh)
        img = Image.open(os.path.join(src, label["image"]))
        xs.append(grey_input(img))
        ys.append(target_mask(label["odo"], label["width"], label["height"]))
        labels.append(json.dumps(label))
        if (i + 1) % 2000 == 0:
            print(f"{i + 1}/{len(files)}", flush=True)
    os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
    np.savez_compressed(dst, x=np.stack(xs), y=np.stack(ys), labels=np.array(labels))
    print(f"{len(files)} photos → {dst}")


# --------------------------------------------------------------------------------------------- model


def build():
    import torch.nn as nn
    import torch

    def block(i, o):
        return nn.Sequential(nn.Conv2d(i, o, 3, padding=1, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True))

    class Net(nn.Module):
        def __init__(self):
            super().__init__()
            self.e1 = block(1, E1)                                   # 192×144
            self.e2 = block(E1, E2)                                  # 96×72
            self.e3 = nn.Sequential(block(E2, E3), block(E3, E3))   # 48×36
            self.e4 = nn.Sequential(block(E3, E4), block(E4, E4))   # 24×18
            self.b = nn.Sequential(block(E4, B), block(B, B))       # 12×9
            self.d4 = block(B + E4, D4)                              # 24×18
            self.d3 = block(D4 + E3, D3)                             # 48×36
            self.d2 = block(D3 + E2, D2)                             # 96×72
            self.head = nn.Conv2d(D2, 1, 1)

        def forward(self, x):
            pool = nn.functional.max_pool2d
            up = lambda t: nn.functional.interpolate(t, scale_factor=2, mode="nearest")
            e1 = self.e1(x)
            e2 = self.e2(pool(e1, 2))
            e3 = self.e3(pool(e2, 2))
            e4 = self.e4(pool(e3, 2))
            b = self.b(pool(e4, 2))
            d4 = self.d4(torch.cat([up(b), e4], 1))
            d3 = self.d3(torch.cat([up(d4), e3], 1))
            d2 = self.d2(torch.cat([up(d3), e2], 1))
            return self.head(d2)

    return Net()


def normalise(x):
    """Per photo: mean 0, std 1 (exposure and contrast vary a lot)."""
    x = x.astype(np.float32) / 255.0
    m = x.mean(axis=(-2, -1), keepdims=True)
    s = x.std(axis=(-2, -1), keepdims=True) + 1e-3
    return (x - m) / s


def augment(x, y, rng):
    """Cheap extra variation on top of the generator: brightness/contrast curve, inverted displays are in the data."""
    x = x.astype(np.float32)
    gamma = rng.uniform(0.7, 1.4, size=(x.shape[0], 1, 1))
    x = 255.0 * (x / 255.0) ** gamma
    # random shift by up to 8 px (in steps of 2 so the mask moves along exactly)
    out_x, out_y = np.empty_like(x), np.empty_like(y)
    for i in range(x.shape[0]):
        dx, dy = rng.integers(-4, 5) * 2, rng.integers(-4, 5) * 2
        out_x[i] = np.roll(np.roll(x[i], dy, 0), dx, 1)
        out_y[i] = np.roll(np.roll(y[i], dy // 2, 0), dx // 2, 1)
    return out_x, out_y


def train(epochs):
    import torch
    import torch.nn.functional as F

    torch.manual_seed(38)
    torch.set_num_threads(int(os.environ.get("THREADS", "16")))
    dev = device()
    data = np.load("cache/train.npz")
    x_all, y_all = data["x"], data["y"]
    n = len(x_all)
    val = np.arange(n) >= n - 1000
    xt, yt, xv, yv = x_all[~val], y_all[~val], x_all[val], y_all[val]
    model = build().to(dev)
    opt = torch.optim.AdamW(model.parameters(), lr=3e-3, weight_decay=1e-4)
    steps = epochs * math.ceil(len(xt) / 64)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=3e-3, total_steps=steps, pct_start=0.15)
    rng = np.random.default_rng(38)

    def loss_of(logits, target):
        bce = F.binary_cross_entropy_with_logits(logits, target, pos_weight=torch.tensor(4.0, device=dev))
        p = torch.sigmoid(logits)
        inter = (p * target).sum(dim=(1, 2, 3))
        dice = 1 - (2 * inter + 1) / (p.sum(dim=(1, 2, 3)) + target.sum(dim=(1, 2, 3)) + 1)
        return bce + dice.mean()

    for epoch in range(epochs):
        model.train()
        order = rng.permutation(len(xt))
        t0, total = time.time(), 0.0
        for k in range(0, len(order), 64):
            idx = order[k:k + 64]
            bx, by = augment(xt[idx], yt[idx], rng)
            bx = torch.tensor(normalise(bx)).unsqueeze(1).to(dev)
            by = torch.tensor(by.astype(np.float32) / 255.0).unsqueeze(1).to(dev)
            loss = loss_of(model(bx), by)
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
            total += loss.item() * len(idx)
        model.eval()
        with torch.no_grad():
            pv = torch.sigmoid(model(torch.tensor(normalise(xv)).unsqueeze(1).to(dev))).squeeze(1).cpu().numpy()
        tv = yv.astype(np.float32) / 255.0
        iou = ((pv > 0.5) & (tv > 0.5)).sum() / max(1, ((pv > 0.5) | (tv > 0.5)).sum())
        print(f"epoch {epoch + 1}/{epochs}: loss {total / len(xt):.4f}, val mask IoU {iou:.3f}, "
              f"{time.time() - t0:.0f} s", flush=True)
        torch.save(model.state_dict(), "cache/detector.pt")
    export(model.cpu())


# --------------------------------------------------------------------------------------------- export


def device():
    import torch

    return torch.device("cuda" if torch.cuda.is_available() else "cpu")


def folded(model):
    """Conv weights and biases with the batch norm folded in, in the order the app reads them."""
    import torch.nn as nn

    out = []
    for m in [model.e1, model.e2, *model.e3, *model.e4, *model.b, model.d4, model.d3, model.d2]:
        conv, bn = m[0], m[1]
        scale = (bn.weight / (bn.running_var + bn.eps).sqrt()).detach().numpy()
        w = conv.weight.detach().numpy() * scale[:, None, None, None]
        b = (bn.bias.detach().numpy() - bn.running_mean.detach().numpy() * scale)
        out += [w.astype(np.float32), b.astype(np.float32)]
    out += [model.head.weight.detach().numpy().astype(np.float32), model.head.bias.detach().numpy().astype(np.float32)]
    assert isinstance(model.head, nn.Conv2d)
    return out


def export(model, path="odo-detector.bin"):
    import torch

    model.eval()
    tensors = folded(model)
    with open(path, "wb") as f:
        f.write(b"BMO1")
        f.write(struct.pack("<i", len(tensors)))
        for a in tensors:
            f.write(struct.pack("<i", a.ndim))
            for d in a.shape:
                f.write(struct.pack("<i", d))
            f.write(a.astype("<f4").tobytes())
    print(f"{path}: {os.path.getsize(path) / 1024:.0f} KB")
    # Reference for OdoDetectorTest: a fixed pseudo-random input (same generator as the test) → sums and a few
    # logits of the output, so the Kotlin inference is checked against PyTorch.
    x = reference_input()
    with torch.no_grad():
        y = model(torch.tensor(x).view(1, 1, IN_H, IN_W)).numpy().reshape(-1)
    with open("odo-reference.txt", "w") as f:
        f.write(f"sum {y.sum():.6f}\n")
        f.write(f"abs {np.abs(y).sum():.6f}\n")
        for i in range(0, len(y), 997):
            f.write(f"{i} {y[i]:.6f}\n")


def reference_input():
    """Deterministic input in [-2, 2]: x[i] = sin(i·0.37)·2 (no RNG, so Kotlin can rebuild it)."""
    i = np.arange(IN_W * IN_H, dtype=np.float64)
    return (np.sin(i * 0.37) * 2.0).astype(np.float32)


# --------------------------------------------------------------------------------------------- geometry


def quad_of(prob, width, height, threshold=0.5):
    """
    The ODO line from the mask: the strongest connected region above [threshold], as an oriented rectangle from its
    second moments, in photo pixels, clockwise from the top left of the upright display. Returns (quad, confidence)
    or (None, 0). Mirrors OdoDetector.kt.
    """
    on = prob > threshold
    if not on.any():
        return None, 0.0
    # connected regions (4-neighbourhood), keep the one with the largest probability mass
    labels = np.zeros(on.shape, dtype=np.int32)
    best, best_mass, current = 0, 0.0, 0
    for sy in range(on.shape[0]):
        for sx in range(on.shape[1]):
            if on[sy, sx] and labels[sy, sx] == 0:
                current += 1
                stack, mass = [(sy, sx)], 0.0
                labels[sy, sx] = current
                while stack:
                    y, x = stack.pop()
                    mass += prob[y, x]
                    for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                        if 0 <= ny < on.shape[0] and 0 <= nx < on.shape[1] and on[ny, nx] and labels[ny, nx] == 0:
                            labels[ny, nx] = current
                            stack.append((ny, nx))
                if mass > best_mass:
                    best, best_mass = current, mass
    ys, xs = np.nonzero(labels == best)
    w = prob[ys, xs]
    # cell centres in photo pixels
    px = (xs + 0.5) * width / prob.shape[1]
    py = (ys + 0.5) * height / prob.shape[0]
    cx, cy = (w * px).sum() / w.sum(), (w * py).sum() / w.sum()
    dx, dy = px - cx, py - cy
    cxx, cyy, cxy = (w * dx * dx).sum() / w.sum(), (w * dy * dy).sum() / w.sum(), (w * dx * dy).sum() / w.sum()
    angle = 0.5 * math.atan2(2 * cxy, cxx - cyy)  # direction of the long axis
    ux, uy = math.cos(angle), math.sin(angle)
    if ux < 0:
        ux, uy = -ux, -uy
    vx, vy = -uy, ux  # pointing down
    # a uniform rectangle of length L has variance L²/12 along that axis
    along = (w * (dx * ux + dy * uy) ** 2).sum() / w.sum()
    across = (w * (dx * vx + dy * vy) ** 2).sum() / w.sum()
    half_l, half_h = math.sqrt(3 * along), math.sqrt(3 * across)
    # the mask cells blur the border by about half a cell: add it back
    cell = width / prob.shape[1]
    half_h = max(half_h, cell / 2)
    quad = [
        (cx - ux * half_l - vx * half_h, cy - uy * half_l - vy * half_h),
        (cx + ux * half_l - vx * half_h, cy + uy * half_l - vy * half_h),
        (cx + ux * half_l + vx * half_h, cy + uy * half_l + vy * half_h),
        (cx - ux * half_l + vx * half_h, cy - uy * half_l + vy * half_h),
    ]
    confidence = float(w.mean())
    return quad, confidence


def polygon_iou(a, b):
    """IoU of two convex quads by rasterising at 1/2 px (good enough for a 0.7 threshold)."""
    xs = [p[0] for p in a + b]
    ys = [p[1] for p in a + b]
    x0, y0 = math.floor(min(xs)) - 1, math.floor(min(ys)) - 1
    w, h = math.ceil(max(xs)) - x0 + 2, math.ceil(max(ys)) - y0 + 2
    s = 2
    ia, ib = Image.new("1", (w * s, h * s), 0), Image.new("1", (w * s, h * s), 0)
    ImageDraw.Draw(ia).polygon([((x - x0) * s, (y - y0) * s) for x, y in a], fill=1)
    ImageDraw.Draw(ib).polygon([((x - x0) * s, (y - y0) * s) for x, y in b], fill=1)
    ma, mb = np.asarray(ia), np.asarray(ib)
    union = (ma | mb).sum()
    return float((ma & mb).sum() / union) if union else 0.0


def evaluate(cache="cache/test.npz", weights="cache/detector.pt"):
    import torch

    model = build()
    model.load_state_dict(torch.load(weights, map_location="cpu"))
    model.eval()
    data = np.load(cache)
    x, labels = data["x"], [json.loads(s) for s in data["labels"]]
    with torch.no_grad():
        prob = torch.sigmoid(model(torch.tensor(normalise(x)).unsqueeze(1))).squeeze(1).numpy()
    found, missed, mixups, by_kind, ious = 0, 0, {}, {}, []
    rows = []
    for p, label in zip(prob, labels):
        quad, conf = quad_of(p, label["width"], label["height"])
        iou = polygon_iou(quad, [tuple(q) for q in label["odo"]]) if quad else 0.0
        ious.append(iou)
        k = label["kind"]
        ok = iou >= 0.7
        by_kind.setdefault(k, [0, 0])[0] += ok
        by_kind[k][1] += 1
        found += ok
        if quad is None:
            missed += 1
        elif not ok:
            # what did it find instead?
            hit = max(((polygon_iou(quad, [tuple(q) for q in d["quad"]]), d["type"]) for d in label["distractors"]),
                      default=(0.0, None))
            kind = hit[1] if hit[0] >= 0.3 else ("partial" if iou >= 0.3 else "elsewhere")
            mixups[kind] = mixups.get(kind, 0) + 1
        rows.append((label["image"], k, round(iou, 3), round(conf, 3)))
    n = len(labels)
    print(f"ODO line found (IoU ≥ 0.7): {found}/{n} = {100 * found / n:.1f} %   (none found: {missed})")
    print("by kind: " + ", ".join(f"{k} {a}/{b}" for k, (a, b) in sorted(by_kind.items())))
    print("misses: " + (", ".join(f"{k} {v}" for k, v in sorted(mixups.items())) or "none"))
    print(f"median IoU {np.median(ious):.3f}")
    with open("cache/eval.tsv", "w") as f:
        for r in rows:
            f.write("\t".join(map(str, r)) + "\n")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "prepare":
        prepare(sys.argv[2], sys.argv[3])
    elif cmd == "train":
        train(int(sys.argv[2]) if len(sys.argv) > 2 else 30)
    elif cmd == "eval":
        evaluate(*sys.argv[2:])
    elif cmd == "export":
        import torch

        m = build()
        m.load_state_dict(torch.load("cache/detector.pt", map_location="cpu"))
        export(m)
    else:
        sys.exit(__doc__)
