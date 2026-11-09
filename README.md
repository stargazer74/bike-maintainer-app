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
