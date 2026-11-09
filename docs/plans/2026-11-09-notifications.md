# App v1 – Benachrichtigungen bei fälligen Wartungen (#12) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Einmal täglich prüft die App im Hintergrund die Fälligkeiten. Was neu fällig oder überfällig wird, meldet sie einmal, ohne Google-Push. Ein Tipp öffnet das Fahrzeug.

**Architecture:**
- Eine reine Funktion `dueUpdates` in `domain` vergleicht den zuletzt gemeldeten Stand (Aufgaben-ID → Status) mit dem aktuellen.
- `DueTaskCheck` (Paket `notifications`) lädt über die vorhandenen Repositories, ruft `dueUpdates` auf, meldet über `DueTaskNotifier` und speichert den Stand in `SettingsStore`. Es ist reines Kotlin und mit Fakes testbar.
- `DueTasksWorker` (WorkManager `CoroutineWorker`) ist nur die Hülle darum. Er wird über eine eigene `WorkerFactory` gebaut, die die App über `Configuration.Provider` setzt.
- `ReminderScheduler` plant den täglichen Job mit Netz-Bedingung und erstem Lauf am nächsten Morgen um 7 Uhr, oder storniert ihn. `sync()` wird beim App-Start, nach der Anmeldung, nach der Erlaubnisfrage und beim Umschalten in den Einstellungen aufgerufen.

**Tech Stack:**
- wie #11
- **neu:** `androidx.work:work-runtime-ktx` 2.11.2 und für Tests `androidx.work:work-testing` 2.11.2. Beide gehören zu AndroidX, die Spec verlangt WorkManager, und sie bringen keinen Google-Play-Dienst mit.
- `NotificationCompat`/`NotificationManagerCompat` aus androidx.core (schon da).
- Robolectric `ShadowNotificationManager` für Tests.

**Spec:** `docs/specs/2026-11-06-app-v1-design.md` (Abschnitte „Benachrichtigungen“, „Einstellungen“, „Aufbau“ → `notifications`, „Tests“ → Benachrichtigungslogik) · Ticket stargazer74/bike-maintainer-app#12 · baut auf #8–#11.

## Global Constraints

- **Privacy first:**
  - Kein Push-Dienst, keine Google-Play-Dienste, keine Dritt-Anfragen.
  - Gespeichert wird nur Aufgaben-ID → Status (in `SettingsStore`), keine Namen.
  - Fahrzeug- und Aufgabennamen kommen nicht in Logs.
  - Auf dem Sperrbildschirm erscheint nur „Wartung fällig“ (`VISIBILITY_PRIVATE` + `publicVersion`).
- **Abhängigkeiten:**
  - Nur die zwei Work-Bibliotheken oben, eingetragen in `gradle/libs.versions.toml`.
  - Danach `./gradlew :app:dependencies --write-locks` und `app/gradle.lockfile` mitcommitten. Trivy prüft die Locks.
  - Der Standard-Initializer von WorkManager wird im Manifest entfernt (`tools:node="remove"` für `androidx.work.WorkManagerInitializer` im `androidx.startup.InitializationProvider`). `BikeMaintainerApp` implementiert `Configuration.Provider` mit der eigenen Factory.
- **Texte (Schlüssel: de / en):**
  - `notif_channel_due`: „Fällige Wartungen“ / „Due maintenance“
  - `notif_channel_due_desc`: „Erinnerungen an fällige und überfällige Wartungen“ / „Reminders about due and overdue maintenance“
  - `notif_public_title`: „Wartung fällig“ / „Maintenance due“
  - `notif_task_overdue`: „%1$s überfällig“ / „%1$s overdue“
  - `notif_task_due_soon`: „%1$s bald fällig“ / „%1$s due soon“
  - `notif_relogin_title`: „Bitte erneut anmelden“ / „Please sign in again“
  - `notif_relogin_text`: „Bitte erneut anmelden, sonst keine Erinnerungen.“ / „Please sign in again, otherwise no reminders.“
  - `settings_notifications`: „Erinnerungen an fällige Wartungen“ / „Reminders for due maintenance“
  - `settings_notifications_blocked`: „Benachrichtigungen sind in den Systemeinstellungen ausgeschaltet.“ / „Notifications are turned off in the system settings.“
  - `settings_open_system`: „Systemeinstellungen öffnen“ / „Open system settings“
