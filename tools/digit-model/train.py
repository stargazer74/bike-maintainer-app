# Not used in the app build. Reproduces the digit model and the scan test photos, see docs/digit-model.md.
"""Trains the small digit CNN for the worksheet scan (Epic #120) and exports it for the app.

Input: MNIST idx files in data/. Output: digits.bin (little-endian float32 weights, see DigitClassifier.kt).
Augmentation makes MNIST look more like digits written into worksheet boxes and photographed:
affine jitter, stroke thickness, blur, noise, European 1 (flag, also long) and 7 (crossbar), 9 with a tail bent
to the left (as often written in Germany; the straight MNIST 9 alone made the model read it as 5 or 3).

Real digits: real-digits.npz holds digits cut from photos of filled-in worksheets, exactly as the app feeds them to
the model (ScanSampleExportTest), labelled from the known values, with a group per sheet. They are mixed into every
batch. REAL_TRAIN / REAL_TEST (comma-separated groups) split them for an honest check on sheets not trained on;
by default all groups are trained on.

Ambiguous shapes: a 7 without crossbar and a 1 with a long flag are trained with a soft target (AMBIG, default 0.4 on
the other digit), so the model is unsure about them and the app flags the field, instead of reading the 1 of someone
who crosses the 7 as a confident 7. On sheets not trained on this cut the confidently wrong digits from 21 to 1 of
1,616 (more right digits are flagged in turn).
"""
import gzip, math, os, struct, sys
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

torch.manual_seed(7)
np.random.seed(7)
torch.set_num_threads(int(os.environ.get("THREADS", "16")))


def load(name):
    with gzip.open(f"data/{name}.gz", "rb") as f:
        data = f.read()
    if "labels" in name:
        return np.frombuffer(data, np.uint8, offset=8).astype(np.int64)
    return np.frombuffer(data, np.uint8, offset=16).reshape(-1, 28, 28).astype(np.float32) / 255.0


x_train = torch.tensor(load("train-images-idx3-ubyte")).unsqueeze(1)
y_train = torch.tensor(load("train-labels-idx1-ubyte"))
x_test = torch.tensor(load("t10k-images-idx3-ubyte")).unsqueeze(1)
y_test = torch.tensor(load("t10k-labels-idx1-ubyte"))


real = np.load("real-digits.npz")
real_x = torch.tensor(real["x"].astype(np.float32) / 255.0).unsqueeze(1)
real_y = torch.tensor(real["y"].astype(np.int64))
real_group = real["group"]
all_groups = sorted(set(real_group))
train_groups = os.environ.get("REAL_TRAIN", ",".join(all_groups)).split(",")
test_groups = [g for g in os.environ.get("REAL_TEST", "").split(",") if g]
real_train = torch.tensor(np.isin(real_group, train_groups))
real_test = torch.tensor(np.isin(real_group, test_groups))
REAL_PER_BATCH = 16


class Net(nn.Module):
    def __init__(self):
        super().__init__()
        self.c1 = nn.Conv2d(1, 16, 3, padding=1)
        self.c2 = nn.Conv2d(16, 32, 3, padding=1)
        self.f1 = nn.Linear(32 * 7 * 7, 64)
        self.f2 = nn.Linear(64, 10)
        self.drop = nn.Dropout(0.3)

    def forward(self, x):
        x = F.max_pool2d(F.relu(self.c1(x)), 2)
        x = F.max_pool2d(F.relu(self.c2(x)), 2)
        x = x.flatten(1)
        x = self.drop(F.relu(self.f1(x)))
        return self.f2(x)


def line(img, x0, y0, x1, y1, width):
    """Draws an anti-aliased stroke onto a 28x28 tensor (in place, max)."""
    ys, xs = torch.meshgrid(torch.arange(28.0), torch.arange(28.0), indexing="ij")
    dx, dy = x1 - x0, y1 - y0
    length2 = dx * dx + dy * dy + 1e-6
    t = ((xs - x0) * dx + (ys - y0) * dy) / length2
    t = t.clamp(0, 1)
    d = torch.sqrt((xs - (x0 + t * dx)) ** 2 + (ys - (y0 + t * dy)) ** 2)
    stroke = (1.0 - (d - width / 2).clamp(min=0)).clamp(0, 1)
    img.copy_(torch.maximum(img, stroke))


def bezier(img, p0, p1, p2, width, steps=8):
    """Quadratic Bezier curve as a chain of strokes."""
    prev = p0
    for k in range(1, steps + 1):
        t = k / steps
        pt = tuple((1 - t) ** 2 * a + 2 * (1 - t) * t * b + t * t * c for a, b, c in zip(p0, p1, p2))
        line(img, prev[0], prev[1], pt[0], pt[1], width)
        prev = pt


