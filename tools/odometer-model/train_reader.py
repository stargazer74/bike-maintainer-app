"""
Trains the ODO line reader (app#39): reads the digits of the straightened ODO line, with a confidence per digit.

The line is cut out of the photo along its four corners (from the detector, or the label) with a margin and
straightened to 32 px height and at most 160 px width (`crop` below, `OdoCrop` in the app). A small CNN turns it into
40 columns; per column it gives the probability of each class – blank, digits 0–9, tenths digits 0–9 (the 100 m roll
or the digit after the decimal point: read and discarded) – and whether a roll is turning there. CTC decoding gives
the digits. A digit is unsure if its probability is low or its roll is turning (a half-turned roll counts as its
lower digit, like reading a meter, but the reader should not be sure of it).

    python train_reader.py prepare out/train cache/reader-train.npz 2    # 2 jittered crops per photo
    python train_reader.py prepare testset cache/reader-test.npz 0       # exact crops from the labels
    python train_reader.py train 40                                      # → odo-reader.bin, odo-reader-reference.txt
    python train_reader.py eval                                          # labels' quads and detector's quads

Plain PyTorch, on the GPU if there is one. Weights in format `BMR1` (like `odo-detector.bin`), batch norms folded.
"""

import json
import math
import os
import struct
import sys
import time

import numpy as np
from PIL import Image

H, MAX_W, FRAMES = 32, 160, 40
MARGIN_Y = 0.25          # share of the line height added above and below
MARGIN_X = 0.3           # share of the line height added left and right
BLANK, CLASSES = 0, 21   # 1..10 digits 0-9, 11..20 tenths 0-9
ROLLING = 0.2            # a roll turned at least this far (and not back) counts as turning

# Widths, keep in sync with OdoReader.kt.
C1, C2, C3, C4, C5, T = 32, 48, 64, 64, 96, 96


# --------------------------------------------------------------------------------------------- geometry


def homography(quad):
    """Maps the unit square (u along the line, v down) to the quad (tl, tr, br, bl) in photo pixels."""
    src = [(0, 0), (1, 0), (1, 1), (0, 1)]
    a, b = [], []
    for (u, v), (x, y) in zip(src, quad):
        a.append([u, v, 1, 0, 0, 0, -u * x, -v * x]); b.append(x)
        a.append([0, 0, 0, u, v, 1, -u * y, -v * y]); b.append(y)
    h = np.linalg.solve(np.array(a, dtype=np.float64), np.array(b, dtype=np.float64))
    return np.append(h, 1.0).reshape(3, 3)


def apply_h(m, u, v):
    w = m[2, 0] * u + m[2, 1] * v + m[2, 2]
    return (m[0, 0] * u + m[0, 1] * v + m[0, 2]) / w, (m[1, 0] * u + m[1, 1] * v + m[1, 2]) / w


def crop_geometry(quad):
    """Output width and the margins in unit-square terms for a quad."""
    q = np.array(quad, dtype=np.float64)
    length = (np.linalg.norm(q[1] - q[0]) + np.linalg.norm(q[2] - q[3])) / 2
    height = max(1.0, (np.linalg.norm(q[3] - q[0]) + np.linalg.norm(q[2] - q[1])) / 2)
    mx = MARGIN_X * height / max(length, 1.0)
    w = int(round(H * length * (1 + 2 * mx) / (height * (1 + 2 * MARGIN_Y))))
    return min(MAX_W, max(16, w)), mx, height


