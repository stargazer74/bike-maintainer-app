# Fonts for the odometer generator

Only used by `generate.py` to draw TFT displays, labels, gauge numbers and the digits on the rolls of mechanical
counters. They are not part of the app.

| File | Font | Source | License |
|---|---|---|---|
| `NotoSansMono-Regular.ttf`, `NotoSansMono-Bold.ttf` | Noto Sans Mono, © Google | Debian package `fonts-noto-mono` | SIL OFL 1.1 |
| `Quicksand-Medium.ttf`, `Quicksand-Bold.ttf` | Quicksand, © The Quicksand Project Authors | Debian package `fonts-quicksand` | SIL OFL 1.1 |

Neither font has a Reserved Font Name. The files are **subsets** (digits, Latin letters, umlauts and a few signs,
hinting dropped) to keep the repository small. Licence text: [`OFL.txt`](OFL.txt).

```bash
CH='0123456789 .,:;-+/%°ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÄÖÜäöüß()'
pyftsubset /usr/share/fonts/truetype/noto/NotoSansMono-Regular.ttf --text="$CH" \
  --output-file=NotoSansMono-Regular.ttf --layout-features='' --no-hinting   # same for the other three
```

Seven-segment and dot-matrix digits are drawn by the generator itself, no font involved.
