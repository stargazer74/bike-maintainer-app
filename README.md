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

## Installation

The app is not tied to Google services. Signed APKs are published under
[GitHub Releases](https://github.com/stargazer74/bike-maintainer-app/releases); Android 10 or newer is required.

- **Install:** download `bike-maintainer-<version>.apk` and open it (allow installing from this source when asked).
- **Updates:** with [Obtainium](https://github.com/ImranR98/Obtainium) (open source, no account, no tracking) –
  add `https://github.com/stargazer74/bike-maintainer-app` as app source. Or install each new APK by hand.
- **Verify:** compare the checksum with the `.sha256` file of the release (`sha256sum -c`), and the signing
  certificate with the fingerprint below (e.g. `apksigner verify --print-certs`). Android itself refuses updates
  that are signed with a different key.

Signing certificate (SHA-256): _published with the first release_

Downloads from GitHub are visible to GitHub. The app itself only talks to the server you set it up with.

## Worksheet scan

Photographing a filled-in worksheet (Epic backend#120) runs entirely on the phone – see `docs/digit-model.md` for
the pipeline, the digit model and how to retrain it (`tools/digit-model/`).

Third-party data: the digit model is trained on MNIST (Y. LeCun, C. Cortes, C. J. C. Burges, CC BY-SA 3.0).

## License

[MIT](LICENSE)