- **Benachrichtigung je Fahrzeug:**
  - Titel ist der Fahrzeugname. Der Text nennt die neu gemeldeten Aufgaben, überfällige zuerst, sonst in der Reihenfolge von `forDisplay()`, verbunden mit „, “. Beispiel: „Ölwechsel überfällig, Kette schmieren bald fällig“.
  - `BigTextStyle`, Kanal-ID `due_maintenance`, `notify(tag = "vehicle", id = vehicleId.toInt())`, `setAutoCancel(true)`, kleines Icon `R.drawable.ic_notification` (einfarbiger Schraubenschlüssel, Vektor).
  - Ein Tipp öffnet `MainActivity` mit Extra `EXTRA_VEHICLE_ID` (`FLAG_IMMUTABLE`, `requestCode = vehicleId.toInt()`). Die App zeigt dann das Fahrzeug-Detail über der Liste.
  - Die Erneut-anmelden-Benachrichtigung hat `tag = "relogin"`, `id = 0`, denselben Kanal und öffnet die App.
- **Zeitplan:**
  - `PeriodicWorkRequest` alle 24 h, `NetworkType.CONNECTED`, Backoff exponentiell (WorkManager-Standard).
  - `setInitialDelay` bis zum nächsten 07:00 Ortszeit. Vor 07:00 heißt das heute 07:00, ab 07:00 morgen 07:00.
  - Eindeutiger Name `due_tasks`, Richtlinie `ExistingPeriodicWorkPolicy.KEEP`.
- **Läuft nur, wenn** der Schalter an ist (Standard: an), Benachrichtigungen erlaubt sind (`NotificationManagerCompat.areNotificationsEnabled()`) und eine Anmeldung gespeichert ist. Das ist ein Token (Portal) oder ein Profil (Haushalt). `sync()` plant oder storniert entsprechend, der Worker prüft es zu Beginn noch einmal.
- **Gates:** Kover ≥ 90 % auf `data`/`domain` bleibt grün; Lint `warningsAsErrors`.
- **Commits:** Trailer `Co-Authored-By: opencode <noreply@opencode.ai>`, Ticket `(#12)`, Branch `feature/notifications`.

## Entscheidungen (vom Plan getroffen, nicht von der Spec)

1. **Was der Text nennt:** nur die neu gemeldeten Aufgaben, nicht alle fälligen des Fahrzeugs. So steht dort, warum die Benachrichtigung jetzt kommt.
2. **OVERDUE → DUE_SOON** (z. B. nach Korrektur in der Web-App) meldet nichts, speichert aber DUE_SOON. Ein späteres OVERDUE wird dann wieder gemeldet.
3. **Ergebnisse des Workers:**
   - Netzfehler, `Http(≥ 500)` und `Http(-1)` → `Result.retry()`.
   - `Unauthorized` → die einmalige Erneut-anmelden-Benachrichtigung, Job stornieren, `success`.
   - `NotConfigured`/`Storage` → Job stornieren, `success`.
   - Sonstige 4xx → `success`; nächster Lauf morgen.
   - Wird mitten im Laden ein Fehler gemeldet, ändert sich weder der gemeldete Stand noch erscheint eine Benachrichtigung.
4. **„Einmalig“ beim Erneut-anmelden:**
   - Das Flag `relogin_notified` in `SettingsStore` wird bei jeder neuen Anmeldung zurückgesetzt (`savePortalLogin`, `saveHouseholdProfile`).
   - Nach der Anmeldung plant `sync()` den Job wieder.
