# Notices

The source code of this app is licensed under the [MIT License](LICENSE). The files below are **not** – they are
licensed under the **Creative Commons Attribution-ShareAlike 3.0 Unported** license (CC BY-SA 3.0), full text in
[`LICENSES/CC-BY-SA-3.0.txt`](LICENSES/CC-BY-SA-3.0.txt), also at <https://creativecommons.org/licenses/by-sa/3.0/>.

## Digit model and data derived from MNIST

The digit model of the worksheet scan is trained on the **MNIST database of handwritten digits** by Yann LeCun,
Corinna Cortes and Christopher J. C. Burges (<http://yann.lecun.com/exdb/mnist/>), licensed under CC BY-SA 3.0.
Whether trained weights are an adaptation of their training data is not settled; to be on the safe side they are
treated as one and shared under the same license.

| Files | What it is | Changes to MNIST |
|---|---|---|
| `app/src/main/assets/digits.bin` (also inside the APK) | Weights of the digit model | Trained on MNIST training digits with augmentation (affine jitter, stroke width, blur, noise, European 1, 7 and 9; a 7 without crossbar and a 1 with a long flag with a soft target) together with the real digits below; see `docs/digit-model.md` |
| `app/src/test/resources/scan/digit-reference.txt` | Ten MNIST test digits and the model's outputs | Unchanged pixels, outputs added |
| `app/src/test/resources/scan/{valves-straight,valves-page2-oblique,tires-oblique,chain-dim,brakes-oblique-dim}.jpg` | Generated worksheet photos | MNIST test digits written into worksheet boxes and "photographed" (`tools/digit-model/synth.py`) |

## Real handwriting and photos

| Files | What it is |
|---|---|
| `tools/digit-model/real-digits.npz` | 2,756 handwritten digits cut from photos of filled-in worksheets |
| `app/src/test/resources/scan/real-*.jpg` | Photos of filled-in worksheets (metadata removed) |

© 2026 Chris Wohlbrecht, licensed under CC BY-SA 3.0, as the model they are trained into.

## Other third-party material

- Launcher icon: Material Symbols "two_wheeler", Google, Apache License 2.0.