def crop(grey, quad):
    """
    The line straightened: H × w grey values (float32, 0..255) with w ≤ 160, sampled bilinearly (edge pixels repeated
    outside the photo) with s×s supersampling where the line is larger than the crop. Same steps as OdoCrop.kt.
    """
    w, mx, height = crop_geometry(quad)
    m = homography(quad)
    s = int(min(4, max(1, math.ceil(height * (1 + 2 * MARGIN_Y) / H))))
    sub = (np.arange(s) + 0.5) / s
    xs = (np.arange(w)[:, None] + sub[None, :]).reshape(-1)          # w*s
    ys = (np.arange(H)[:, None] + sub[None, :]).reshape(-1)          # H*s
    u = -mx + xs / w * (1 + 2 * mx)
    v = -MARGIN_Y + ys / H * (1 + 2 * MARGIN_Y)
    uu, vv = np.meshgrid(u, v)
    px, py = apply_h(m, uu, vv)
    vals = bilinear(grey, px, py)                                    # (H*s, w*s)
    return vals.reshape(H, s, w, s).mean(axis=(1, 3)).astype(np.float32)


def bilinear(grey, x, y):
    """Pixel centres at integer + 0.5 (like Pillow); outside the photo the edge repeats."""
    g = grey.astype(np.float32)
    x = np.clip(x - 0.5, 0, g.shape[1] - 1)
    y = np.clip(y - 0.5, 0, g.shape[0] - 1)
    x0 = np.minimum(np.floor(x).astype(np.int64), g.shape[1] - 2)
    y0 = np.minimum(np.floor(y).astype(np.int64), g.shape[0] - 2)
    fx, fy = x - x0, y - y0
    top = g[y0, x0] * (1 - fx) + g[y0, x0 + 1] * fx
    bottom = g[y0 + 1, x0] * (1 - fx) + g[y0 + 1, x0 + 1] * fx
    return top * (1 - fy) + bottom * fy


def x_range(quad, w, mx, box):
    """Columns (in crop pixels) a digit box covers."""
    m_inv = np.linalg.inv(homography(quad))
    xs = []
    for x, y in box:
        u, _ = apply_h(m_inv, x, y)
        xs.append((u + mx) / (1 + 2 * mx) * w)
    return min(xs), max(xs)


def jittered(quad, rng):
    """The label's quad as a detector might give it: corners moved, line longer or shorter."""
    q = np.array(quad, dtype=np.float64)
    along = (q[1] - q[0] + q[2] - q[3]) / 2
    along /= max(1e-6, np.linalg.norm(along))
    height = (np.linalg.norm(q[3] - q[0]) + np.linalg.norm(q[2] - q[1])) / 2
    q += rng.normal(0, 0.07 * height, size=q.shape)
    left, right = rng.normal(0, 0.12 * height, size=2)
    q[[0, 3]] -= along * left
    q[[1, 2]] += along * right
    return [tuple(p) for p in q]


# --------------------------------------------------------------------------------------------- data


def target_of(label):
    """CTC target: the integer digits as shown, then the tenths digit as its own class."""
    seq = [1 + int(c) for c in label["text"] if c.isdigit()]
    if label.get("tenths") is not None:
        seq.append(11 + int(label["tenths"]))
    return seq


def rolling_boxes(label):
    return [d["quad"] for d in label["digits"] if not d["tenths"] and d.get("turn", 0) >= ROLLING]


def sample(grey, label, quad):
    """Crop padded to MAX_W, its width, per-frame rolling target."""
    w, mx, _ = crop_geometry(quad)
    c = crop(grey, quad)
    out = np.zeros((H, MAX_W), dtype=np.uint8)
    out[:, :w] = np.clip(np.round(c), 0, 255).astype(np.uint8)
    roll = np.zeros(FRAMES, dtype=np.uint8)
    for box in rolling_boxes(label):
        a, b = x_range(quad, w, mx, box)
        f0, f1 = int(max(0, math.floor(a / 4))), int(min(FRAMES - 1, math.floor(b / 4)))
        roll[f0:f1 + 1] = 1
    return out, w, roll


