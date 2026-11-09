# App v1 – Netzwerk und Speicherung (#8) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fundament der App: generierter API-Client, OkHttp-Stack mit Sitzung (Geräte-Token-Tausch bzw. Profil-Neuwahl), CSRF, Heimnetz-Wächter, sichere Speicherung und Repository-Grundgerüst – vollständig per JVM-Unit-Tests abgesichert.

**Architecture:** Der Client wird beim Build aus `../bike-maintainer-api/src/main/resources/maintenance-api.yaml` generiert (Gradle-Plugin `org.openapi.generator`, nur APIs, Modelle und die Serializer-Infrastruktur). Ein OkHttpClient je Server-Adresse trägt CookieJar (Arbeitsspeicher), CSRF- und Klartext-Prüfung als *Network*-Interceptors und einen `SessionAuthenticator`, der bei 401 die Sitzung erneuert. Repositories bilden die DTOs auf eigene `domain`-Modelle ab und liefern `Outcome<T>`; `AppContainer` verdrahtet alles von Hand.

**Tech Stack:** Kotlin 2.2.10 (AGP 9.5 built-in), OpenAPI Generator 7.14.0 (`kotlin`, `jvm-retrofit2`), Retrofit 3.0.0 + `converter-kotlinx-serialization`, OkHttp 5.5.1, kotlinx.serialization-json 1.9.0, kotlinx-coroutines 1.10.2, DataStore `datastore-preferences-core` 1.3.0, MockWebServer (`com.squareup.okhttp3:mockwebserver3` 5.5.1), JUnit 4, kotlinx-coroutines-test, Kover 0.9.11.

**Spec:** `docs/specs/2026-11-06-app-v1-design.md` (Abschnitte „Anmeldung, Sitzung und Speicherung“, „Netz und Sicherheit“, „Aufbau“, „Tests“, „CI“) · Ticket stargazer74/bike-maintainer-app#8

## Global Constraints

