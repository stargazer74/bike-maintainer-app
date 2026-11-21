# bike-maintainer-app

## Build

The REST client (`de.bikemaintainer.app.api`) is generated at build time from the OpenAPI spec
`maintenance-api.yaml` of `bike-maintainer-api`; no generated code is committed. Check that repo out next to
this one (`../bike-maintainer-api`) or point to the spec with `-Pbm.apiSpec=<path>`:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew assembleDebug -Pbm.apiSpec=/path/to/maintenance-api.yaml
```

After changing dependencies: `./gradlew :app:dependencies --write-locks`. Releases: [docs/release.md](docs/release.md).

## Cucumber tests

Behaviour from the user's view is described in Gherkin (`app/src/test/resources/features/*.feature`, English like
the backend's and the web app's features; quoted UI text is the German UI). The scenarios run with the unit tests
(`testDebugUnitTest`, no emulator): `CucumberTest` runs each scenario as its own Robolectric test with the real app –
navigation, view models, repositories, offline storage and queue. Only one scenario:
`./gradlew :app:testDebugUnitTest --tests '*CucumberTest*maintenance: Recording*'`.

- **No real backend:** `features/support/FakeBackend.kt` answers every `/api/` request in memory on a MockWebServer,
  typed with the generated API models. A request to an endpoint the fake does not implement **fails the scenario** –
  add the endpoint to the fake rather than loosening that. The fake keeps rules simple (task status by km only,
  measurement status unknown); business rules are tested in the backend's Cucumber suite.
- **Replaced** is only what needs real hardware (`features/support/TestApp.kt`): the Android Keystore, WorkManager
  (sending what was kept offline is a step), notifications and the camera. Camera flows (worksheet, QR code,
  odometer photo) are not covered; their recognition has unit tests.
- **Steps:** `steps/UiSteps.kt` (generic, by visible text, label or content description; it waits for the app and
  scrolls lazy lists), `steps/BackendSteps.kt` (seed the fake and the app's stored login, assert what the app sent).
  Prefer reusing generic steps over new screen-specific ones. On failure a step lists the texts on screen.
- **Known bugs:** a scenario tagged `@bug @app-<n>` describes behaviour that is still broken and is skipped until the
  fix for that issue removes the tag.
- **Not yet runnable:** a scenario tagged `@wip` is skipped too. These open a dialog with a focused text field (mileage,
  delete account) or depend on its timing; the blinking cursor keeps Compose from ever becoming idle under Robolectric.
- When UI text in `res/values-de/strings.xml` changes, scenarios quoting it must change too.

## Installation

The app is not tied to Google services. Signed APKs are published under
[GitHub Releases](https://github.com/stargazer74/bike-maintainer-app/releases); Android 10 or newer is required.

- **Install:** download `bike-maintainer-<version>.apk` and open it (allow installing from this source when asked).
- **Updates:** with [Obtainium](https://github.com/ImranR98/Obtainium) (open source, no account, no tracking) –
  add `https://github.com/stargazer74/bike-maintainer-app` as app source. Or install each new APK by hand.
- **Verify:** compare the checksum with the `.sha256` file of the release (`sha256sum -c`), and the signing
  certificate with the fingerprint below (e.g. `apksigner verify --print-certs`). Android itself refuses updates
  that are signed with a different key.

Signing certificate (SHA-256, since 1.0.0):
`DC:3B:B4:5B:E9:D5:7D:8F:DC:4F:35:24:4D:3F:47:8B:BE:F2:4D:03:C0:DF:E8:BB:F6:5D:45:68:5F:03:61:4F`

Downloads from GitHub are visible to GitHub. The app itself only talks to the server you set it up with.

## Worksheet scan

Photographing a filled-in worksheet (Epic backend#120) runs entirely on the phone – see `docs/digit-model.md` for
the pipeline, the digit model and how to retrain it (`tools/digit-model/`).

## Odometer photo (in progress)

Reading the odometer from a photo (Epic backend#129) is trained on synthetic photos from `tools/odometer-model/` –
see `docs/odometer-model.md` for the generator, the label format, the line detector and the digit reader.
In the app, the camera button next to the mileage field reads the odometer on the phone; the value is checked and
taken over by the user (app#40).

Third-party data: the digit model is trained on MNIST (Y. LeCun, C. Cortes, C. J. C. Burges, CC BY-SA 3.0).

## License

Source code: [MIT](LICENSE). The digit model (`app/src/main/assets/digits.bin`), its training digits and the scan
test photos are licensed under CC BY-SA 3.0 – see [NOTICE.md](NOTICE.md).
