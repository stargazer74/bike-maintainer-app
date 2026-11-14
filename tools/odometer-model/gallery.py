"""Example gallery per display kind for docs/odometer-model.md, with the labels drawn in: ODO line green, its
digits yellow (tenths cyan), distractors red. Also a quick visual check that the labels fit the pictures.

usage: python gallery.py [--seed 1] [--out ../../docs/odometer-model] [--per-kind 6] [--plain]
"""
import argparse
import io
import os

from PIL import Image, ImageDraw

from generate import KINDS, make

THUMB = (320, 240)


def overlay(jpeg, label, scale):
    img = Image.open(io.BytesIO(jpeg)).convert("RGB")
    img = img.resize((int(img.width * scale), int(img.height * scale)), Image.Resampling.LANCZOS)
    d = ImageDraw.Draw(img)

    def poly(q, col, w=1):
        pts = [(x * scale, y * scale) for x, y in q]
        d.line(pts + pts[:1], fill=col, width=w)

    for dis in label["distractors"]:
        poly(dis["quad"], (255, 60, 60))
    for dig in label["digits"]:
        poly(dig["quad"], (0, 230, 255) if dig["tenths"] else (255, 230, 0))
    poly(label["odo"], (60, 255, 60), 2)
    text = str(label["value"]) + (f" .{label['tenths']}" if label["tenths"] is not None else "")
    d.rectangle([0, 0, 8 + 7 * len(text), 14], fill=(0, 0, 0))
    d.text((4, 2), text, fill=(255, 255, 255))
    return img


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--out", default=os.path.join("..", "..", "docs", "odometer-model"))
    p.add_argument("--per-kind", type=int, default=6)
    p.add_argument("--plain", action="store_true", help="without the labels drawn in")
    a = p.parse_args()
    os.makedirs(a.out, exist_ok=True)
    cols = 3
    for k, kind in enumerate(KINDS):
        rows = (a.per_kind + cols - 1) // cols
        sheet = Image.new("RGB", (cols * THUMB[0], rows * THUMB[1]), (255, 255, 255))
        for i in range(a.per_kind):
            index = k + i * len(KINDS)               # make() picks the kind by index
            jpeg, label = make(a.seed, index, KINDS, (640, 480))
            assert label["kind"] == kind
            if a.plain:
                thumb = Image.open(io.BytesIO(jpeg)).convert("RGB").resize(THUMB, Image.Resampling.LANCZOS)
            else:
                thumb = overlay(jpeg, label, THUMB[0] / label["width"])
            sheet.paste(thumb, ((i % cols) * THUMB[0], (i // cols) * THUMB[1]))
        path = os.path.join(a.out, f"{kind}.jpg")
        sheet.save(path, "JPEG", quality=82)
        print(path)


if __name__ == "__main__":
    main()
