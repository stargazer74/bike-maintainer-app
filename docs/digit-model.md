# Reading photographed worksheets (Epic #120)

The app reads a photo of a filled-in worksheet **entirely on the phone**: no cloud OCR, no Play Services, no network
request; the photo stays in memory until the values are taken over and is never saved.

## Pipeline (`domain/scan`, pure Kotlin, unit-tested on the JVM)

1. `MarkerFinder` – adaptive threshold, connected regions, the four solid corner squares that form the page frame.
2. `Homography` – page points → photo pixels from the four marker centres.
3. QR code – the QR area is straightened and handed to `QrReader` (on the device: zxing-cpp, Apache 2.0, native,
   offline). `SheetCode` parses `BMWS;v=1;t=…;id=…;…;p=x/y`.
4. `ScanLayout` – field positions for template, layout and page. Twin of the backend's `WorksheetScanLayout`; both are
   tested against `worksheet-scan-layout-v1.json` (contract: `bike-maintainer-backend/docs/worksheet-scan-layout.md`).
5. Per field: alignment to its own printed frame (`SheetReader.frameOffset`, ±5 pt). The markers fix the page as a
   whole, but lens distortion and paper that is not flat move single fields by a few points; without this, a box
   border ended up in the read area and came out as a confident "1" – also in empty boxes.
6. Per digit box: ink relative to the paper, border remnants and specks removed, normalised like MNIST (20×20,
   centred by mass) → `DigitClassifier`. Checkboxes: share of ink.
7. A field is *uncertain* if a digit's probability is below 0.8 or the digits have a gap; the review screen
   highlights it.

## Digit model (`app/src/main/assets/digits.bin`, 424 KB)

Small CNN (conv 3×3 ×16 → pool → conv 3×3 ×32 → pool → dense 64 → dense 10), inference in plain Kotlin
(`DigitClassifier`). Trained with `tools/digit-model/train.py` (PyTorch, CPU, ~5 min) on the MNIST training set with
augmentation: affine jitter, stroke width, blur, noise, and **European 1 (upstroke) and 7 (crossbar)**.
MNIST test set: 99.2 %, augmented test set: 98.3 %.

```bash
cd tools/digit-model   # MNIST idx files in data/
python train.py 15     # writes digits.bin and reference outputs
```

`DigitClassifierTest` checks that the Kotlin inference matches PyTorch on reference digits.

**Data:** MNIST, Y. LeCun, C. Cortes, C. J. C. Burges, licensed CC BY-SA 3.0. The weights are derived from it.

## Sample photos (`app/src/test/resources/scan/`)

`tools/digit-model/synth.py` takes worksheet pages rendered by the backend (`-Dbm.dumpWorksheets=<dir>` in
`WorksheetPdfHelperTest`), writes digits of the **MNIST test set** (not used for training) into the boxes, ticks
checkboxes and "photographs" the page: straight, at an angle, in dim light with shadow, noise and blur. Expected values
sit next to each photo as JSON. `SheetReaderTest` requires most fields to be right and **every wrong value to be
flagged as uncertain**.

`SheetReaderTest` also reads two of them with simulated lens distortion and requires empty fields to stay empty.

**Diagnosing a real photo:** `BM_SCAN_PHOTO=<jpg> BM_SCAN_QR='<qr text>' ./gradlew testDebugUnitTest --tests
'*ScanDiagnosticsTest*' --rerun` writes, per digit box, the box, the ink and the 28×28 model input plus the top-3
guesses to `build/scan-debug/` (`BM_SCAN_OUT` to change). The photo stays on the machine.

One-off check on 24 further generated photos (other seeds): all sheets found, 236 of 246 fields right, 9 of the 10
wrong ones flagged. Real handwriting differs from MNIST – the review screen with the photo of each field is the
safety net, nothing is saved before "Save".