- Privacy first: keine Google-Play-Dienste, keine Tracker/Analyse/Crash-Reporter; nur freie Abhängigkeiten (F-Droid-tauglich). Kein `okhttp3:logging-interceptor` (kann Bodies loggen).
- Paket `de.bikemaintainer.app`; generierter Client im Paket `de.bikemaintainer.app.api`, nicht eingecheckt (`app/build/generated/openapi`).
- Spec-Mindestversion 2.10.0. Generierte Pfade sind relativ (`api/v1/...`) → Retrofit-Base-URL ist die Server-Adresse **mit** abschließendem `/`.
- Sitzungs-Cookie und `XSRF-TOKEN` nur im Arbeitsspeicher. Geräte-Token nur verschlüsselt (Keystore-AES-GCM) im DataStore. E-Mail und Passwort werden nie gespeichert.
- Zeitlimits: 15 s Verbindung, 30 s Lesen.
- Release-Build loggt keine Anfragen; Debug-Build höchstens Methode, Pfad, Status.
- `http://` nur, wenn die verbundene IP in: IPv4 `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, `169.254.0.0/16`, `100.64.0.0/10`; IPv6 `fc00::/7`, `fe80::/10`, `::1`. Sonst Abbruch vor dem Senden.
- Abdeckung: Kover ≥ 90 % Zeilen für `data` und `domain` (Ticket #8; die Spec nennt noch 80 % – das Ticket ist neuer und entspricht dem 90-%-Gate der anderen Repos); ausgenommen: generierter Code, `ui.*`, `KeystoreTokenCipher`, `AppContainer`, `BikeMaintainerApp`, `MainActivity`.
- Lint: `warningsAsErrors` bleibt an. Dependency-Locks nach jeder Abhängigkeitsänderung: `./gradlew :app:dependencies --write-locks`.
- Keine festen UI-Texte in diesem Ticket (kein UI). Code-Kommentare und Bezeichner Englisch, wie im bestehenden Code.
- Commits mit Trailer `Co-Authored-By: opencode <noreply@opencode.ai>`, Ticket-Referenz `(#8)`. Branch `feature/network-storage`.

## Review Focus

1. **Retry nach Token-Tausch trägt veraltete CSRF-Header/Cookies.** OkHttp führt Application-Interceptors bei Authenticator-Folgeanfragen nicht erneut aus; nach dem Tausch sind Session-ID und `XSRF-TOKEN` neu. → CSRF als *Network*-Interceptor; Test `retryAfterExchangeCarriesNewCsrfToken` in Task 4.
2. **Endlosschleife bei 401 auf den Sitzungs-Endpunkten selbst** (`device-session`, `device-login`, `profiles/{id}/select`, `config`). → Authenticator gibt dort `null` zurück; Test `unauthorizedOnDeviceLoginIsNotRetried` in Task 4.
3. **Nicht entschlüsselbares Token** (Keystore-Schlüssel weg, z. B. nach Wiederherstellung/Geräte-Reset). → wie fehlendes Token: Zustand „neu anmelden“, kein Absturz; Test `undecryptableTokenMeansNoToken` in Task 2.
4. **Leere Notiz beim Wartung-Erfassen** – Spec verlangt `minLength: 1`, leerer String gäbe 400. → Blank wird zu `null`; Test `blankNotesAreOmitted` in Task 5.
5. **Server-Adresse ohne abschließenden Slash oder mit Pfad** (`https://host:8443`, `http://192.168.178.20:8080/`). → `ApiProvider` normalisiert auf genau einen `/`; Test `baseUrlIsNormalized` in Task 5.

---

### Task 1: Client-Generierung, Abhängigkeiten, Manifest, CI mit Spec-Checkout

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`, `app/gradle.lockfile`, `app/src/main/AndroidManifest.xml`, `.github/workflows/ci.yml`, `.github/workflows/codeql.yml`, `README.md`
- Create: `app/src/main/res/xml/network_security_config.xml`
- Delete: `app/src/test/java/de/bikemaintainer/app/ExampleUnitTest.kt`
- Test: `app/src/test/java/de/bikemaintainer/app/api/GeneratedClientTest.kt`

**Interfaces:**
- Produces: generierte Interfaces `de.bikemaintainer.app.api.apis.{AuthApi, ConfigApi, ProfilesApi, VehiclesApi, MaintenanceTasksApi, MaintenanceLogsApi}` (suspend, Rückgabe `retrofit2.Response<T>`), Modelle `de.bikemaintainer.app.api.models.*`, `de.bikemaintainer.app.api.infrastructure.Serializer.kotlinxSerializationJson` (Json mit `ignoreUnknownKeys`, Adapter für `LocalDate`/`OffsetDateTime`). `BuildConfig.DEBUG` verfügbar.

- [ ] **Step 1: Generierung einrichten**

`build.gradle.kts` (root): Plugins `org.openapi.generator` 7.14.0 und `org.jetbrains.kotlin.plugin.serialization` (version.ref `kotlin`) mit `apply false`, Einträge in `libs.versions.toml`.

`app/build.gradle.kts`:
- Plugins `openapi-generator`, `kotlin-serialization`; `buildFeatures { buildConfig = true }`.
- Spec-Pfad: Gradle-Property `bm.apiSpec`, Default `rootDir/../bike-maintainer-api/src/main/resources/maintenance-api.yaml`; existiert die Datei nicht, bricht `openApiGenerate` mit der Meldung „API spec not found at <pfad> – check out bike-maintainer-api next to this repo or pass -Pbm.apiSpec=…“ ab.
- `openApiGenerate`: `generatorName = "kotlin"`, `library = "jvm-retrofit2"`, `packageName = "de.bikemaintainer.app.api"`, `outputDir = build/generated/openapi`, `configOptions`: `serializationLibrary=kotlinx_serialization`, `useCoroutines=true`, `dateLibrary=java8`, `enumPropertyNaming=UPPERCASE`; keine Docs/Tests. **`globalProperties`** `apis=""`, `models=""`, `supportingFiles="Serializer.kt,CollectionFormats.kt,BigDecimalAdapter.kt,BigIntegerAdapter.kt,LocalDateAdapter.kt,LocalDateTimeAdapter.kt,OffsetDateTimeAdapter.kt,UUIDAdapter.kt,AtomicIntegerAdapter.kt,AtomicLongAdapter.kt,AtomicBooleanAdapter.kt,URIAdapter.kt,URLAdapter.kt,StringBuilderAdapter.kt"` – damit fehlen `ApiClient.kt` (braucht logging-interceptor + scalars) und `auth/ApiKeyAuth.kt` bewusst. (So mit Gradle 9.8 + Configuration Cache erprobt: 56 Dateien.)
- `android.sourceSets["main"].kotlin.srcDir(<outputDir>/src/main/kotlin)` und `preBuild` dependsOn `openApiGenerate`. Meldet Lint Befunde im generierten Code, `lint { }` um eine `lint.xml` mit `<ignore path="build/generated/**" />` ergänzen – nicht die Regeln global abschalten.
- Abhängigkeiten: `retrofit`, `converter-kotlinx-serialization`, `okhttp`, `kotlinx-serialization-json`, `kotlinx-coroutines-android`, `datastore-preferences-core`; Test: `mockwebserver3`, `kotlinx-coroutines-test`.

- [ ] **Step 2: Failing test schreiben** – `GeneratedClientTest`

```kotlin
@Test fun vehicleResponseDeserializes() {
    val json = """{"id":7,"name":"Africa Twin","type":"MOTORCYCLE","modelYear":2020,
        "firstRegistrationDate":"2020-04-01","currentMileage":23500,
        "createdAt":"2026-01-01T10:00:00Z","updatedAt":"2026-01-02T10:00:00Z","unknownField":1}"""
    val v = Serializer.kotlinxSerializationJson.decodeFromString<VehicleResponse>(json)
    assertEquals(LocalDate.of(2020, 4, 1), v.firstRegistrationDate)
    assertEquals(VehicleType.MOTORCYCLE, v.type)
    assertNull(v.make)
}
@Test fun vehiclesApiCallsRelativePath() // MockWebServer, Retrofit mit baseUrl server.url("/"), listVehicles() → Pfad "/api/v1/vehicles"
```

- [ ] **Step 3: Test laufen lassen**

Run: `./gradlew testDebugUnitTest --tests '*GeneratedClientTest'` · Expected: PASS erst nach Step 1 (vor Step 1: Kompilierfehler, `VehicleResponse` unbekannt).

- [ ] **Step 4: Manifest und Klartext-Konfiguration**

- `<uses-permission android:name="android.permission.INTERNET" />`.
- `network_security_config.xml`: `<base-config cleartextTrafficPermitted="true">` mit System-Trust-Anchors und XML-Kommentar „home-network addresses are unknown in advance; CleartextGuard enforces the real rule“; `tools:ignore="InsecureBaseConfiguration"` am Element, damit Lint (`warningsAsErrors`) nicht scheitert. Im Manifest `android:networkSecurityConfig`.
- `allowBackup` bleibt hier unverändert (gehört zu #9).

- [ ] **Step 5: CI** – `ci.yml` und `codeql.yml` nach dem Muster des Frontends: App nach `bike-maintainer-app` auschecken (`working-directory` per `defaults.run`), Schritt „Select API branch“ (gleichnamiger Branch per `git ls-remote`, sonst `main`), API nach `bike-maintainer-api`. Pfade in Lint-/Trivy-/Report-Schritten auf `bike-maintainer-app/...` anpassen. CodeQL: `--rerun-tasks` erzeugt den Client mit, also genügt der Spec-Checkout.

- [ ] **Step 6: Verifizieren**

Run: `./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug && ./gradlew -q :app:dependencies --write-locks && git status --short app/gradle.lockfile` · Expected: BUILD SUCCESSFUL, Lockfile aktualisiert (wird mitcommittet).

- [ ] **Step 7: README** – Abschnitt „Build“: Spec-Repo daneben auschecken bzw. `-Pbm.apiSpec`; Client wird beim Build generiert.

- [ ] **Step 8: Commit** – `feat: generate API client from maintenance-api.yaml (#8)`

---

### Task 2: Speicherung – `SettingsStore`, `TokenCipher`, `DeviceTokenStore`

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/data/storage/{SettingsStore.kt, TokenCipher.kt, KeystoreTokenCipher.kt, DeviceTokenStore.kt}`
- Test: `app/src/test/java/de/bikemaintainer/app/data/storage/{SettingsStoreTest.kt, DeviceTokenStoreTest.kt, FakeTokenCipher.kt}`
- Test (instrumentiert, nicht in CI): `app/src/androidTest/java/de/bikemaintainer/app/data/storage/KeystoreTokenCipherTest.kt` (ersetzt `ExampleInstrumentedTest.kt`)

**Interfaces:**
- Consumes: nichts. Task 2 legt `enum class ServerMode { PORTAL, HOUSEHOLD }` in `app/src/main/java/de/bikemaintainer/app/domain/ServerMode.kt` an; Task 5 verwendet es.
- Produces:
  - `data class StoredSettings(val serverUrl: String?, val mode: ServerMode?, val encryptedDeviceToken: String?, val profileId: Long?)`
  - `class SettingsStore(dataStore: DataStore<Preferences>)`: `val settings: Flow<StoredSettings>`, `suspend fun current(): StoredSettings`, `suspend fun setServer(url: String, mode: ServerMode)`, `suspend fun setEncryptedDeviceToken(value: String?)`, `suspend fun setProfileId(id: Long?)`, `suspend fun clearSession()` (Token + Profil), `suspend fun clearAll()`.
  - `interface TokenCipher { fun encrypt(plain: String): String; fun decrypt(encrypted: String): String }` – `decrypt` wirft bei unlesbaren Daten `GeneralSecurityException`.
  - `class KeystoreTokenCipher(alias: String = "bm_device_token") : TokenCipher` – `AndroidKeyStore`, AES-256, `AES/GCM/NoPadding`, IV vom Cipher, Ausgabe Base64(IV ‖ Ciphertext) ohne Zeilenumbrüche.
  - `class DeviceTokenStore(settings: SettingsStore, cipher: TokenCipher)`: `suspend fun get(): String?`, `suspend fun set(token: String)`, `suspend fun clear()`.

- [ ] **Step 1: Failing tests** (DataStore per `PreferenceDataStoreFactory.create(scope = backgroundScope) { tmpFolder.newFile("t.preferences_pb") }`, `runTest`)

```kotlin
@Test fun emptyStoreHasNoSettings()          // current() == StoredSettings(null, null, null, null)
@Test fun setServerPersistsUrlAndMode()
@Test fun clearSessionKeepsServer()          // nach setServer+Token+Profil: Server bleibt, Token/Profil null
@Test fun clearAllRemovesEverything()
// DeviceTokenStoreTest mit FakeTokenCipher (encrypt = "enc:" + plain, decrypt wirft bei fehlendem Präfix)
@Test fun tokenIsStoredEncrypted()           // settings.current().encryptedDeviceToken == "enc:abc", get() == "abc"
@Test fun undecryptableTokenMeansNoToken()   // gespeichert "garbage" → get() == null und Eintrag gelöscht
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*data.storage*'` · Expected: FAIL (Klassen fehlen).
- [ ] **Step 3: Implementieren** (Schlüssel: `server_url`, `server_mode` als Enum-Name, `device_token_enc`, `profile_id`).
- [ ] **Step 4: Run** wie Step 2 · Expected: PASS. `KeystoreTokenCipherTest` (Roundtrip, zwei Verschlüsselungen desselben Texts unterscheiden sich) nur kompilieren: `./gradlew compileDebugAndroidTestKotlin`.
- [ ] **Step 5: Commit** – `feat: settings store and encrypted device token (#8)`

---

### Task 3: Netzwerk-Bausteine – CookieJar, CSRF, Heimnetz-Wächter, Debug-Log, Client-Fabrik

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/data/network/{InMemoryCookieJar.kt, CsrfInterceptor.kt, HomeNetwork.kt, CleartextGuard.kt, RequestLogInterceptor.kt, HttpClientFactory.kt}`
- Test: `app/src/test/java/de/bikemaintainer/app/data/network/{InMemoryCookieJarTest.kt, CsrfInterceptorTest.kt, HomeNetworkTest.kt, CleartextGuardTest.kt, RequestLogInterceptorTest.kt, HttpClientFactoryTest.kt}`

**Interfaces:**
- Produces:
  - `class InMemoryCookieJar : CookieJar` mit `fun clear()` und `fun valueOf(url: HttpUrl, name: String): String?`; ersetzt Cookies gleicher (name, domain, path), verwirft abgelaufene, respektiert `Cookie.matches(url)`.
  - `class CsrfInterceptor(cookieJar: InMemoryCookieJar) : Interceptor` – für POST/PUT/PATCH/DELETE Header `X-XSRF-TOKEN` = Cookie `XSRF-TOKEN`, falls vorhanden. **Wird als Network-Interceptor registriert.**
  - `object HomeNetwork { fun contains(address: InetAddress): Boolean }` – CIDR-Liste aus Global Constraints.
  - `class CleartextNotAllowedException(host: String) : IOException`
  - `class CleartextGuard(isHomeNetwork: (InetAddress) -> Boolean = HomeNetwork::contains) : Interceptor` – nur für `!url.isHttps`; Adresse aus `chain.connection()!!.route().socketAddress.address`; nicht erlaubt → wirft vor `chain.proceed`. **Network-Interceptor.**
  - `class RequestLogInterceptor(log: (String) -> Unit) : Interceptor` – Zeile `"<METHOD> <encodedPath> -> <code>"`, ohne Query, Header, Body.
  - `object HttpClientFactory { fun create(cookieJar: InMemoryCookieJar, authenticator: Authenticator, applicationInterceptors: List<Interceptor> = emptyList(), debugLog: ((String) -> Unit)? = null): OkHttpClient }` – connect 15 s, read 30 s; Network-Interceptors in der Reihenfolge `CleartextGuard`, `CsrfInterceptor`; `RequestLogInterceptor` nur wenn `debugLog != null` (AppContainer übergibt `null`, wenn `!BuildConfig.DEBUG`).

- [ ] **Step 1: Failing tests**

```kotlin
// HomeNetworkTest – je Grenze innen/außen:
@Test fun privateAndVpnRangesAreHome()  // 10.1.2.3, 172.16.0.1, 172.31.255.255, 192.168.178.20, 127.0.0.1, 169.254.1.1, 100.64.0.1, 100.127.255.255, fd12::1, fe80::1, ::1
@Test fun publicAddressesAreNotHome()   // 8.8.8.8, 172.32.0.1, 100.128.0.1, 192.169.0.1, 2001:db8::1, ::ffff:8.8.8.8
// CleartextGuardTest (MockWebServer auf 127.0.0.1)
@Test fun httpToHomeAddressIsAllowed()
@Test fun httpToNonHomeAddressFailsBeforeSending() // Prädikat { false } → CleartextNotAllowedException, server.requestCount == 0
@Test fun httpsIsNotChecked()                      // MockWebServer mit HeldCertificate, Prädikat { false } → 200
// CsrfInterceptorTest
@Test fun stateChangingRequestCarriesXsrfHeader()   // 1. GET setzt Set-Cookie XSRF-TOKEN=abc → 2. POST hat X-XSRF-TOKEN: abc
@Test fun getCarriesNoXsrfHeader()
// InMemoryCookieJarTest: replacesSameCookie, dropsExpired, clearRemovesAll
// RequestLogInterceptorTest: logsMethodPathStatusOnly  // "GET /api/v1/vehicles -> 200" für URL mit ?q=secret
// HttpClientFactoryTest: timeoutsAre15And30Seconds, noLoggingWithoutDebugLog
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*data.network*'` · Expected: FAIL.
- [ ] **Step 3: Implementieren.** `HomeNetwork`: IPv4-mapped IPv6 (`::ffff:a.b.c.d`) als IPv4 prüfen.
- [ ] **Step 4: Run** wie Step 2 · Expected: PASS.
- [ ] **Step 5: Commit** – `feat: cookie jar, CSRF header and home-network guard (#8)`

---

### Task 4: Sitzung – `SessionAuthenticator` (Token-Tausch, Profil-Neuwahl)

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/data/network/{SessionState.kt, SessionAuthenticator.kt}`
- Test: `app/src/test/java/de/bikemaintainer/app/data/network/SessionAuthenticatorTest.kt`

**Interfaces:**
- Consumes: `SettingsStore`, `DeviceTokenStore`, `InMemoryCookieJar`, `HttpClientFactory` (Task 2/3); generierte `AuthApi.createDeviceSession`, `ProfilesApi.selectProfile`.
- Produces:
  - `enum class SessionStatus { ACTIVE, LOGIN_REQUIRED, PROFILE_SELECTION_REQUIRED }`
  - `class SessionState` – `val status: StateFlow<SessionStatus>`, `val generation: Long` (AtomicLong), `fun sessionStarted()` (generation++, status ACTIVE), `fun require(status: SessionStatus)`. Auch `AuthRepository` (Task 5) ruft `sessionStarted()` nach Login/Profilwahl.
  - `class GenerationTagInterceptor(state: SessionState) : Interceptor` – Application-Interceptor, taggt jede Anfrage mit `SessionGeneration(state.generation)`.
  - `class SessionAuthenticator(settings: SettingsStore, tokens: DeviceTokenStore, state: SessionState, sessionApis: () -> Pair<AuthApi, ProfilesApi>) : Authenticator`. `sessionApis` liefert APIs auf einem Client **ohne** Authenticator, aber mit demselben CookieJar und denselben Network-Interceptors (`client.newBuilder().authenticator(Authenticator.NONE)`).

Ablauf `authenticate(route, response)` (Algorithmus, den die Signatur nicht festlegt):

```
if response.request.url.encodedPath endet auf /auth/device-session, /auth/device-login, /select oder /config → null
if response.priorResponse != null → null                        // höchstens eine Wiederholung
sentGen = response.request.tag(SessionGeneration)?.value
runBlocking { mutex.withLock {
  if state.generation != sentGen → return retry(response.request)   // anderer hat schon erneuert: kein zweiter Tausch
  when settings.current().mode:
    PORTAL:    token = tokens.get() ?: { state.require(LOGIN_REQUIRED); return null }
               r = authApi.createDeviceSession(DeviceSessionRequest(token))
               200 → tokens.set(r.body.deviceToken) ZUERST, dann state.sessionStarted(), retry
               401 → tokens.clear(); state.require(LOGIN_REQUIRED); null
               sonst/IOException → null (Status unverändert)
    HOUSEHOLD: id = settings.current().profileId ?: { require(PROFILE_SELECTION_REQUIRED); null }
               r = profilesApi.selectProfile(id)
               200 → state.sessionStarted(), retry;  404 → settings.setProfileId(null); require(PROFILE_SELECTION_REQUIRED); null
    null → null
}}
retry(req) = req.newBuilder().tag(SessionGeneration(state.generation)).build()
```

- [ ] **Step 1: Failing tests** – voller Stack (MockWebServer, `HttpClientFactory`, Retrofit, echter `SettingsStore` auf Temp-Datei, `FakeTokenCipher`), `Dispatcher` je Pfad.

```kotlin
@Test fun unauthorizedTriggersExchangeAndRetry()        // GET vehicles 401 → POST device-session (Body deviceToken=alt) 200 neu → GET vehicles 200; tokens.get()=="neu"; Reihenfolge der Requests geprüft
@Test fun newTokenIsStoredBeforeRetry()                  // Dispatcher prüft beim Retry-Request, dass tokens.get()=="neu"
@Test fun retryAfterExchangeCarriesNewCsrfToken()        // device-session setzt XSRF-TOKEN=neu; wiederholter POST trägt X-XSRF-TOKEN: neu
@Test fun parallelRequestsExchangeOnlyOnce()             // 5 parallele GETs (async, Dispatchers.IO) bekommen 401 bis Tausch; genau 1 device-session-Request; alle 200
@Test fun changedGenerationSkipsSecondExchange()         // Request mit alter Generation, state.sessionStarted() vor der Antwort → 401 → Retry ohne device-session
@Test fun exchangeRejectedRequiresLogin()                // device-session 401 → Status LOGIN_REQUIRED, Token null, Aufrufer sieht 401
@Test fun secondUnauthorizedIsNotRetriedAgain()          // nach Tausch erneut 401 → kein weiterer Tausch, Aufrufer sieht 401
@Test fun unauthorizedOnDeviceLoginIsNotRetried()        // POST device-login 401 → 0 device-session-Requests
@Test fun missingTokenRequiresLogin()
@Test fun householdReselectsProfile()                    // mode HOUSEHOLD, profileId 3: 401 → POST /profiles/3/select 200 → Retry 200
@Test fun deletedProfileRequiresProfileSelection()       // select 404 → PROFILE_SELECTION_REQUIRED, profileId null
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*SessionAuthenticatorTest'` · Expected: FAIL.
- [ ] **Step 3: Implementieren** (`kotlinx.coroutines.sync.Mutex` + `runBlocking` im Authenticator-Thread; `GenerationTagInterceptor` wird über `applicationInterceptors` an `HttpClientFactory.create` übergeben).
- [ ] **Step 4: Run** wie Step 2, danach komplette Suite `./gradlew testDebugUnitTest` · Expected: PASS; `parallelRequestsExchangeOnlyOnce` 10× wiederholt stabil (`--rerun` bzw. Schleife im Test).
- [ ] **Step 5: Commit** – `feat: renew session on 401 via device token or profile (#8)`

---

### Task 5: Domain-Modelle, `ApiProvider`, Repositories

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/domain/{Vehicle.kt, MaintenanceTask.kt, Profile.kt, ServerConfig.kt, Outcome.kt}` (+ `ServerMode.kt` aus Task 2)
- Create: `app/src/main/java/de/bikemaintainer/app/data/network/ApiProvider.kt`
- Create: `app/src/main/java/de/bikemaintainer/app/data/repository/{Mappers.kt, Outcomes.kt, AuthRepository.kt, VehicleRepository.kt, MaintenanceRepository.kt}`
- Test: `app/src/test/java/de/bikemaintainer/app/data/repository/{MappersTest.kt, AuthRepositoryTest.kt, VehicleRepositoryTest.kt, MaintenanceRepositoryTest.kt}`, `.../data/network/ApiProviderTest.kt`

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces:
  - `domain`: `enum class VehicleType { MOTORCYCLE, CAR, BICYCLE, E_BIKE, QUAD, SCOOTER }`; `data class Vehicle(id: Long, name: String, type: VehicleType, make: String?, model: String?, modelYear: Int, firstRegistrationDate: LocalDate, currentMileage: Int)`; `enum class TaskStatus { OVERDUE, DUE_SOON, OK, DONE }`; `data class MaintenanceTask(id: Long, vehicleId: Long, name: String, oneTime: Boolean, active: Boolean, status: TaskStatus, progress: Double?, kmRemaining: Int?, daysRemaining: Int?, lastServiceMileage: Int?, lastServiceDate: LocalDate?)`; `data class Profile(id: Long, displayName: String)`; `data class ServerConfig(mode: ServerMode, websiteUrl: String?)`.
  - `sealed interface Outcome<out T> { data class Success<T>(val value: T); data class Failure(val error: AppError) }`; `sealed interface AppError { data object Network; data object CleartextNotAllowed; data object Unauthorized; data object NotFound; data class Http(val status: Int, val code: String?) }` – `code` aus `ErrorResponse.code` (z. B. `EMAIL_NOT_VERIFIED`, `ACCOUNT_DISABLED`), 429 als `Http(429, …)`.
  - `class ApiProvider(settings: SettingsStore, tokens: DeviceTokenStore, state: SessionState, debugLog: ((String) -> Unit)?)`: `data class Apis(auth: AuthApi, config: ConfigApi, profiles: ProfilesApi, vehicles: VehiclesApi, tasks: MaintenanceTasksApi, logs: MaintenanceLogsApi)`; `fun apisFor(serverUrl: String): Apis` (cacht pro normalisierter URL, ein CookieJar je URL); `suspend fun apis(): Apis` (gespeicherte Adresse, sonst `IllegalStateException`); `fun reset()` (Cache + Cookies verwerfen); `companion fun normalize(url: String): HttpUrl` (trimmt, genau ein abschließender `/`).
  - `class AuthRepository(provider: ApiProvider, settings: SettingsStore, tokens: DeviceTokenStore, state: SessionState)`: `suspend fun fetchConfig(serverUrl: String): Outcome<ServerConfig>` (setzt nebenbei das CSRF-Cookie); `suspend fun deviceLogin(serverUrl: String, email: String, password: String, deviceName: String): Outcome<Unit>` (speichert Server + `PORTAL` + Token, `sessionStarted()`); `suspend fun profiles(serverUrl: String): Outcome<List<Profile>>`; `suspend fun selectProfile(serverUrl: String, id: Long): Outcome<Unit>` (speichert Server + `HOUSEHOLD` + Profil); `suspend fun logout(): Outcome<Unit>` (POST logout, danach **immer** `settings.clearSession()` + `provider.reset()`, auch bei Netzfehler); `suspend fun switchServer()` (logout-Versuch, dann `settings.clearAll()`).
  - `class VehicleRepository(provider: ApiProvider)`: `suspend fun vehicles(): Outcome<List<Vehicle>>`, `suspend fun vehicle(id: Long): Outcome<Vehicle>`, `suspend fun updateMileage(vehicle: Vehicle, newMileage: Int): Outcome<Vehicle>` – PUT mit allen übrigen Feldern aus `vehicle` (Abweichung von der Spec-Skizze `updateMileage(vehicleId, km)`: der zuletzt geladene Stand wird explizit übergeben statt im Repository gecacht).
  - `class MaintenanceRepository(provider: ApiProvider)`: `suspend fun tasks(vehicleId: Long): Outcome<List<MaintenanceTask>>`, `suspend fun logMaintenance(vehicleId: Long, performedAt: LocalDate, mileage: Int, taskIds: Set<Long>, notes: String?): Outcome<Unit>`.
  - `internal suspend fun <T> call(block: suspend () -> Response<T>): Outcome<T>` in `Outcomes.kt`: `IOException` → `Network`, `CleartextNotAllowedException` → `CleartextNotAllowed`, 401 → `Unauthorized`, 404 → `NotFound`, sonst `Http(status, code)`.

- [ ] **Step 1: Failing tests** (MockWebServer + echte Repositories)

```kotlin
@Test fun mapsVehicleAndTaskDtos()            // alle Felder inkl. null-Feldern und Enums
@Test fun updateMileageKeepsOtherFields()     // PUT-Body: name/type/make/model/modelYear/firstRegistrationDate unverändert, currentMileage=24000
@Test fun logMaintenanceSendsPerformedTaskIds() // Body performedAt "2026-11-09", mileageAtPerformed, performedTaskIds [1,2]
@Test fun blankNotesAreOmitted()              // notes "  " → kein "notes"-Feld bzw. null im Body
@Test fun errorCodeIsMapped()                 // 403 {"code":"EMAIL_NOT_VERIFIED"} → Http(403,"EMAIL_NOT_VERIFIED")
@Test fun connectionFailureIsNetworkError()   // server.close() → Network
@Test fun deviceLoginStoresServerModeAndToken()
@Test fun selectProfileStoresHouseholdProfile()
@Test fun logoutClearsSessionEvenWhenOffline()
@Test fun switchServerClearsEverything()
@Test fun fetchConfigReadsModeAndWebsiteUrl() // {"mode":"household"} → ServerConfig(HOUSEHOLD, null)
@Test fun baseUrlIsNormalized()               // "https://host:8443" und "https://host:8443//" → "https://host:8443/"; "http://192.168.178.20:8080/" bleibt
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*data.repository*' --tests '*ApiProviderTest'` · Expected: FAIL.
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: Run** wie Step 2 · Expected: PASS.
- [ ] **Step 5: Commit** – `feat: domain models and repositories (#8)`

---

### Task 6: `AppContainer`, `BikeMaintainerApp`, Kover-Gate

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/{AppContainer.kt, BikeMaintainerApp.kt}`
- Modify: `app/src/main/AndroidManifest.xml` (`android:name=".BikeMaintainerApp"`), `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: alles aus Tasks 2–5.
- Produces: `class AppContainer(context: Context)` mit `val settings: SettingsStore`, `val sessionState: SessionState`, `val authRepository`, `val vehicleRepository`, `val maintenanceRepository`; DataStore-Datei `filesDir/datastore/settings.preferences_pb` via `PreferenceDataStoreFactory.create`; `debugLog = if (BuildConfig.DEBUG) { Log.d("BikeMaintainerHttp", it) } else null`. `BikeMaintainerApp : Application` mit `lateinit var container: AppContainer` in `onCreate`. Für #9–#12.

- [ ] **Step 1: Implementieren** (keine Logik, nur Verdrahtung).
- [ ] **Step 2: Kover** – Plugin `org.jetbrains.kotlinx.kover` 0.9.11; Report-Filter: includes `de.bikemaintainer.app.data.*`, `de.bikemaintainer.app.domain.*`; excludes `de.bikemaintainer.app.api.*`, `*KeystoreTokenCipher*`; `verify { rule { minBound(90) } }` (Zeilen). Falls Kover sich nicht in AGP 9 mit eingebautem Kotlin einhängt: stattdessen AGP-JaCoCo (`enableUnitTestCoverage = true`) mit `JacocoCoverageVerification`-Task, gleiche Filter und 90 % – Entscheidung im Commit-Text begründen.
- [ ] **Step 3: CI** – Schritt „Build and test“ ruft `assembleDebug testDebugUnitTest koverVerifyDebug` (bzw. JaCoCo-Task); Kover-HTML-Report bei Fehler mit hochladen.
- [ ] **Step 4: Verifizieren**

Run: `./gradlew --no-daemon assembleDebug testDebugUnitTest koverVerifyDebug lintDebug && ./gradlew -q :app:dependencies --write-locks && git diff --stat app/gradle.lockfile` · Expected: BUILD SUCCESSFUL, Abdeckung ≥ 90 %; Lockfile ggf. mitcommitten. App startet im Emulator/auf dem Gerät weiter mit dem Grundgerüst-Bildschirm (kein Absturz in `onCreate`).

- [ ] **Step 5: Commit** – `feat: app container and coverage gate (#8)`

- [ ] **Step 6: Push + PR nach OK des Users** – PR „App v1: Netzwerk und Speicherung“, Body mit `Closes #8`, Label `app`; danach Kommentar in #8 und Checkliste im Epic backend#104 ergänzen.