def prepare(src, dst, jitters):
    jitters = int(jitters)
    files = sorted(f for f in os.listdir(src) if f.endswith(".json") and f != "manifest.json")
    rng = np.random.default_rng(39)
    xs, ws, rolls, targets, labels = [], [], [], [], []
    for i, f in enumerate(files):
        with open(os.path.join(src, f)) as fh:
            label = json.load(fh)
        grey = np.asarray(Image.open(os.path.join(src, label["image"])).convert("L"))
        quads = [label["odo"]] if jitters == 0 else [jittered(label["odo"], rng) for _ in range(jitters)]
        for q in quads:
            x, w, roll = sample(grey, label, q)
            xs.append(x); ws.append(w); rolls.append(roll)
            targets.append(json.dumps(target_of(label))); labels.append(json.dumps(label))
        if (i + 1) % 2000 == 0:
            print(f"{i + 1}/{len(files)}", flush=True)
    np.savez_compressed(dst, x=np.stack(xs), w=np.array(ws), roll=np.stack(rolls), targets=np.array(targets),
                        labels=np.array(labels))
    print(f"{len(xs)} crops → {dst}")


# --------------------------------------------------------------------------------------------- model


def build():
    import torch
    import torch.nn as nn

    def block(i, o):
        return nn.Sequential(nn.Conv2d(i, o, 3, padding=1, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True))

    def block1(i, o):
        return nn.Sequential(nn.Conv1d(i, o, 3, padding=1, bias=False), nn.BatchNorm1d(o), nn.ReLU(inplace=True))

    class Net(nn.Module):
        def __init__(self):
            super().__init__()
            self.c1, self.c2, self.c3 = block(1, C1), block(C1, C2), block(C2, C3)
            self.c4, self.c5 = block(C3, C4), block(C4, C5)
            self.t1, self.t2 = block1(C5, T), block1(T, T)
            self.head = nn.Conv1d(T, CLASSES, 1)
            self.roll = nn.Conv1d(T, 1, 1)

        def forward(self, x):
            mp = nn.functional.max_pool2d
            x = mp(self.c1(x), 2)                 # 16×80
            x = mp(self.c2(x), 2)                 # 8×40
            x = self.c3(x)
            x = mp(self.c4(x), (2, 1))            # 4×40
            x = self.c5(x).amax(dim=2)            # 40
            x = self.t2(self.t1(x))
            return self.head(x), self.roll(x).squeeze(1)

    return Net()


def normalise(x, w):
    """Per crop over its valid columns: mean 0, std 1; the padding becomes 0."""
    x = x.astype(np.float32) / 255.0
    out = np.zeros_like(x)
    for i in range(len(x)):
        v = x[i, :, :w[i]]
        out[i, :, :w[i]] = (v - v.mean()) / (v.std() + 1e-3)
    return out


def device():
    import torch

    return torch.device("cuda" if torch.cuda.is_available() else "cpu")


