# App v1 – Einrichtung und Anmeldung (#9) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Erster Start bis zur (noch leeren) Fahrzeugliste: Server einrichten, Geräte-Login bzw. Profilwahl, Benachrichtigungs-Frage, Einstellungen mit Sprache, Abmelden und Server wechseln – im Werkstatt-Theme hell/dunkel, Deutsch/Englisch.

**Architecture:** Navigation Compose mit typsicheren Routen (`@Serializable`), ein ViewModel je Bildschirm mit `StateFlow<UiState>`, Abhängigkeiten aus `AppContainer` über `viewModelFactory`. Die Startseite ergibt sich aus einer reinen Funktion über `StoredSettings`; `SessionState.status` leitet bei „neu anmelden“/„Profil wählen“ um. ViewModels hängen an einem `AuthRepository`-Interface, damit Tests Fakes nutzen.

**Tech Stack:** Jetpack Compose + Material 3 (BOM 2026.09.00), Navigation Compose 2.10.2, lifecycle-viewmodel-compose (Version wie `lifecycleRuntimeKtx` 2.11.0), AppCompat 1.8.0 (Sprache pro App auf API 29–32), Robolectric 4.17 + compose `ui-test-junit4` für JVM-UI-Tests.

**Spec:** `docs/specs/2026-11-06-app-v1-design.md` (Abschnitte „Einrichtung“, „Einstellungen“, „Fehler im Betrieb“, „Gespeicherte Daten“, „Aufbau“, „Tests“) · Ticket stargazer74/bike-maintainer-app#9 · baut auf #8 (`data.*`, `domain`, `AppContainer`).

## Global Constraints

- Privacy first: keine Google-Play-Dienste, keine Tracker; keine Netzwerk-Schriften (Systemschrift, kein Inter-Download); E-Mail und Passwort werden nie gespeichert und nie geloggt.
- `android:allowBackup="false"`; `backup_rules.xml` und `data_extraction_rules.xml` schließen alles aus (cloud-backup und device-transfer).
- Texte nur aus `values/strings.xml` (Englisch) und `values-de/strings.xml` (Deutsch); keine festen Texte im Code. Gleiche Schlüssel in beiden Dateien (Lint `MissingTranslation` bleibt Fehler).
- Fehlertexte der Anmeldung wie in der Web-App:
  - de: „Bitte E-Mail und Passwort eingeben.“ / „E-Mail oder Passwort falsch.“ / „Dein Konto wurde gesperrt.“ / „Deine E-Mail-Adresse ist noch nicht bestätigt.“ / „Zu viele Versuche, bitte später erneut.“ / „Anmeldung fehlgeschlagen, bitte später erneut versuchen.“
  - en: „Please enter your email and password.“ / „Wrong email or password.“ / „Your account has been disabled.“ / „Your email address has not been verified yet.“ / „Too many attempts, please try again later.“ / „Login failed, please try again later.“
  - Abbildung: 401 → falsch; 403 `ACCOUNT_DISABLED` → gesperrt; 403 `EMAIL_NOT_VERIFIED` → nicht bestätigt; 429 → zu viele Versuche; sonst → unbekannt; `AppError.Network` → Verbindungstext (unten).
- Weitere feste Texte (de / en):
  - http-Hinweis: „Die Verbindung ist unverschlüsselt. Das ist nur im eigenen Heimnetz vertretbar.“ / „The connection is not encrypted. This is only acceptable in your own home network.“
  - `CleartextNotAllowed`: „Unverschlüsselte Verbindung nur im Heimnetz erlaubt – bitte https:// verwenden.“ / „Unencrypted connections are only allowed in your home network – please use https://.“
  - Kein Server erreichbar: „Der Server antwortet nicht. Bitte Adresse und Verbindung prüfen.“ / „The server does not respond. Please check the address and your connection.“
  - Kein Bike-Maintainer-Server: „Unter dieser Adresse läuft kein Bike Maintainer.“ / „There is no Bike Maintainer at this address.“
  - Ungültige Adresse: „Bitte eine gültige Adresse eingeben.“ / „Please enter a valid address.“
  - Neu anmelden: „Bitte melde dich erneut an.“ / „Please log in again.“
  - Profilwahl: Titel „Wer bist du?“ / „Who are you?“, Untertitel „Wähle deine Person, um deine Fahrzeuge zu sehen.“ / „Pick your profile to see your vehicles.“, Ladefehler „Die Personen konnten nicht geladen werden.“ / „The profiles could not be loaded.“, leer: „Noch keine Personen – lege sie in der Web-App an.“ / „No profiles yet – create them in the web app.“
  - Rechtliches: „Impressum“ / „Legal notice“, „Datenschutz“ / „Privacy“, „Website“ / „Website“.