5. **Abmelden / Server wechseln:**
   - `clearSession()` und `clearAll()` löschen den gemeldeten Stand.
   - Die Einstellungen stornieren den Job und entfernen alle Benachrichtigungen der App (`cancelAll`). Nach dem Abmelden sollen keine Fahrzeugnamen mehr in der Leiste stehen.
6. **Schalter an ohne Erlaubnis** (Android 13+): Die App fragt nach der Erlaubnis.
   - Wird sie verweigert oder sind Benachrichtigungen systemweit aus, zeigt die Zeile `settings_notifications_blocked` mit dem Knopf `settings_open_system` (`Settings.ACTION_APP_NOTIFICATION_SETTINGS`).
   - Der Schalter selbst speichert den Wunsch des Nutzers.

## Review Focus

1. **Zweiter Lauf am selben Tag mit unverändertem Stand:** keine erneute Benachrichtigung. Test: `unchangedStateNotifiesNothing` (Task 1), `secondRunDoesNotNotifyAgain` (Task 3).
2. **Aufgabe erledigt und später wieder fällig:** wird wieder gemeldet. Test: `doneTaskIsReportedAgainLater` (Task 1).
3. **Fehler bei einem von mehreren Fahrzeugen:** keine Teil-Meldung, Stand unverändert, `retry`. Test: `failureMidwayKeepsStateAndRetries` (Task 3).
4. **Token ungültig an mehreren Tagen:** die Erneut-anmelden-Benachrichtigung kommt nur einmal. Test: `reloginNotifiedOnlyOnce` (Task 3).
5. **Tipp auf die Benachrichtigung bei laufender App oder nach dem Abmelden:** öffnet das Fahrzeug bzw. landet ohne Absturz bei der Anmeldung. Tests: `vehicleIntentOpensDetailOverList` und `vehicleIntentWhenSignedOutStaysOnLogin` (Task 4).

---