def train(epochs):
    import torch
    import torch.nn.functional as F

    torch.manual_seed(39)
    dev = device()
    data = np.load("cache/reader-train.npz")
    x_all, w_all, r_all = data["x"], data["w"], data["roll"]
    t_all = [json.loads(s) for s in data["targets"]]
    n = len(x_all)
    val = np.arange(n) >= n - 2000
    idx_t, idx_v = np.nonzero(~val)[0], np.nonzero(val)[0]
    model = build().to(dev)
    opt = torch.optim.AdamW(model.parameters(), lr=3e-3, weight_decay=1e-4)
    batch = 128
    steps = epochs * math.ceil(len(idx_t) / batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=3e-3, total_steps=steps, pct_start=0.15)
    rng = np.random.default_rng(39)
    ctc = torch.nn.CTCLoss(blank=BLANK, zero_infinity=True)

    def batch_of(ids, augment):
        x = x_all[ids].astype(np.float32)
        if augment:
            gamma = rng.uniform(0.7, 1.4, size=(len(ids), 1, 1))
            x = 255.0 * (x / 255.0) ** gamma
            x += rng.normal(0, rng.uniform(0, 6), size=(len(ids), 1, 1)) * rng.standard_normal(x.shape)
        x = torch.tensor(normalise(np.clip(x, 0, 255), w_all[ids])).unsqueeze(1).to(dev)
        targets = [t_all[i] for i in ids]
        flat = torch.tensor([c for t in targets for c in t], dtype=torch.long)
        lengths = torch.tensor([len(t) for t in targets], dtype=torch.long)
        roll = torch.tensor(r_all[ids].astype(np.float32)).to(dev)
        return x, flat, lengths, roll

    for epoch in range(epochs):
        model.train()
        order = rng.permutation(idx_t)
        t0, total = time.time(), 0.0
        for k in range(0, len(order), batch):
            ids = order[k:k + batch]
            x, flat, lengths, roll = batch_of(ids, True)
            logits, roll_logits = model(x)
            logp = F.log_softmax(logits, dim=1).permute(2, 0, 1)          # T, N, C
            loss = ctc(logp.cpu(), flat, torch.full((len(ids),), FRAMES, dtype=torch.long), lengths)
            # Turning rolls are rare (a few % of the columns): weigh them up so the flag is not missed.
            loss = loss.to(dev) + 0.5 * F.binary_cross_entropy_with_logits(
                roll_logits, roll, pos_weight=torch.tensor(5.0, device=dev))
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
            total += loss.item() * len(ids)
        model.eval()
        right = 0
        with torch.no_grad():
            for k in range(0, len(idx_v), 500):
                ids = idx_v[k:k + 500]
                x, _, _, _ = batch_of(ids, False)
                logits, _ = model(x)
                p = F.softmax(logits, dim=1).cpu().numpy()
                for i, pi in zip(ids, p):
                    right += decode(pi)[0] == t_all[i]
        print(f"epoch {epoch + 1}/{epochs}: loss {total / len(idx_t):.4f}, val sequences right "
              f"{100 * right / len(idx_v):.2f} %, {time.time() - t0:.0f} s", flush=True)
        torch.save(model.state_dict(), "cache/reader.pt")
    export(model.cpu())


# --------------------------------------------------------------------------------------------- decoding


def decode(p):
    """Greedy CTC: classes and, per class, the highest probability in its run of frames and the frames."""
    best = p.argmax(axis=0)
    out, conf, frames = [], [], []
    prev = BLANK
    for f, c in enumerate(best):
        if c != BLANK and c != prev:
            out.append(int(c)); conf.append(float(p[c, f])); frames.append([f])
        elif c != BLANK and c == prev:
            conf[-1] = max(conf[-1], float(p[c, f])); frames[-1].append(f)
        prev = c
    return out, conf, frames


def candidates(p, roll_p, sure=0.98, ghost=0.35, roll_limit=0.3):
    """
    The values one crop can mean. Digits are read with both roles merged (a digit is a digit, whether integer or
    tenths); whether the last one is the tenths digit is often not visible (a 100 m roll in the same colour, no
    decimal point), so up to two readings come out: all digits as integer digits, and the last one dropped as tenths.
    Each is (value, unsure, weight): unsure if a digit's probability is below [sure], a roll turns under an integer
    digit, or a blank column shows a digit that none of its neighbours reads (a digit may be missing); weight is how likely the model thinks this role
    of the last digit is. Mirrors OdoReader.kt.
    """
    blank, ints, tenths = p[0], p[1:11], p[11:21]
    merged = np.vstack([blank, ints + tenths])
    best = merged.argmax(axis=0)
    chars, prev = [], 0
    for f, c in enumerate(best):
        if c != 0 and c != prev:
            chars.append([int(c) - 1, float(merged[c, f]), [f]])
        elif c != 0:
            chars[-1][1] = max(chars[-1][1], float(merged[c, f]))
            chars[-1][2].append(f)
        prev = c
    if not chars:
        return []
    shaky = False
    for f in range(len(best)):
        if best[f] == 0 and merged[1:, f].max() > ghost:
            k = int(merged[1:, f].argmax()) + 1
            shaky |= not any(0 <= g < len(best) and best[g] == k for g in (f - 1, f + 1))

    def unsure_of(cs):
        return (shaky or any(c[1] < sure for c in cs) or any(roll_p[c[2]].max() > roll_limit for c in cs))

    last = chars[-1]
    t_share = max(float(tenths[last[0], f] / (ints[last[0], f] + tenths[last[0], f] + 1e-9)) for f in last[2])
    # As an integer digit, the last one is read from the integer classes alone (a tenths roll shows its lower digit
    # half turned; as an integer digit that may be the next one).
    f_int = max(last[2], key=lambda f: ints[:, f].max())
    d_int = int(ints[:, f_int].argmax())
    p_int = float(ints[d_int, f_int] / (ints[:, f_int].sum() + 1e-9))
    whole = chars[:-1] + [[d_int, min(last[1], p_int) if t_share > 0.5 else last[1], last[2]]]
    out = [(int("".join(str(c[0]) for c in whole)), unsure_of(whole), 1 - t_share)]
    if len(chars) > 1:
        # A tenths roll at 9 turns the next roll along (carry): its digit may already show the next one. Nearly
        # through, the tenths roll may already read 0.
        carrying = last[0] in (9, 0)
        out.append((int("".join(str(c[0]) for c in chars[:-1])), unsure_of(chars[:-1]) or carrying, t_share))
    return out