- Server-Vorgabe `https://app.bike-maintainer.de`. Eingabe ohne Schema → `https://` voranstellen.
- Web-App-Links (Server-Adresse + Pfad): Registrieren `registrieren`, Passwort vergessen `passwort-vergessen`. Website-Links aus `websiteUrl` je App-Sprache: de `/impressum/`, `/datenschutz/`, `/`; en `/en/legal-notice/`, `/en/privacy/`, `/en/`. Ohne `websiteUrl` keine Links.
- Gerätename vorbelegt mit `Build.MODEL`, änderbar, 1–100 Zeichen, nicht nur Leerzeichen.
- Werkstatt-Farben (aus der Web-App): dunkel – Hintergrund `#0B0F19`, Fläche `#0F172A`, Fläche-Variante `#1E293B`, Rahmen `#334155`, Text `#F8FAFC`, Text gedämpft `#94A3B8`, Primär `#F97316` mit Text `#0B0F19` darauf (dunkle Schrift auf Orange, Kontrast). Hell – Hintergrund `#F8FAFC`, Fläche `#FFFFFF`, Fläche-Variante `#E2E8F0`, Rahmen `#CBD5E1`, Text `#0F172A`, gedämpft `#475569`, Primär `#C45600` mit Text `#FFFFFF`. Kein Dynamic Color. Statusfarben (für #10, hier schon im Theme): dunkel ok `#34D399`, bald fällig `#FBBF24`, überfällig `#FB7185`; hell ok `#047857`, bald fällig `#B45309`, überfällig `#BE123C`.
- Touch-Ziele ≥ 48 dp, Inhaltsbeschreibungen für Icons, Schrift skaliert (sp).
- Kover-Gate (≥ 90 % auf `data`/`domain`) bleibt grün; `ui.*` ist ausgenommen, wird aber getestet (ViewModels + ein Compose-Test).
- Lint `warningsAsErrors`; Locks nach Abhängigkeitsänderung (`./gradlew :app:dependencies --write-locks`).
- Commits mit Trailer `Co-Authored-By: opencode <noreply@opencode.ai>`, Ticket-Referenz `(#9)`. Branch `feature/setup-login`.

## Review Focus

1. **Prozess-Neustart mitten im Ablauf** (Android beendet die App im Hintergrund): Startseite muss sich allein aus `StoredSettings` ergeben, nie aus Arbeitsspeicher. → Tests `startDestination*` in Task 1.
2. **Doppeltes Absenden** (zweimal auf „Anmelden“ tippen): zweiter Login würde ein zweites Gerät registrieren. → Knopf während des Ladens gesperrt, ViewModel ignoriert Aufrufe während `loading`; Test `submitIsIgnoredWhileLoading` in Task 4.
3. **Passwort im gespeicherten Zustand**: `SavedStateHandle`/Bundles überleben Prozess-Tod. → Passwort nur im ViewModel-Speicher, nie in `SavedStateHandle`; Test `passwordIsNotSavedInState` in Task 4.
4. **Sitzung verfällt während die Einstellungen offen sind** → `SessionStatus.LOGIN_REQUIRED` führt von jeder Seite zur Anmeldung mit „Bitte melde dich erneut an.“, Rückweg gesperrt. → Test `loginRequiredNavigatesToLogin` in Task 6.
5. **Adresse mit Leerzeichen, Schrägstrich, Großschreibung oder ohne Schema** (`app.bike-maintainer.de `, `HTTP://192.168.178.20:8080/`). → Normalisierung in Task 3; Test `addressIsNormalized`.

---

### Task 1: Theme, Ressourcen, Manifest, Navigation-Gerüst

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/gradle.lockfile`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/{backup_rules.xml, data_extraction_rules.xml}`, `app/src/main/res/values/{strings.xml, themes.xml}`, `app/src/main/java/de/bikemaintainer/app/MainActivity.kt`, `app/src/main/java/de/bikemaintainer/app/ui/theme/{Color.kt, Theme.kt, Type.kt}`
- Create: `app/src/main/res/values-de/strings.xml`, `app/src/main/res/xml/locales_config.xml`, `app/src/main/res/resources.properties` (`unqualifiedResLocale=en`), `app/src/main/java/de/bikemaintainer/app/ui/theme/StatusColors.kt`, `app/src/main/java/de/bikemaintainer/app/ui/navigation/{Routes.kt, StartDestination.kt, AppNavHost.kt}`, `app/src/main/java/de/bikemaintainer/app/ui/vehicles/VehiclesPlaceholderScreen.kt`
- Test: `app/src/test/java/de/bikemaintainer/app/ui/navigation/StartDestinationTest.kt`

**Interfaces:**
- Produces:
  - Routen (`@Serializable`): `object SetupRoute`; `data class LoginRoute(val serverUrl: String, val relogin: Boolean = false)`; `data class ProfilesRoute(val serverUrl: String)`; `object VehiclesRoute`; `object SettingsRoute`.
  - `fun startDestination(settings: StoredSettings): Any` – `serverUrl == null` → `SetupRoute`; `PORTAL` ohne `encryptedDeviceToken` → `LoginRoute(serverUrl)`; `HOUSEHOLD` ohne `profileId` → `ProfilesRoute(serverUrl)`; `mode == null` → `SetupRoute`; sonst `VehiclesRoute`.
  - `@Composable fun BikeMaintainerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: …)` (ohne Dynamic Color); `data class StatusColors(ok, dueSoon, overdue)` + `LocalStatusColors`.
  - `@Composable fun AppNavHost(container: AppContainer, start: Any)`; Bildschirme aus späteren Tasks werden dort eingetragen (bis dahin Platzhalter-`Text`).
  - `VehiclesPlaceholderScreen(onOpenSettings: () -> Unit)`: Top-App-Bar mit Titel `app_name` und Einstellungen-Icon (Inhaltsbeschreibung), Text „Fahrzeuge folgen im nächsten Schritt.“ / „Vehicles follow in the next step.“ (wird in #10 ersetzt).
- `MainActivity` wird `AppCompatActivity` (nötig für `AppCompatDelegate.setApplicationLocales` unter API 33); Theme-Parent `Theme.AppCompat.DayNight.NoActionBar`. Die Activity liest `settings.current()` einmal (Splash-API nicht nötig; bis dahin leere Fläche in Hintergrundfarbe) und zeigt dann `AppNavHost`.
- Manifest: `allowBackup="false"`, `android:localeConfig="@xml/locales_config"` (de, en), `POST_NOTIFICATIONS`-Permission, Service `androidx.appcompat.app.AppLocalesMetadataHolderService` mit `autoStoreLocales=true` (Sprache unter API 33 merken).

- [ ] **Step 1: Failing test** `StartDestinationTest`

```kotlin
@Test fun startDestinationWithoutServerIsSetup()
@Test fun startDestinationPortalWithoutTokenIsLogin()   // == LoginRoute("https://x/")
@Test fun startDestinationHouseholdWithoutProfileIsProfiles()
@Test fun startDestinationWithSessionIsVehicles()        // PORTAL+Token, HOUSEHOLD+Profil
@Test fun startDestinationWithoutModeIsSetup()
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*StartDestinationTest'` · Expected: FAIL (fehlende Klassen).
- [ ] **Step 3: Implementieren** (Abhängigkeiten: `navigation-compose`, `lifecycle-viewmodel-compose`, `appcompat`, `kotlinx-serialization` ist schon da; Theme/Farben aus Global Constraints; Ressourcen und Manifest wie oben; alte Vorlagen-Farben `Purple*`/`Greeting` entfernen).
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest lintDebug assembleDebug` · Expected: PASS, Lint ohne Befund; Locks aktualisiert und committet.
- [ ] **Step 5: Commit** – `feat: workshop theme, resources and navigation skeleton (#9)`

---

### Task 2: `AuthRepository`-Interface, Konto-Abfrage, Benachrichtigungs-Merker

**Files:**
- Modify: `app/src/main/java/de/bikemaintainer/app/data/repository/AuthRepository.kt` (→ Interface), `AppContainer.kt`, `data/storage/SettingsStore.kt`, betroffene Tests unter `app/src/test/.../data/repository/`
- Create: `data/repository/ApiAuthRepository.kt`, `domain/Account.kt`
- Test: `AuthRepositoryTest.kt` (anpassen + neu), `SettingsStoreTest.kt`

**Interfaces:**
- Produces:
  - `interface AuthRepository` mit allen bisherigen Methoden (gleiche Signaturen) plus `suspend fun currentAccount(): Outcome<Account>` (`GET auth/me` über den gespeicherten Server, `callStored`) `val settings: Flow<StoredSettings>` (Weiterreichung von `SettingsStore.settings`, damit die UI nicht direkt den Store braucht) und `suspend fun markNotificationsAsked()`.
  - `class ApiAuthRepository(...) : AuthRepository` = bisherige Implementierung.
  - `data class Account(val email: String?, val displayName: String?)`.
  - `SettingsStore`: `StoredSettings.notificationsAsked: Boolean` (Schlüssel `notifications_asked`), `suspend fun setNotificationsAsked()`; `clearAll()` setzt es zurück, `clearSession()` nicht.
- `AppContainer.authRepository: AuthRepository` (Typ Interface).

- [ ] **Step 1: Failing tests** – `currentAccountMapsEmailAndDisplayName` (Portal: `{"email":"a@b.de",…}`; Haushalt: `{"displayName":"Chris",…}`), `currentAccountWithoutServerIsNotConfigured`, `notificationsAskedSurvivesClearSession`, `clearAllResetsNotificationsAsked`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*AuthRepositoryTest' --tests '*SettingsStoreTest'` · Expected: FAIL.
- [ ] **Step 3: Implementieren.** Bestehende Repository-Tests auf `ApiAuthRepository` umstellen, sonst unverändert.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest koverVerifyDebug` · Expected: PASS.
- [ ] **Step 5: Commit** – `feat: auth repository interface, current account and notification flag (#9)`

---

### Task 3: Einrichtung – Server-Adresse

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/ui/setup/{SetupViewModel.kt, SetupScreen.kt, ServerAddress.kt}`, `app/src/test/java/de/bikemaintainer/app/ui/FakeAuthRepository.kt`, `app/src/test/java/de/bikemaintainer/app/ui/MainDispatcherRule.kt`
- Modify: `ui/navigation/AppNavHost.kt`, `values/strings.xml`, `values-de/strings.xml`
- Test: `app/src/test/java/de/bikemaintainer/app/ui/setup/{SetupViewModelTest.kt, ServerAddressTest.kt}`

**Interfaces:**
- Consumes: `AuthRepository.fetchConfig(serverUrl)`, `AppError.{Network, CleartextNotAllowed, InvalidAddress, Http}`, `ServerConfig(mode, websiteUrl)`.
- Produces:
  - `object ServerAddress { fun normalize(input: String): String?; fun isCleartext(url: String): Boolean }` – trimmt, ergänzt fehlendes Schema mit `https://`, Schema klein, genau ein abschließendes `/`; ungültig → `null`.
  - `SetupViewModel(auth: AuthRepository)`: `val state: StateFlow<SetupUiState>`; `data class SetupUiState(val address: String = "https://app.bike-maintainer.de", val loading: Boolean = false, val error: SetupError? = null, val confirmCleartext: Boolean = false, val next: Any? = null)`; `enum class SetupError { INVALID_ADDRESS, NOT_REACHABLE, NOT_BIKE_MAINTAINER, CLEARTEXT_NOT_ALLOWED }`; `fun onAddressChange(String)`, `fun onContinue()`, `fun onCleartextConfirmed()`, `fun onCleartextDismissed()`, `fun onNavigated()`.
  - Ablauf `onContinue`: normalisieren (`null` → `INVALID_ADDRESS`); bei `http://` zuerst `confirmCleartext = true` (Hinweis-Dialog, einmal pro Eingabe), erst nach Bestätigung `fetchConfig`; Erfolg → `next = LoginRoute(url)` bzw. `ProfilesRoute(url)`; `Network` → `NOT_REACHABLE`; `CleartextNotAllowed` → `CLEARTEXT_NOT_ALLOWED`; `Http`/`NotFound`/sonst → `NOT_BIKE_MAINTAINER`. Nichts wird gespeichert (das tut erst Login/Profilwahl).
  - `FakeAuthRepository` (Test): konfigurierbare Antworten je Methode, zählt Aufrufe; von Tasks 4–6 wiederverwendet.

- [ ] **Step 1: Failing tests**

```kotlin
// ServerAddressTest
@Test fun addressIsNormalized()  // " app.bike-maintainer.de " → "https://app.bike-maintainer.de/"; "HTTP://192.168.178.20:8080/" → "http://192.168.178.20:8080/"; "https://h//" → "https://h/"
@Test fun invalidAddressIsNull() // "", "   ", "http://", "ftp://x"
// SetupViewModelTest (MainDispatcherRule, FakeAuthRepository)
@Test fun portalServerLeadsToLogin()
@Test fun householdServerLeadsToProfiles()
@Test fun cleartextAddressAsksBeforeConnecting()      // http:// → confirmCleartext, fetchConfig 0×; confirm → 1×
@Test fun unreachableServerShowsError()
@Test fun nonBikeMaintainerShowsError()               // Http(404,null)
@Test fun cleartextToPublicAddressShowsError()
@Test fun invalidAddressShowsErrorWithoutRequest()
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ui.setup*'` · Expected: FAIL.
- [ ] **Step 3: Implementieren.** Screen: Titel `app_name`, Feld „Server-Adresse“ / „Server address“ (`KeyboardType.Uri`, `ImeAction.Go`), Knopf „Weiter“ / „Continue“ (gesperrt beim Laden, Fortschrittsanzeige), Fehlertext unter dem Feld, Hinweis-Dialog mit „Trotzdem verbinden“ / „Connect anyway“ und „Abbrechen“ / „Cancel“. In `AppNavHost` eintragen; Navigation über `next` + `onNavigated()`.
- [ ] **Step 4: Run** wie Step 2 + `lintDebug` · Expected: PASS.
- [ ] **Step 5: Commit** – `feat: server setup screen (#9)`

---

### Task 4: Anmeldung (Portal)

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/ui/login/{LoginViewModel.kt, LoginScreen.kt}`, `app/src/main/java/de/bikemaintainer/app/ui/common/WebLinks.kt`
- Modify: `AppNavHost.kt`, `values*/strings.xml`, `app/build.gradle.kts` + `libs.versions.toml` (Test: Robolectric 4.17, `androidx.compose.ui:ui-test-junit4` als `testImplementation`, `testOptions.unitTests.isIncludeAndroidResources = true`)
- Test: `app/src/test/java/de/bikemaintainer/app/ui/login/{LoginViewModelTest.kt, LoginScreenTest.kt}`

**Interfaces:**
- Consumes: `AuthRepository.deviceLogin(serverUrl, email, password, deviceName)`, `FakeAuthRepository`, `LoginRoute(serverUrl, relogin)`.
- Produces:
  - `LoginViewModel(auth: AuthRepository, serverUrl: String, defaultDeviceName: String, relogin: Boolean)`: `state: StateFlow<LoginUiState>`; `data class LoginUiState(val email: String = "", val password: String = "", val deviceName: String, val loading: Boolean = false, val error: LoginError? = null, val reloginHint: Boolean, val done: Boolean = false)`; `enum class LoginError { REQUIRED, INVALID_CREDENTIALS, ACCOUNT_DISABLED, EMAIL_NOT_VERIFIED, TOO_MANY_ATTEMPTS, NOT_REACHABLE, UNKNOWN }`; `onEmailChange`, `onPasswordChange`, `onDeviceNameChange`, `onSubmit()`. Leere E-Mail/Passwort oder blanker Gerätename → `REQUIRED` ohne Anfrage; E-Mail getrimmt; Passwort nach Erfolg und nach Fehler aus dem Zustand gelöscht.
  - `object WebLinks { fun register(serverUrl: String): String; fun forgotPassword(serverUrl: String): String; fun imprint(websiteUrl: String, german: Boolean): String; fun privacy(…); fun home(…) }` – Pfade aus Global Constraints. Öffnen per `Intent.ACTION_VIEW` (kein WebView).
- Nach Erfolg: wenn `notificationsAsked == false` und API ≥ 33 → Benachrichtigungs-Frage (Task 6), sonst `VehiclesRoute`; Backstack bis einschließlich Setup/Login leeren.

- [ ] **Step 1: Failing tests**

```kotlin
// LoginViewModelTest
@Test fun successfulLoginIsDone()                   // deviceLogin mit getrimmter E-Mail, Gerätename
@Test fun emptyFieldsAreRequiredWithoutRequest()
@Test fun errorsAreMappedLikeTheWebApp()            // 401, 403 ACCOUNT_DISABLED, 403 EMAIL_NOT_VERIFIED, 429, 500, Network
@Test fun submitIsIgnoredWhileLoading()             // zweiter onSubmit während laufendem Login → deviceLogin 1×
@Test fun passwordIsClearedAfterFailure()
@Test fun passwordIsNotSavedInState()               // ViewModel nimmt kein SavedStateHandle für das Passwort; Zustand nach Erfolg ohne Passwort
@Test fun reloginShowsHint()
@Test fun webLinksUseServerAndLanguage()            // register("https://h/") == "https://h/registrieren"; imprint("https://w", false) == "https://w/en/legal-notice/"
// LoginScreenTest (Robolectric, createComposeRule, Locale de)
@Test fun showsWebAppErrorTextAfterFailedLogin()   // Felder füllen, „Anmelden“ tippen, Fake liefert 401 → Text „E-Mail oder Passwort falsch.“ sichtbar
@Test fun submitButtonIsDisabledWhileLoading()
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ui.login*'` · Expected: FAIL.
- [ ] **Step 3: Implementieren.** Screen: Felder E-Mail (`KeyboardType.Email`), Passwort (`PasswordVisualTransformation`, Autofill-Typ Passwort), Gerätename; Knopf „Anmelden“ / „Log in“; Links „Noch kein Konto? Konto anlegen“ / „No account yet? Create one“ und „Passwort vergessen?“ / „Forgot password?“; „Anderer Server“ / „Other server“ → zurück zu Setup. Robolectric-SDK: höchstes von 4.17 unterstütztes SDK über `app/src/test/resources/robolectric.properties` (`sdk=…`), falls 37 nicht unterstützt wird.
- [ ] **Step 4: Run** wie Step 2 + `lintDebug` · Expected: PASS.
- [ ] **Step 5: Commit** – `feat: device login screen (#9)`

---

### Task 5: Profilwahl (Haushalt)

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/ui/profiles/{ProfilesViewModel.kt, ProfilesScreen.kt}`
- Modify: `AppNavHost.kt`, `values*/strings.xml`
- Test: `app/src/test/java/de/bikemaintainer/app/ui/profiles/ProfilesViewModelTest.kt`

**Interfaces:**
- Consumes: `AuthRepository.profiles(serverUrl)`, `selectProfile(serverUrl, id)`.
- Produces: `ProfilesViewModel(auth, serverUrl)`: `state: StateFlow<ProfilesUiState>`; `data class ProfilesUiState(val profiles: List<Profile> = emptyList(), val loading: Boolean = true, val loadFailed: Boolean = false, val selecting: Long? = null, val error: Boolean = false, val done: Boolean = false)`; `fun reload()`, `fun select(id: Long)` (ignoriert, solange `selecting != null`).
- Nach Erfolg wie Task 4 (Benachrichtigungs-Frage oder `VehiclesRoute`, Backstack leeren). Bildschirm: Titel/Untertitel aus Global Constraints, Liste als Karten (Touch-Ziel ≥ 48 dp), leer → Hinweis „Noch keine Personen …“ mit Link zur Web-App (Server-Adresse), Ladefehler mit „Erneut versuchen“ / „Try again“. Profile anlegen/umbenennen/löschen bleibt in der Web-App.

- [ ] **Step 1: Failing tests** – `loadsProfilesOnStart`, `loadFailureCanBeRetried`, `selectingProfileIsDone`, `secondSelectIsIgnoredWhileSelecting`, `deletedProfileShowsErrorAndReloads` (`selectProfile` → `NotFound` → `error=true`, Liste neu geladen).
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ui.profiles*'` · Expected: FAIL.
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: Run** wie Step 2 + `lintDebug` · Expected: PASS.
- [ ] **Step 5: Commit** – `feat: household profile selection (#9)`

---

### Task 6: Benachrichtigungs-Frage, Einstellungen, Sitzungs-Umleitung

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/ui/notifications/NotificationPermissionScreen.kt`, `app/src/main/java/de/bikemaintainer/app/ui/settings/{SettingsViewModel.kt, SettingsScreen.kt}`, `app/src/main/java/de/bikemaintainer/app/ui/navigation/SessionRedirect.kt`
- Modify: `Routes.kt` (+ `data object NotificationPermissionRoute`), `AppNavHost.kt`, `values*/strings.xml`
- Test: `app/src/test/java/de/bikemaintainer/app/ui/settings/SettingsViewModelTest.kt`, `app/src/test/java/de/bikemaintainer/app/ui/navigation/SessionRedirectTest.kt`

**Interfaces:**
- Consumes: `AuthRepository.{settings, currentAccount, logout, switchServer, fetchConfig}`, `AuthRepository.markNotificationsAsked()` (Task 2), `SessionState.status`.
- Produces:
  - `NotificationPermissionScreen(onDone: () -> Unit)`: kurze Erklärung („Bike Maintainer erinnert dich an fällige Wartungen.“ / „Bike Maintainer reminds you of due maintenance.“), Knöpfe „Erlauben“ / „Allow“ (startet `RequestPermission(POST_NOTIFICATIONS)`) und „Später“ / „Later“; in beiden Fällen `markNotificationsAsked()` und weiter zu `VehiclesRoute`. Unter API 33 wird die Seite übersprungen.
  - `SettingsViewModel(auth: AuthRepository)`: `state: StateFlow<SettingsUiState>`; `data class SettingsUiState(val serverUrl: String?, val mode: ServerMode?, val account: Account?, val websiteUrl: String?, val busy: Boolean = false, val next: Any? = null)`; lädt `currentAccount()` und `fetchConfig(serverUrl)` (für `websiteUrl`; Fehler → keine Links); `fun logout()` → `next = LoginRoute(serverUrl)` bzw. `ProfilesRoute(serverUrl)` (Server bleibt); `fun switchServer()` → `next = SetupRoute`. Beide auch bei Fehlern lokal abgemeldet (Repository garantiert das).
  - Sprache: Auswahl System / Deutsch / English über `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("" | "de" | "en"))` direkt im Screen (keine Logik im ViewModel).
  - `fun sessionRedirect(status: SessionStatus, settings: StoredSettings): Any?` – `LOGIN_REQUIRED` + Server → `LoginRoute(serverUrl, relogin = true)`; `PROFILE_SELECTION_REQUIRED` + Server → `ProfilesRoute(serverUrl)`; sonst `null`. `AppNavHost` beobachtet `SessionState.status` und navigiert mit `popUpTo(0)`.

- [ ] **Step 1: Failing tests**

```kotlin
// SessionRedirectTest
@Test fun loginRequiredNavigatesToLogin()       // == LoginRoute(url, relogin = true)
@Test fun profileSelectionRequiredNavigatesToProfiles()
@Test fun activeStatusDoesNotRedirect()
// SettingsViewModelTest
@Test fun showsServerAccountAndWebsite()
@Test fun websiteLinksMissingWithoutWebsiteUrl()
@Test fun logoutReturnsToLoginForPortal()
@Test fun logoutReturnsToProfilesForHousehold()
@Test fun switchServerReturnsToSetup()
@Test fun logoutFailureStillLeavesSettings()     // Fake logout → Failure(Network) → next gesetzt
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ui.settings*' --tests '*SessionRedirectTest'` · Expected: FAIL.
- [ ] **Step 3: Implementieren.** Settings-Screen: Abschnitte „Sprache“ / „Language“, „Verbindung“ / „Connection“ (Server, Konto-E-Mail bzw. Profilname), Knöpfe „Abmelden“ / „Log out“ und „Server wechseln“ / „Change server“ (mit Bestätigungsdialog), „Rechtliches“ / „Legal“ mit den Website-Links (nur mit `websiteUrl`). Erreichbar über das Icon im Fahrzeug-Platzhalter.
- [ ] **Step 4: Verifizieren**

Run: `./gradlew --no-daemon assembleDebug testDebugUnitTest koverVerifyDebug lintDebug` · Expected: BUILD SUCCESSFUL. Danach auf dem Emulator (`installDebug`, App starten): Setup → Login/Profil → Platzhalter → Einstellungen → Abmelden durchklicken, Sprache umschalten, hell/dunkel prüfen; Ergebnis im Report festhalten.

- [ ] **Step 5: Commit** – `feat: notification prompt, settings and session redirects (#9)`