### Task 1: Meldelogik und gespeicherter Stand

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/domain/DueNotifications.kt`
- Modify: `data/storage/SettingsStore.kt`
- Test: `app/src/test/java/de/bikemaintainer/app/domain/DueNotificationsTest.kt` (neu), `data/storage/SettingsStoreTest.kt`

**Interfaces:**
- Produces:
  - `data class DueTask(val id: Long, val name: String, val status: TaskStatus)` mit `status` ∈ {OVERDUE, DUE_SOON}
  - `data class VehicleDue(val vehicleId: Long, val vehicleName: String, val tasks: List<DueTask>)`
  - `data class DueUpdate(val toNotify: List<VehicleDue>, val newState: Map<Long, TaskStatus>)`
  - `fun dueUpdates(previous: Map<Long, TaskStatus>, current: List<Pair<Vehicle, List<MaintenanceTask>>>): DueUpdate`
    - Berücksichtigt werden nur aktive Aufgaben mit OVERDUE/DUE_SOON. `newState` enthält genau diese.
    - Gemeldet wird eine Aufgabe, wenn sie in `previous` fehlt oder von DUE_SOON zu OVERDUE wechselt.
    - Je Fahrzeug mit mindestens einer zu meldenden Aufgabe gibt es ein `VehicleDue`. Seine Aufgaben sind OVERDUE vor DUE_SOON, dann in `forDisplay()`-Reihenfolge. Die Fahrzeugreihenfolge bleibt wie in `current`.
  - `SettingsStore`:
    - `val reportedDue: Flow<Map<Long, TaskStatus>>` und `suspend fun setReportedDue(map: Map<Long, TaskStatus>)`. Kodiert als String `"12:OVERDUE,15:DUE_SOON"`; unlesbare Einträge werden übersprungen.
    - `notificationsEnabled: Boolean = true` in `StoredSettings` plus `suspend fun setNotificationsEnabled(enabled: Boolean)`. Überlebt `clearSession`.
    - `reloginNotified: Boolean = false` in `StoredSettings` plus `suspend fun setReloginNotified(value: Boolean)`.
    - `clearSession()` entfernt zusätzlich `reported_due` und `relogin_notified`; `clearAll()` löscht ohnehin alles.
    - `savePortalLogin`/`saveHouseholdProfile` entfernen `relogin_notified`.

- [ ] **Step 1: Failing tests**
  - `DueNotificationsTest` (Fahrzeug A mit Aufgaben 1–4, Fahrzeug B mit 5):
    - `newlyDueIsReported`: previous `{}`, 1 OVERDUE, 2 DUE_SOON, 3 OK → `toNotify == [VehicleDue(A, [1 OVERDUE, 2 DUE_SOON])]`, `newState == {1:OVERDUE, 2:DUE_SOON}`
    - `dueSoonToOverdueIsReported`: previous `{2:DUE_SOON}`, now 2 OVERDUE → gemeldet
    - `unchangedStateNotifiesNothing`: previous gleich current → `toNotify` leer
    - `overdueToDueSoonIsNotReportedButStored`
    - `doneTaskIsReportedAgainLater`: Lauf 1 meldet 1; Lauf 2 mit 1 DONE → `newState` ohne 1; Lauf 3 mit 1 DUE_SOON → wieder gemeldet
    - `inactiveTasksAreIgnored`
    - `oneNotificationPerVehicle`: A und B neu fällig → zwei `VehicleDue` in Eingabereihenfolge
  - `SettingsStoreTest`:
    - `reportedDueRoundTrip`
    - `garbledReportedDueIsSkipped`: Rohwert `"x:Y,7:OVERDUE"` → `{7:OVERDUE}`
    - `clearSessionDropsReportedDueAndReloginFlagButKeepsEnabled`
    - `newLoginResetsReloginFlag`
    - `notificationsEnabledDefaultsToTrue`
- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest --tests '*DueNotificationsTest' --tests '*SettingsStoreTest'` · Expected: FAIL.
- [ ] **Step 3: Implementieren** wie oben.
- [ ] **Step 4: Run** `./gradlew --no-daemon testDebugUnitTest koverVerifyDebug lintDebug` · Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** – `feat: due notification logic and stored state (#12)`

---

### Task 2: Benachrichtigungen anzeigen (`DueTaskNotifier`)

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/notifications/DueTaskNotifier.kt`, `app/src/main/res/drawable/ic_notification.xml`
- Modify: `MainActivity.kt` (nur `const val EXTRA_VEHICLE_ID = "de.bikemaintainer.app.VEHICLE_ID"` im Companion), strings (alle `notif_*`)
- Test: `app/src/test/java/de/bikemaintainer/app/notifications/DueTaskNotifierTest.kt` (Robolectric)

**Interfaces:**
- Consumes: `VehicleDue`, `DueTask` (Task 1)
- Produces:
  - `interface DueNotifications { fun showDue(due: VehicleDue); fun showRelogin(); fun cancelAll() }`
  - `class DueTaskNotifier(context: Context) : DueNotifications`:
    - legt im `init` den Kanal an (`IMPORTANCE_DEFAULT`, Name/Beschreibung aus strings)
    - `showDue`/`showRelogin` wie in Global Constraints; vor `notify` wird `areNotificationsEnabled()` geprüft (keine SecurityException)
- Texte werden mit `context.getString` geholt, also in der App-Sprache.

- [ ] **Step 1: Failing tests** (Robolectric, `@Config(sdk = [34])`, Erlaubnis per `ShadowApplication.grantPermissions(POST_NOTIFICATIONS)`, de-Locale)
  - `channelIsCreated`: Kanal `due_maintenance` mit Name „Fällige Wartungen“
  - `oneNotificationPerVehicleWithTaskList`: `showDue(VehicleDue(3, "Africa Twin", [Ölwechsel OVERDUE, Kette schmieren DUE_SOON]))` → eine Benachrichtigung mit Tag „vehicle“ und ID 3, Titel „Africa Twin“, BigText „Ölwechsel überfällig, Kette schmieren bald fällig“
  - `lockScreenShowsOnlyGenericText`: `visibility == VISIBILITY_PRIVATE`, Titel der `publicVersion` „Wartung fällig“, und deren Text enthält weder „Africa“ noch „Ölwechsel“
  - `tapOpensVehicle`: `contentIntent` → Intent für `MainActivity` mit `EXTRA_VEHICLE_ID == 3`
  - `reloginNotification`: Tag „relogin“, Text „Bitte erneut anmelden, sonst keine Erinnerungen.“
  - `cancelAllRemovesEverything`
  - `noPermissionPostsNothing`: Erlaubnis entzogen → keine Benachrichtigung, keine Exception
- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest --tests '*DueTaskNotifierTest'` · Expected: FAIL.
- [ ] **Step 3: Implementieren** wie oben.
- [ ] **Step 4: Run** `./gradlew --no-daemon testDebugUnitTest koverVerifyDebug lintDebug` · Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** – `feat: due maintenance notifications (#12)`