def plausible(value, last, max_jump):
    return last is None or last <= value <= last + max_jump


def choose(cands, last=None, max_jump=20000):
    """
    One value from the readings: the only plausible one against the last known mileage [last] (not lower, at most
    [max_jump] more); if both or none are plausible, the one the model prefers, marked unsure. Mirrors
    OdoPlausibility.kt.
    """
    if not cands:
        return None, True
    ok = [c for c in cands if plausible(c[0], last, max_jump)]
    if len(ok) == 1 and last is not None:
        return ok[0][0], ok[0][1]
    pick = max(ok or cands, key=lambda c: c[2])
    # Two readings and nothing to tell them apart (no last mileage, or both fit, or none does): the model's guess of
    # the role is not reliable enough to be sure (a 100 m roll in the same colour looks like any other roll).
    return pick[0], pick[1] or len(cands) > 1 or not ok


def reading(p, roll_p, last=None):
    return choose(candidates(p, roll_p), last)


# --------------------------------------------------------------------------------------------- export


def folded(model):
    out = []
    for m in [model.c1, model.c2, model.c3, model.c4, model.c5, model.t1, model.t2]:
        conv, bn = m[0], m[1]
        scale = (bn.weight / (bn.running_var + bn.eps).sqrt()).detach().numpy()
        shape = (-1,) + (1,) * (conv.weight.dim() - 1)
        w = conv.weight.detach().numpy() * scale.reshape(shape)
        b = bn.bias.detach().numpy() - bn.running_mean.detach().numpy() * scale
        out += [w.astype(np.float32), b.astype(np.float32)]
    for head in [model.head, model.roll]:
        out += [head.weight.detach().numpy().astype(np.float32), head.bias.detach().numpy().astype(np.float32)]
    return out


def export(model, path="odo-reader.bin"):
    import torch

    model.eval()
    tensors = folded(model)
    with open(path, "wb") as f:
        f.write(b"BMR1")
        f.write(struct.pack("<i", len(tensors)))
        for a in tensors:
            f.write(struct.pack("<i", a.ndim))
            for d in a.shape:
                f.write(struct.pack("<i", d))
            f.write(a.astype("<f4").tobytes())
    print(f"{path}: {os.path.getsize(path) / 1024:.0f} KB")
    i = np.arange(H * MAX_W, dtype=np.float64)
    x = (np.sin(i * 0.37) * 2.0).astype(np.float32)
    with torch.no_grad():
        logits, roll = model(torch.tensor(x).view(1, 1, H, MAX_W))
    logits, roll = logits.numpy().reshape(-1), roll.numpy().reshape(-1)
    with open("odo-reader-reference.txt", "w") as f:
        f.write(f"sum {logits.sum():.6f}\n")
        f.write(f"abs {np.abs(logits).sum():.6f}\n")
        f.write(f"roll {roll.sum():.6f}\n")
        for k in range(0, len(logits), 37):
            f.write(f"{k} {logits[k]:.6f}\n")


