# Reading photographed worksheets (Epic #120)

The app reads a photo of a filled-in worksheet **entirely on the phone**: no cloud OCR, no Play Services, no network
request; the photo stays in memory until the values are taken over and is never saved.

## Pipeline (`domain/scan`, pure Kotlin, unit-tested on the JVM)

1. `MarkerFinder` – adaptive threshold, connected regions, the four solid corner squares that form the page frame.
   Of all frames, the largest one wins whose four marker sizes fit the perspective they span and that has no other
   marker-sized square on the page (the QR code's solid patches excepted). Without these checks a dark knot in the
   table or the marker of another sheet next to the page made a bigger frame and won.
2. `Homography` – page points → photo pixels from the four marker centres.
3. QR code – the QR area is straightened and handed to `QrReader` (on the device: zxing-cpp, Apache 2.0, native,
   offline). `SheetCode` parses `BMWS;v=1;t=…;id=…;…;p=x/y`.
4. `ScanLayout` – field positions for template, layout and page. Twin of the backend's `WorksheetScanLayout`; both are
   tested against `worksheet-scan-layout-v1.json` (contract: `bike-maintainer-backend/docs/worksheet-scan-layout.md`).
5. Per field: alignment to its own printed frame (`SheetReader.frameOffset`, ±5 pt). The markers fix the page as a
   whole, but lens distortion and paper that is not flat move single fields by a few points; without this, a box
   border ended up in the read area and came out as a confident "1" – also in empty boxes.
6. Per digit box: ink relative to the paper and to the box's darkest stroke (pale or blurred writing; below 18 %
   contrast the box is empty), border remnants and specks removed, normalised like MNIST (20×20, centred by mass) →
   `DigitClassifier`. Checkboxes: share of ink.
7. A field is *uncertain* if a digit's probability is below 0.8 or the digits have a gap; the review screen
   highlights it.

## Digit model (`app/src/main/assets/digits.bin`, 424 KB)

Small CNN (conv 3×3 ×16 → pool → conv 3×3 ×32 → pool → dense 64 → dense 10), inference in plain Kotlin
(`DigitClassifier`). Trained with `tools/digit-model/train.py` (PyTorch, CPU, ~5 min) on the MNIST training set with
augmentation: affine jitter, stroke width, blur, noise, and **European 1 (short or long upstroke), 7 (crossbar) and
9 (tail bent to the left)** – plus **real digits** from photos of filled-in worksheets (`real-digits.npz`, 1,140
digits of four sheets, 16 per batch).
MNIST test set: 99.0 %, augmented test set: 98.2 %. Real digits of sheets not trained on (`REAL_TRAIN` /
`REAL_TEST`): 94.4 % and 99.5 % in the two directions; the misses are 9s written like an "S", which only the other
sheet had.

```bash
cd tools/digit-model   # MNIST idx files in data/ (not in the repo)
python train.py 15     # writes digits.bin and reference.txt
cp digits.bin ../../app/src/main/assets/ && cp reference.txt ../../app/src/test/resources/scan/digit-reference.txt
REAL_TRAIN=valveA,tiresA REAL_TEST=valveB,tiresB python train.py 15   # check on sheets not trained on
python train.py --export-from digits.bin   # only rewrite reference.txt for an existing model
```

`DigitClassifierTest` checks that the Kotlin inference matches PyTorch on reference digits.

**Real digits:** `ScanSampleExportTest` cuts every filled-in box out of photos exactly as `SheetReader` does and labels
it from the known values (`BM_SCAN_SAMPLES=<list of jpg|json|group> BM_SCAN_OUT=<txt> ./gradlew testDebugUnitTest
--tests '*ScanSampleExportTest*' --rerun`); the text is packed into `real-digits.npz` (28×28 uint8, label, group).
More sheets from other hands make the model better – add them with a new group name.

**License:** MNIST (Y. LeCun, C. Cortes, C. J. C. Burges) is licensed CC BY-SA 3.0. The weights are treated as an
adaptation and shared under CC BY-SA 3.0, like `real-digits.npz`, `digit-reference.txt` and the test photos – not
under the MIT license of the code. Details: `NOTICE.md`; the APK carries `assets/digits-NOTICE.txt` next to the model.
After retraining, keep the list of changes in both up to date.

## Sample photos (`app/src/test/resources/scan/`)

`tools/digit-model/synth.py` takes worksheet pages rendered by the backend (`-Dbm.dumpWorksheets=<dir>` in
`WorksheetPdfHelperTest`), writes digits of the **MNIST test set** (not used for training) into the boxes, ticks
checkboxes and "photographs" the page: straight, at an angle, in dim light with shadow, noise and blur. Expected values
sit next to each photo as JSON. `SheetReaderTest` requires most fields to be right and **every wrong value to be
flagged as uncertain**.

`SheetReaderTest` also reads two of them with simulated lens distortion and requires empty fields to stay empty.

`real-*.jpg` are real phone photos (metadata stripped): a dark knot in the table next to the page, another sheet in
the picture, pale writing. They are part of the training data of the model, so they guard the pipeline, not the
model's accuracy on new handwriting.

**Diagnosing a real photo:** `BM_SCAN_PHOTO=<jpg> BM_SCAN_QR='<qr text>' ./gradlew testDebugUnitTest --tests
'*ScanDiagnosticsTest*' --rerun` writes, per digit box, the box, the ink and the 28×28 model input plus the top-3
guesses to `build/scan-debug/` (`BM_SCAN_OUT` to change). The photo stays on the machine.

One-off check on 24 further generated photos (other seeds): all sheets found, 236 of 246 fields right, 9 of the 10
wrong ones flagged. Real handwriting differs from MNIST – the review screen with the photo of each field is the
safety net, nothing is saved before "Save".