def hook_nine(img):
    """Replaces the tail of a 9 below its loop by one that bends to the left at the bottom."""
    rows = torch.nonzero(img.sum(1) > 0.5).flatten()
    if len(rows) < 8:
        return
    top, bottom = rows[0].item(), rows[-1].item()
    # Lowest row of the loop: the last row (in the upper 70 %) with two separate runs of ink.
    loop_bottom = None
    for y in range(top, top + int(0.7 * (bottom - top)) + 1):
        ink = (img[y] > 0.5).int()
        if int((ink[1:] - ink[:-1]).clamp(min=0).sum()) + int(ink[0]) >= 2:
            loop_bottom = y
    if loop_bottom is None or bottom - loop_bottom < 5:
        return
    ink = torch.nonzero(img[loop_bottom] > 0.5).flatten()
    xr = ink.max().item()
    img[loop_bottom + 2:] = 0
    width = np.random.uniform(1.6, 2.6)
    end_y = bottom - np.random.uniform(0, 2.5)
    bezier(img, (xr, loop_bottom), (xr + np.random.uniform(-0.5, 2.0), bottom + np.random.uniform(0, 1.5)),
           (xr - np.random.uniform(4.0, 9.0), end_y), width)


def europeanise(x, y):
    """Crossbar on half of the 7s, an upstroke flag (short or long) on half of the 1s, a bent tail on half of the 9s
    (how digits are written in Germany). Also returns which digits are ambiguous: a 7 without crossbar and a 1 with a
    long flag look the same – which one it is depends on the hand (the one who crosses the 7 writes the 1 like that)."""
    x = x.clone()
    ambiguous = torch.zeros(len(y), dtype=torch.bool)
    for i in range(len(y)):
        img = x[i, 0]
        label = int(y[i])
        if label not in (1, 7, 9) or np.random.rand() < 0.5:
            # A 7 without crossbar looks like a 1 with a long flag.
            ambiguous[i] = label == 7
            continue
        if label == 9:
            hook_nine(img)
            continue
        rows = torch.nonzero(img.sum(1) > 0.5).flatten()
        cols = torch.nonzero(img.sum(0) > 0.5).flatten()
        if len(rows) < 2 or len(cols) < 1:
            continue
        top, bottom = rows[0].item(), rows[-1].item()
        mid = (top + bottom) / 2 + np.random.uniform(-1.5, 2.5)
        ink = torch.nonzero(img[int(round(mid))] > 0.5).flatten()
        width = np.random.uniform(1.6, 2.6)
        if label == 7:
            centre = ink.float().mean().item() if len(ink) else 14.0
            half = np.random.uniform(3.0, 5.0)
            line(img, centre - half, mid, centre + half, mid + np.random.uniform(-1, 1), width)
        else:
            top_ink = torch.nonzero(img[top] > 0.5).flatten()
            tx = top_ink.float().mean().item() if len(top_ink) else 14.0
            # Short flag, or a long one reaching down to half the height (a "1" that looks like "^").
            if np.random.rand() < 0.5:
                dx, dy = np.random.uniform(3.5, 6.0), np.random.uniform(3.0, 6.0)
            else:
                dx, dy = np.random.uniform(5.0, 9.0), np.random.uniform(6.0, 0.5 * (bottom - top) + 6.0)
                ambiguous[i] = True
            line(img, tx, top, tx - dx, top + dy, width)
    return x, ambiguous


def augment(x):
    n = x.shape[0]
    angle = torch.empty(n).uniform_(-0.22, 0.22)
    shear = torch.empty(n).uniform_(-0.25, 0.25)
    scale = torch.empty(n).uniform_(0.85, 1.12)
    tx = torch.empty(n).uniform_(-0.12, 0.12)
    ty = torch.empty(n).uniform_(-0.12, 0.12)
    cos, sin = torch.cos(angle) / scale, torch.sin(angle) / scale
    theta = torch.stack([
        torch.stack([cos, -sin + shear, tx], 1),
        torch.stack([sin, cos, ty], 1)], 1)
    grid = F.affine_grid(theta, x.shape, align_corners=False)
    x = F.grid_sample(x, grid, align_corners=False, padding_mode="zeros")
    # Stroke thickness: thicker (pen) or thinner (pencil).
    r = torch.rand(n)
    thick = F.max_pool2d(x, 3, 1, 1)
    thin = -F.max_pool2d(-x, 3, 1, 1)
    x = torch.where((r < 0.25).view(-1, 1, 1, 1), thick, x)
    x = torch.where((r > 0.85).view(-1, 1, 1, 1), 0.5 * x + 0.5 * thin, x)
    # Blur, contrast and noise like a photo.
    blur = torch.rand(n) < 0.3
    kernel = torch.tensor([[1., 2., 1.], [2., 4., 2.], [1., 2., 1.]]).view(1, 1, 3, 3) / 16
    x = torch.where(blur.view(-1, 1, 1, 1), F.conv2d(x, kernel, padding=1), x)
    x = x * torch.empty(n, 1, 1, 1).uniform_(0.6, 1.1)
    x = x + torch.randn_like(x) * torch.empty(n, 1, 1, 1).uniform_(0, 0.08)
    return x.clamp(0, 1)