# --------------------------------------------------------------------------------------------- evaluation


def evaluate(cache="cache/reader-test.npz", weights="cache/reader.pt", detector="cache/detector.pt"):
    import torch
    import torch.nn.functional as F

    model = build()
    model.load_state_dict(torch.load(weights, map_location="cpu"))
    model.eval()
    data = np.load(cache)
    labels = [json.loads(s) for s in data["labels"]]

    def run(x, w):
        with torch.no_grad():
            logits, roll = model(torch.tensor(normalise(x, w)).unsqueeze(1))
        return F.softmax(logits, dim=1).numpy(), torch.sigmoid(roll).numpy()

    def report(name, p, roll, labels, skipped=0, last_mode="none"):
        right = flagged_wrong = sure_wrong = flagged_right = 0
        wrong_rows = []
        rng = np.random.default_rng(41)
        for pi, ri, label in zip(p, roll, labels):
            last = None if last_mode == "none" else max(0, label["value"] - int(rng.uniform(0, 3000)))
            value, unsure = reading(pi, ri, last)
            ok = value == label["value"]
            right += ok
            if not ok and unsure:
                flagged_wrong += 1
            if not ok and not unsure:
                sure_wrong += 1
                wrong_rows.append((label["image"], label["kind"], label["value"], value))
            if ok and unsure:
                flagged_right += 1
        n = len(labels) + skipped
        print(f"{name}: right {right}/{n} = {100 * right / n:.1f} %, right but flagged {flagged_right}, "
              f"wrong and flagged {flagged_wrong}, SURE AND WRONG {sure_wrong}"
              + (f", no line found {skipped}" if skipped else ""))
        for r in wrong_rows:
            print("   sure and wrong:", *r)
        by = {}
        rng = np.random.default_rng(41)
        for pi, ri, label in zip(p, roll, labels):
            last = None if last_mode == "none" else max(0, label["value"] - int(rng.uniform(0, 3000)))
            v, u = reading(pi, ri, last)
            by.setdefault(label["kind"], [0, 0])
            by[label["kind"]][0] += v == label["value"] and not u
            by[label["kind"]][1] += 1
        print("   right and sure by kind: " + ", ".join(f"{k} {a}/{b}" for k, (a, b) in sorted(by.items())))

    p, roll = run(data["x"], data["w"])
    report("label quads, no last mileage", p, roll, labels)
    report("label quads, last mileage 0-3000 km lower", p, roll, labels, last_mode="last")

    # The whole way: detector quads on the same photos.
    import train_detector as td

    det = td.build()
    det.load_state_dict(torch.load(detector, map_location="cpu"))
    det.eval()
    xs, ws, used, skipped = [], [], [], 0
    for label in labels:
        img = Image.open(os.path.join("testset", label["image"]))
        with torch.no_grad():
            prob = torch.sigmoid(det(torch.tensor(td.normalise(td.grey_input(img)[None])).unsqueeze(1)))[0, 0].numpy()
        quad, _ = td.quad_of(prob, label["width"], label["height"])
        if quad is None or min(math.dist(a, b) for a, b in zip(quad, quad[1:] + quad[:1])) < 2:
            skipped += 1
            continue
        x, w, _ = sample(np.asarray(img.convert("L")), label, quad)
        xs.append(x); ws.append(w); used.append(label)
    p, roll = run(np.stack(xs), np.array(ws))
    report("detector quads, no last mileage", p, roll, used, skipped)
    report("detector quads, last mileage 0-3000 km lower", p, roll, used, skipped, last_mode="last")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "prepare":
        prepare(*sys.argv[2:5])
    elif cmd == "train":
        train(int(sys.argv[2]) if len(sys.argv) > 2 else 40)
    elif cmd == "eval":
        evaluate(*sys.argv[2:])
    elif cmd == "export":
        import torch

        m = build()
        m.load_state_dict(torch.load("cache/reader.pt", map_location="cpu"))
        export(m)
    else:
        sys.exit(__doc__)