---

### Task 3: Prüflauf, Worker und Zeitplan

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/notifications/{DueTaskCheck.kt, DueTasksWorker.kt, ReminderScheduler.kt}`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/gradle.lockfile`, `AndroidManifest.xml` (WorkManager-Initializer entfernen), `BikeMaintainerApp.kt` (`Configuration.Provider`), `AppContainer.kt`
- Test: `app/src/test/java/de/bikemaintainer/app/notifications/{DueTaskCheckTest, DueTasksWorkerTest, ReminderSchedulerTest}.kt`

**Interfaces:**
- Consumes: `VehicleRepository.vehicles()`, `MaintenanceRepository.tasks(id)`, `SettingsStore`, `dueUpdates`, `DueNotifications`
- Produces:
  - `enum class CheckResult { DONE, RETRY, STOP }`
  - `class DueTaskCheck(vehicles: VehicleRepository, maintenance: MaintenanceRepository, settings: SettingsStore, notifications: DueNotifications, notificationsAllowed: () -> Boolean)` mit `suspend fun run(): CheckResult`
    - Sind Schalter aus, Erlaubnis fehlend oder keine Anmeldung gespeichert → `STOP` ohne Netzzugriff.
    - Sonst die Fahrzeuge laden, dann die Aufgaben je Fahrzeug nacheinander. Beim ersten Fehler wird nach Entscheidung 3 abgebildet: `RETRY`, `STOP` (nach `showRelogin`, wenn `reloginNotified` falsch war, dann das Flag setzen) oder `DONE`.
    - Bei vollem Erfolg: `dueUpdates(previous, …)`, `showDue` je Eintrag, `setReportedDue(newState)`, `DONE`.
  - `class DueTasksWorker(context, params, check: DueTaskCheck, scheduler: ReminderScheduler) : CoroutineWorker`: `DONE` → `success`, `RETRY` → `retry`, `STOP` → `scheduler.cancel()` + `success`
  - `class DueTasksWorkerFactory(container: AppContainer) : WorkerFactory`
  - `interface ReminderScheduler { suspend fun sync(); fun cancel() }`
    - `class WorkManagerReminderScheduler(context, settings: SettingsStore, notificationsAllowed: () -> Boolean) : ReminderScheduler`
    - `sync()`: Schalter an und Erlaubnis und Anmeldung → `enqueueUniquePeriodicWork("due_tasks", KEEP, …)`, sonst `cancel()`
  - `fun delayUntilNextMorning(now: ZonedDateTime, hour: Int = 7): Duration`
  - `AppContainer`: `val notifier: DueNotifications`, `val reminderScheduler: ReminderScheduler`, `val dueTaskCheck: DueTaskCheck`
  - `BikeMaintainerApp`: `workManagerConfiguration` mit `DueTasksWorkerFactory`. In `onCreate` wird `reminderScheduler.sync()` in einem App-Scope gestartet (`ProcessLifecycleOwner` ist nicht nötig; ein `CoroutineScope(SupervisorJob() + Dispatchers.Default)` genügt).