def augment_light(x):
    """Small shifts, rotation and scale for the real digits."""
    n = x.shape[0]
    angle = torch.empty(n).uniform_(-0.1, 0.1)
    scale = torch.empty(n).uniform_(0.92, 1.08)
    tx = torch.empty(n).uniform_(-0.06, 0.06)
    ty = torch.empty(n).uniform_(-0.06, 0.06)
    cos, sin = torch.cos(angle) / scale, torch.sin(angle) / scale
    theta = torch.stack([torch.stack([cos, -sin, tx], 1), torch.stack([sin, cos, ty], 1)], 1)
    grid = F.affine_grid(theta, x.shape, align_corners=False)
    return F.grid_sample(x, grid, align_corners=False, padding_mode="zeros").clamp(0, 1)


def main():
    model = Net()
    opt = torch.optim.Adam(model.parameters(), lr=2e-3)
    epochs = int(sys.argv[1]) if len(sys.argv) > 1 else 12
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=3e-3, total_steps=epochs * (len(x_train) // 128 + 1))
    euro_train, ambiguous = europeanise(x_train, y_train)
    share = float(os.environ.get("AMBIG", "0.4"))
    for epoch in range(epochs):
        model.train()
        perm = torch.randperm(len(x_train))
        for i in range(0, len(perm), 128):
            idx = perm[i:i + 128]
            xb = augment(euro_train[idx])
            yb = y_train[idx]
            target = F.one_hot(yb, 10).float()
            amb = ambiguous[idx]
            other = torch.where(yb == 7, 1, 7)
            target[amb] = target[amb] * (1 - share) + F.one_hot(other[amb], 10).float() * share
            pool = torch.nonzero(real_train).flatten()
            if len(pool):
                pick = pool[torch.randint(len(pool), (REAL_PER_BATCH,))]
                # Real digits are already photographed: mild jitter only.
                xb = torch.cat([xb, augment_light(real_x[pick])])
                yb = torch.cat([yb, real_y[pick]])
                target = torch.cat([target, F.one_hot(real_y[pick], 10).float()])
            loss = F.cross_entropy(model(xb), target, label_smoothing=0.05)
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
        model.eval()
        with torch.no_grad():
            acc = (model(x_test).argmax(1) == y_test).float().mean().item()
            acc_aug = (model(augment(europeanise(x_test, y_test)[0])).argmax(1) == y_test).float().mean().item()
            line_ = f"epoch {epoch + 1}: test {acc:.4f}, augmented test {acc_aug:.4f}"
            if real_test.any():
                pred = model(real_x[real_test]).argmax(1)
                truth = real_y[real_test]
                line_ += f", real held-out {(pred == truth).float().mean().item():.4f}"
                wrong = [(int(t), int(p)) for t, p in zip(truth, pred) if t != p]
                if epoch == epochs - 1:
                    print("real held-out confusions (true, read):", sorted(set((w, wrong.count(w)) for w in wrong), key=lambda z: -z[1]))
        print(line_, flush=True)
    export(model)


def export(model):
    """Magic "BMD1", then per tensor: rank, dims, float32 values – in the order the app reads them."""
    tensors = [model.c1.weight, model.c1.bias, model.c2.weight, model.c2.bias,
               model.f1.weight, model.f1.bias, model.f2.weight, model.f2.bias]
    with open("digits.bin", "wb") as f:
        f.write(b"BMD1")
        for t in tensors:
            a = t.detach().numpy().astype("<f4")
            f.write(struct.pack("<i", a.ndim))
            for d in a.shape:
                f.write(struct.pack("<i", d))
            f.write(a.tobytes())
    # Reference outputs for DigitClassifierTest (copy to app/src/test/resources/scan/digit-reference.txt): the first 10
    # MNIST test digits with the logits of this model.
    with torch.no_grad():
        logits = model(x_test[:10]).numpy()
    with open("reference.txt", "w") as f:
        f.write("# label, 784 pixels (0..255, MNIST test set), 10 logits of the PyTorch model\n")
        for i in range(10):
            pixels = " ".join(str(int(round(v * 255))) for v in x_test[i, 0].flatten().tolist())
            f.write(f"{int(y_test[i])} {pixels} | " + " ".join(f"{v:.5f}" for v in logits[i]) + "\n")

def load_bin(path):
    """The model from a digits.bin written by export()."""
    model = Net()
    tensors = [model.c1.weight, model.c1.bias, model.c2.weight, model.c2.bias,
               model.f1.weight, model.f1.bias, model.f2.weight, model.f2.bias]
    with open(path, "rb") as f:
        assert f.read(4) == b"BMD1"
        for t in tensors:
            rank = struct.unpack("<i", f.read(4))[0]
            shape = [struct.unpack("<i", f.read(4))[0] for _ in range(rank)]
            data = np.frombuffer(f.read(4 * int(np.prod(shape))), "<f4").reshape(shape)
            t.data.copy_(torch.tensor(data))
    return model.eval()


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--export-from":
        # Rewrites reference.txt (and digits.bin, unchanged) from an existing model without training.
        export(load_bin(sys.argv[2]))
    else:
        main()
