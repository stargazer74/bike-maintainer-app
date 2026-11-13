# Store graphics

Icon, feature graphic and phone screenshots of the store entry (Google Play, later F-Droid) in German and English.
Everything lands in `fastlane/metadata/android/<locale>/images/` (see `docs/release.md`, "Store listing"); commit the
changed PNGs.

## Icon and feature graphic

`graphics.html` holds both layouts (brand orange `#F96302`, dark `#0B0F19`, glyph "two_wheeler" as in the launcher
icon); `render.mjs` renders them with Playwright's Chromium. The font is Inter from the portal theme, so
`bike-maintainer-portal` must be checked out next to this repository. The page loads nothing from the web.

```bash
cd tools/store-graphics
npm install && npx playwright install chromium   # once
node render.mjs
```

## Screenshots

`screenshots.py` drives the app in an emulator with adb and uiautomator, against the local dev stack with the example
vehicles of the portal's screenshot tool (`bike-maintainer-portal/tools/screenshots/seed.mjs`).

Requirements:

- `bike-maintainer-deploy/dev` is running (`./build.sh && docker compose up -d`, app on :8100). **Every run deletes
  all vehicles of the screenshot account** and imports the examples.
- `bike-maintainer-portal/tools/screenshots` with its `npm install` done (it seeds the vehicles).
- An Android emulator **without Google Play** (system image `default` or `google_apis`, root is needed to switch the
  system language), visible to `adb`, with the release APK installed.

```bash
env=../bike-maintainer-deploy/dev/.env
BM_SHOT_EMAIL=$(sed -n 's/^BM_INITIAL_ADMIN_EMAIL=//p' "$env") \
BM_SHOT_PASSWORD=$(sed -n 's/^BM_INITIAL_ADMIN_PASSWORD=//p' "$env") \
python3 tools/store-graphics/screenshots.py
```

The script sets the display to 1080×2160 for the run (Play accepts at most 2:1) and resets it afterwards, fixes the
status bar (demo mode) and takes per language: 1 vehicle list, 2 vehicle with due tasks, 3 log maintenance,
4 latest measurements (dark), 5 vehicle list and 6 start screen (light). The login rate limit (10 per 15 minutes) allows
one full run; wait 15 minutes before the next.
