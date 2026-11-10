# bike-maintainer-app

## Build

The REST client (`de.bikemaintainer.app.api`) is generated at build time from the OpenAPI spec
`maintenance-api.yaml` of `bike-maintainer-api`; no generated code is committed. Check that repo out next to
this one (`../bike-maintainer-api`) or point to the spec with `-Pbm.apiSpec=<path>`:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew assembleDebug -Pbm.apiSpec=/path/to/maintenance-api.yaml
```

After changing dependencies: `./gradlew :app:dependencies --write-locks`.

## Worksheet scan

Photographing a filled-in worksheet (Epic backend#120) runs entirely on the phone – see `docs/digit-model.md` for
the pipeline, the digit model and how to retrain it (`tools/digit-model/`).

Third-party data: the digit model is trained on MNIST (Y. LeCun, C. Cortes, C. J. C. Burges, CC BY-SA 3.0).