- [ ] **Step 1: Failing tests**
  - `DueTaskCheckTest` (Fakes aus `ui/FakeVehicleRepository.kt`, `SettingsStore` auf Temp-DataStore wie in `SettingsStoreTest`, Fake `DueNotifications` mit Aufrufliste):
    - `newDueTasksAreNotifiedAndStored`
    - `secondRunDoesNotNotifyAgain`
    - `failureMidwayKeepsStateAndRetries`: Aufgaben von Fahrzeug 2 → `Failure(Network)`, also `RETRY`, keine `showDue`, `reportedDue` unverändert
    - `serverErrorRetries`: `Http(503)`
    - `unauthorizedShowsReloginAndStops`
    - `reloginNotifiedOnlyOnce`: zweimal Unauthorized → genau ein `showRelogin`
    - `notConfiguredStops`
    - `switchedOffDoesNotTouchNetwork`: Schalter aus → `STOP`, `vehiclesCalls == 0`
    - `withoutPermissionDoesNotTouchNetwork`
    - `clientErrorEndsQuietly`: `Http(400)` → `DONE`, keine Benachrichtigung
  - `DueTasksWorkerTest` (Robolectric, `TestListenableWorkerBuilder<DueTasksWorker>` mit einer Test-`WorkerFactory`): `retryMapsToRetry`, `stopCancelsScheduleAndSucceeds`, `doneSucceeds`
  - `ReminderSchedulerTest`:
    - `delayUntilNextMorning`: 06:30 → 30 min, 07:00 → 24 h, 22:00 → 9 h, auch über die Zeitumstellung (27.03.2027 22:00 Europe/Berlin → 8 h)
    - `syncEnqueuesWhenAllowed`: `WorkManagerTestInitHelper`, dann `getWorkInfosForUniqueWork("due_tasks")` → ENQUEUED mit Netz-Bedingung
    - `syncCancelsWhenSwitchedOff`
    - `syncCancelsWhenSignedOut`
- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest --tests '*notifications*'` · Expected: FAIL.
- [ ] **Step 3: Implementieren** wie oben. Danach `./gradlew :app:dependencies --write-locks`.
- [ ] **Step 4: Run** `./gradlew --no-daemon assembleDebug testDebugUnitTest koverVerifyDebug lintDebug` · Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** – `feat: daily due task check with WorkManager (#12)`

---

### Task 4: Einstellungen, Abmelden, Tipp öffnet Fahrzeug

**Files:**
- Modify: `ui/settings/{SettingsViewModel, SettingsScreen}.kt`, `ui/navigation/{AppNavHost, Routes?}.kt`, `MainActivity.kt`, `AndroidManifest.xml` (`android:launchMode="singleTop"` für `MainActivity`), strings (`settings_*`)
- Test: `ui/settings/SettingsViewModelTest.kt`, `ui/navigation/AppNavGraphTest.kt`, `ui/settings/SettingsScreenTest.kt` (neu, falls nicht vorhanden)

**Interfaces:**
- Consumes: `ReminderScheduler`, `DueNotifications.cancelAll()`, `SettingsStore.setNotificationsEnabled`, `MainActivity.EXTRA_VEHICLE_ID`
- Produces:
  - `SettingsViewModel(auth, reminders: ReminderScheduler, notifications: DueNotifications, notificationsAllowed: () -> Boolean)`:
    - `SettingsUiState` neu: `notificationsEnabled: Boolean`, `notificationsBlocked: Boolean` (Schalter an, aber `!notificationsAllowed()`)
    - `fun setNotificationsEnabled(enabled: Boolean)`: speichern, dann `reminders.sync()`
    - `fun refreshPermission()` beim Resume des Screens
    - Bei `logout()` und `switchServer()` zusätzlich `reminders.cancel()` und `notifications.cancelAll()`
  - `AppDependencies` neu: `reminders: ReminderScheduler`, `notifications: DueNotifications`, `notificationsAllowed: () -> Boolean`, `openVehicle: StateFlow<Long?>`, `onVehicleOpened: () -> Unit`
  - `MainActivity`: liest `EXTRA_VEHICLE_ID` in `onCreate` und `onNewIntent` in einen `MutableStateFlow<Long?>`.
  - `AppNavHost`:
    - Ist `openVehicle` gesetzt und die aktuelle Destination authentifiziert, dann `navigate(VehicleRoute(id)) { popUpTo<VehiclesRoute>() }` (Liste darunter) und `onVehicleOpened()`.
    - Ist sie nicht authentifiziert (Login/Setup/Profile), wird der Wert verworfen.
  - Nach jeder Anmeldung (`navigateAfterSignIn`) und nach `NotificationPermissionScreen.onDone` wird `deps.reminders.sync()` aufgerufen.
- UI Einstellungen:
  - neue Zeile mit `Switch` (`settings_notifications`, ganze Zeile `toggleable`, `Role.Switch`)
  - Ist sie an, aber blockiert: `settings_notifications_blocked` und ein `TextButton(settings_open_system)` (`Settings.ACTION_APP_NOTIFICATION_SETTINGS` mit `EXTRA_APP_PACKAGE`)
  - Beim Einschalten ohne Erlaubnis auf API 33+: `rememberLauncherForActivityResult(RequestPermission)` mit `POST_NOTIFICATIONS`, danach `refreshPermission()`

- [ ] **Step 1: Failing tests**
  - `SettingsViewModelTest` (Fake `ReminderScheduler`/`DueNotifications` mit Zählern):
    - `toggleSavesAndSyncs`
    - `blockedWhenEnabledButNotAllowed`
    - `logoutCancelsRemindersAndNotifications`
    - `switchServerCancelsRemindersAndNotifications`
  - `AppNavGraphTest`:
    - `vehicleIntentOpensDetailOverList`: Start `VehiclesRoute`, `openVehicle = 3` → Back-Stack `[Vehicles, Vehicle(3)]`, `onVehicleOpened` einmal
    - `vehicleIntentWhenSignedOutStaysOnLogin`: Start `LoginRoute` → bleibt auf Login, Wert verworfen
    - `signInSyncsReminders`
  - `SettingsScreenTest`: Schalterzeile ist ein Knoten mit `Role.Switch`; im blockierten Zustand sind Text und Knopf sichtbar.
- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest --tests '*ui.settings*' --tests '*ui.navigation*'` · Expected: FAIL.
- [ ] **Step 3: Implementieren** wie oben. `MainActivity` übergibt `container.reminderScheduler`, `container.notifier` und `{ NotificationManagerCompat.from(this).areNotificationsEnabled() }`.
- [ ] **Step 4: Run** `./gradlew --no-daemon assembleDebug testDebugUnitTest koverVerifyDebug lintDebug` · Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** – `feat: reminder switch, cancel on logout, open vehicle from notification (#12)`
- [ ] **Step 6: Emulator-Check** (Controller, AVD `Medium_Phone_API_33.0`, Dev-Stack):
  - Job geplant: `adb shell dumpsys jobscheduler | grep -A3 de.bikemaintainer`
  - Lauf erzwingen: `adb shell cmd jobscheduler run -f de.bikemaintainer.app <jobId>`. Danach erscheint je Fahrzeug mit fälligen Aufgaben eine Benachrichtigung mit dem richtigen Text.
  - Sperrbildschirm zeigt nur „Wartung fällig“.
  - Tipp öffnet das Detail. Ein zweiter erzwungener Lauf meldet nichts neu.
  - Schalter aus storniert den Job. Abmelden entfernt die Benachrichtigungen.
