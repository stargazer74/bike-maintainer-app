# Not used in the app build. Reproduces the digit model and the scan test photos, see docs/digit-model.md.
"""Trains the small digit CNN for the worksheet scan (Epic #120) and exports it for the app.

Input: MNIST idx files in data/. Output: digits.bin (little-endian float32 weights, see DigitClassifier.kt).
Augmentation makes MNIST look more like digits written into worksheet boxes and photographed:
affine jitter, stroke thickness, blur, noise, European 1 (flag) and 7 (crossbar).
"""
import gzip, math, struct, sys
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

torch.manual_seed(7)
np.random.seed(7)
torch.set_num_threads(16)


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


def europeanise(x, y):
    """Crossbar on half of the 7s, an upstroke flag on half of the 1s (how digits are written in Germany)."""
    x = x.clone()
    for i in range(len(y)):
        img = x[i, 0]
        label = int(y[i])
        if label not in (1, 7) or np.random.rand() < 0.5:
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
            line(img, tx, top, tx - np.random.uniform(3.5, 6.0), top + np.random.uniform(3.0, 6.0), width)
    return x


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


def main():
    model = Net()
    opt = torch.optim.Adam(model.parameters(), lr=2e-3)
    epochs = int(sys.argv[1]) if len(sys.argv) > 1 else 12
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=3e-3, total_steps=epochs * (len(x_train) // 128 + 1))
    euro_train = europeanise(x_train, y_train)
    for epoch in range(epochs):
        model.train()
        perm = torch.randperm(len(x_train))
        for i in range(0, len(perm), 128):
            idx = perm[i:i + 128]
            xb = augment(euro_train[idx])
            loss = F.cross_entropy(model(xb), y_train[idx], label_smoothing=0.05)
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
        model.eval()
        with torch.no_grad():
            acc = (model(x_test).argmax(1) == y_test).float().mean().item()
            acc_aug = (model(augment(europeanise(x_test, y_test))).argmax(1) == y_test).float().mean().item()
        print(f"epoch {epoch + 1}: test {acc:.4f}, augmented test {acc_aug:.4f}", flush=True)
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
    # Reference outputs for the Kotlin test: logits of the first 20 test digits.
    with torch.no_grad():
        logits = model(x_test[:20]).numpy()
    np.save("reference_logits.npy", logits)
    with open("reference.txt", "w") as f:
        for i in range(20):
            f.write(f"{int(y_test[i])} " + " ".join(f"{v:.5f}" for v in logits[i]) + "\n")


if __name__ == "__main__":
    main()
