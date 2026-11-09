# App v1 – Kilometerstand und Wartung erfassen (#11) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Im Fahrzeug-Detail lässt sich der Kilometerstand per Dialog eintragen und eine Wartung auf einem eigenen Bildschirm erfassen. Danach zeigt das Detail den neuen Stand.

**Architecture:**
- Die Schreib-Methoden gibt es seit #8: `VehicleRepository.updateMileage(vehicle, km)` (PUT mit unveränderten übrigen Feldern, Test `updateMileageKeepsOtherFields`) und `MaintenanceRepository.logMaintenance(…)`. Neu ist nur, dass eine 2xx-Antwort mit unlesbarem Body beim Log als gespeichert gilt.
- Der Kilometerstand-Dialog gehört zum Detail; sein Zustand liegt im `VehicleViewModel`.
- „Wartung erfassen“ ist ein eigenes Ziel `LogMaintenanceRoute(vehicleId)` im Paket `ui.logmaintenance` (wie in der Spec). Es hat ein eigenes ViewModel, das Fahrzeug und Aufgaben selbst lädt.
- Nach dem Speichern geht es zurück zum Detail. Dessen `onResume` lädt still neu (#10). Eine Snackbar meldet das Ergebnis; es kommt über den `SavedStateHandle` des Detail-Eintrags.

**Tech Stack:** wie #10: Compose + Material 3 (BOM 2026.09.00, `AlertDialog`, `DatePickerDialog`, `Checkbox`, `SnackbarHost`), Navigation Compose 2.10.2, Robolectric 4.17. Keine neuen Abhängigkeiten.

**Spec:** `docs/specs/2026-11-06-app-v1-design.md` (Abschnitte „Fahrzeug-Detail“ – die Knöpfe „Kilometerstand eintragen“ und „Wartung erfassen“, „Fehler im Betrieb“, „Aufbau“, „Tests“) · Ticket stargazer74/bike-maintainer-app#11 · baut auf #10 (`VehicleViewModel`, `ReloadableLoader`, `AppDependencies`, `AppNavGraphTest`, `FakeVehicleRepository`/`FakeMaintenanceRepository`).

## Global Constraints

- **Privacy first:** keine neuen Abhängigkeiten, keine Dritt-Anfragen. Notizen, Aufgabennamen und Kilometerstände kommen nicht in Logs.
- **Texte:** nur aus `values/strings.xml` (en) und `values-de/strings.xml` (de) mit gleichen Schlüsseln. Zahlen werden wie in #10 mit `mileageText`/`NumberFormat.getIntegerInstance(locale)` formatiert, Datumsangaben mittellang nach App-Sprache (wie „Erledigt am …“).
- **Neue Texte (Schlüssel: de / en):**
  - `save`: „Speichern“ / „Save“
  - `mileage_enter`: „Kilometerstand eintragen“ / „Enter mileage“ (Knopf und Dialogtitel)
  - `mileage_label`: „Kilometerstand (km)“ / „Mileage (km)“
  - `error_mileage_invalid`: „Bitte eine ganze Zahl eingeben.“ / „Please enter a whole number.“
  - `error_mileage_too_low`: „Darf nicht kleiner sein als bisher (%1$s km).“ / „Must not be lower than before (%1$s km).“
  - `log_title`: „Wartung erfassen“ / „Log maintenance“ (Knopf und Titel)
  - `log_date`: „Datum“ / „Date“
  - `log_tasks`: „Durchgeführte Aufgaben“ / „Tasks performed“
  - `log_notes`: „Notiz (optional)“ / „Note (optional)“
  - `log_notes_counter`: „%1$d / 1000“ / „%1$d / 1000“
  - `log_costs_hint`: „Material und Kosten in der Web-App ergänzen.“ / „Add materials and costs in the web app.“
  - `log_raise_mileage`: „Kilometerstand des Fahrzeugs auf %1$s km anheben“ / „Raise the vehicle's mileage to %1$s km“
  - `error_no_task_selected`: „Bitte mindestens eine Aufgabe auswählen.“ / „Please select at least one task.“
  - `log_saved`: „Wartung gespeichert.“ / „Maintenance logged.“
  - `log_saved_mileage_failed`: „Wartung gespeichert, aber der Kilometerstand konnte nicht angehoben werden.“ / „Maintenance logged, but the mileage could not be raised.“
  - `error_input_rejected`: „Der Server hat die Eingaben abgelehnt.“ / „The server rejected the input.“
  - `error_save_failed`: „Konnte nicht gespeichert werden.“ / „Could not be saved.“
- **Speicherfehler** (`AppError.saveMessageRes()`):
  - `Network` → `error_no_connection`
  - `Http(≥ 500)` → `error_server_not_responding`
  - `CleartextNotAllowed` → `error_cleartext_not_allowed`
  - `NotFound` → `error_vehicle_not_found`
  - `Http(400)` → `error_input_rejected`
  - alles andere (auch `Http(-1)` beim PUT) → `error_save_failed`
  - Ein `Unauthorized` erscheint zwar als Text, die Umleitung zur Anmeldung macht aber die Session-Logik aus #9. `LogMaintenanceRoute` muss deshalb in `isAuthenticated()` (`SessionRedirect.kt`) stehen.
- **Kilometerstand-Eingabe:**
  - Erlaubt sind nur Ziffern, nach dem Entfernen von Leerzeichen am Anfang und Ende, und nur Werte von 0 bis 9 999 999.
  - „24.600“ und „24,600“ sind ungültig. So wird nichts falsch gelesen, z. B. „24,6“ als 246.
  - Tastatur `KeyboardType.Number`. Vorbelegt wird der aktuelle Stand als Ziffern ohne Tausenderpunkt.
- **Bedienung:**
  - Touch-Ziele ≥ 48 dp. Fehler erscheinen über `ErrorText` (Live-Region), Laden und Speichern über `LoadingIndicator`.
  - Während des Speicherns ist „Speichern“ deaktiviert, ein zweiter Aufruf wird ignoriert.
  - Jede Aufgabenzeile ist als Ganzes umschaltbar (`Modifier.toggleable`, `Role.Checkbox`) und wird mit Name und Statustext vorgelesen.
- **Gates:** Kover ≥ 90 % auf `data`/`domain` bleibt grün; Lint `warningsAsErrors`.
- **Commits:** Trailer `Co-Authored-By: opencode <noreply@opencode.ai>`, Ticket `(#11)`, Branch `feature/maintenance-entry`.

## Entscheidungen (vom Plan getroffen, nicht von der Spec)

1. **Mindestens eine Aufgabe.** Eine Wartung ohne Aufgabe ist in der App nicht speicherbar. Die Web-App erlaubt das, kann dort aber neue Aufgaben, Material und Kosten anhängen; die App nicht.
2. **Gleicher Kilometerstand im Dialog** schließt ihn ohne Anfrage.
3. **Kilometerstand anheben:** Liegt der erfasste Stand über dem des Fahrzeugs, erscheint eine Checkbox, vorab angehakt. Nach erfolgreichem Log folgt `updateMileage`. Scheitert nur dieses, ist das Log trotzdem gespeichert: Es geht zurück zum Detail mit `log_saved_mileage_failed`. Kein erneutes Senden des Logs, sonst entstünde ein Duplikat.
4. **Datum:** Der `DatePickerDialog` erlaubt nur heute und frühere Tage. Ein Kilometerstand unter dem des Fahrzeugs ist erlaubt (nachgetragene Wartung).
5. **Unlesbare 2xx beim Log** (`Http(-1)`) gilt als gespeichert. Das POST ist auf dem Server angekommen; ein „Erneut versuchen“ würde ein zweites Log anlegen.

## Review Focus

1. **Doppeltipp auf „Speichern“:** genau ein `logMaintenance`-Aufruf. Test: `saveTwiceSendsOnce` (Task 3).
2. **Server legt das Log an, Antwort unlesbar:** gilt als gespeichert, kein Duplikat. Test: `unreadableCreatedResponseCountsAsSaved` (Task 1).
3. **„24.600“ im Kilometerfeld:** Fehler „ganze Zahl“, nicht 24 oder 24600. Test: `groupedNumberIsRejected` (Task 1).
4. **Log gespeichert, Anheben scheitert:** zurück mit `log_saved_mileage_failed`, Log nur einmal gesendet. Test: `raiseFailureStillFinishesAsSaved` (Task 3).
5. **Detail nach dem Speichern:** zeigt den neuen Stand ohne manuelles Ziehen. Tests: `mileageSavedReloadsDetail` (Task 2), `loggingReturnsToDetailAndReloads` (Task 4).

---

### Task 1: Eingabeprüfung, Speicherfehler, Log-Antwort

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/domain/MileageInput.kt`
- Modify: `data/repository/MaintenanceRepository.kt`, `ui/common/LoadErrors.kt`, `res/values{,-de}/strings.xml` (nur `error_input_rejected`, `error_save_failed`; jeder Task legt die Texte an, die er nutzt – Lint `warningsAsErrors` würde ungenutzte Strings (`UnusedResources`) ablehnen)
- Test: `app/src/test/java/de/bikemaintainer/app/domain/MileageInputTest.kt` (neu), `MaintenanceRepositoryTest.kt`, `ui/common/LoadErrorsTest.kt`

**Interfaces:**
- Produces:
  - `const val MAX_MILEAGE = 9_999_999`
  - `fun parseMileage(text: String): Int?`: `null` bei leer, Nicht-Ziffern oder > `MAX_MILEAGE`
  - `@StringRes fun AppError.saveMessageRes(): Int`, Zuordnung wie in Global Constraints
  - `ApiMaintenanceRepository.logMaintenance` liefert bei `Http(-1)` `Outcome.Success(Unit)`; alle anderen Fehler unverändert.

- [ ] **Step 1: Failing tests**
  - `MileageInputTest`:
    - `parseMileage("24600") == 24600`
    - `parseMileage(" 0 ") == 0`
    - `groupedNumberIsRejected`: `parseMileage("24.600")`, `"24,600"` und `"24 600"` sind `null`
    - `""`, `"-5"`, `"1e3"` und `"10000000"` sind `null`
    - `parseMileage("9999999") == 9_999_999`
  - `MaintenanceRepositoryTest.unreadableCreatedResponseCountsAsSaved`: MockWebServer antwortet 201 mit Body `"{"`, das Ergebnis ist `Outcome.Success(Unit)`, genau eine Anfrage.
  - `logMaintenanceServerErrorStaysFailure`: 500 ergibt `Failure(Http(500, null))`.
  - `LoadErrorsTest`: je Zeile der Zuordnung ein Assert, dabei `Http(400, null)` → `error_input_rejected` und `Http(-1, null)` → `error_save_failed`.
- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest --tests '*MileageInputTest' --tests '*MaintenanceRepositoryTest' --tests '*LoadErrorsTest'` · Expected: FAIL.
- [ ] **Step 3: Implementieren.** `parseMileage` per `trim()` und `all(Char::isDigit)`. `toIntOrNull` erst nach der Längenprüfung (≤ 7 Stellen), damit kein Überlauf entsteht.
- [ ] **Step 4: Run** `./gradlew --no-daemon testDebugUnitTest koverVerifyDebug lintDebug` · Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** – `feat: mileage input check and save errors (#11)`

---

### Task 2: Kilometerstand eintragen (Dialog im Detail)

**Files:**
- Modify: `ui/vehicle/VehicleViewModel.kt`, `ui/vehicle/VehicleScreen.kt`, strings (`save`, `mileage_enter`, `mileage_label`, `error_mileage_invalid`, `error_mileage_too_low`, `log_title`)
- Test: `ui/vehicle/VehicleViewModelTest.kt`, `ui/vehicle/VehicleScreenTest.kt`

**Interfaces:**
- Consumes: `parseMileage`, `saveMessageRes`, `VehicleRepository.updateMileage(vehicle, km)`
- Produces:
  - `data class MileageDialogState(val input: String, val error: MileageError? = null, val saving: Boolean = false)`
  - `sealed interface MileageError { data object Invalid; data class TooLow(val current: Int); data class Save(val error: AppError) }`
  - `VehicleUiState` neu: `val mileageDialog: MileageDialogState? = null`
  - `VehicleViewModel`:
    - `openMileageDialog()`: nur mit geladenem Fahrzeug; vorbelegt mit `currentMileage.toString()`
    - `onMileageInput(text: String)`: setzt `input`, löscht `error`
    - `saveMileage()`
    - `dismissMileageDialog()`: während `saving` ohne Wirkung
  - `VehicleContent` erhält einen Parameter `actions: VehicleActions`. Das ist eine `data class` mit Lambdas: `onOpenMileage`, `onMileageInput`, `onSaveMileage`, `onDismissMileage`, `onLogMaintenance`. So bleibt die Signatur überschaubar. `onLogMaintenance` ist in diesem Task nur durchgereicht.
- `saveMileage()`:
  - ungültig → `Invalid`
  - kleiner als `vehicle.currentMileage` → `TooLow(current)`
  - gleich → Dialog schließen, keine Anfrage
  - sonst `saving = true` und `updateMileage(lastLoadedVehicle, km)`. Bei Erfolg Dialog schließen und `loader.refresh()`, bei Fehler `Save(error)` mit Dialog offen.
- UI:
  - Unter dem Kopf eine Zeile mit `OutlinedButton(mileage_enter)` und, wenn `tasks` nicht leer, `Button(log_title)`.
  - Der Dialog ist ein `AlertDialog` mit `OutlinedTextField` (Label `mileage_label`, `KeyboardType.Number`, `ImeAction.Done` → speichern), den Fehlertexten `error_mileage_invalid`, `error_mileage_too_low` (mit `mileageText`) bzw. `saveMessageRes()` und den Knöpfen `save`/`cancel`. Während des Speicherns zeigt „Speichern“ den kleinen `LoadingIndicator` und ist deaktiviert.

- [ ] **Step 1: Failing tests** (ViewModel mit Fakes und `MainDispatcherRule`; Screen mit Robolectric, `createComposeRule`, de-Locale wie bestehende Tests)
  - `openPrefillsCurrentMileage`: Fahrzeug 24500 → `mileageDialog.input == "24500"`
  - `lowerMileageIsRejected`: Eingabe `"24000"` → `error == TooLow(24500)`, kein `updateMileageCalls`
  - `invalidMileageIsRejected`: `"24.600"` → `Invalid`
  - `sameMileageClosesWithoutRequest`
  - `mileageSavedReloadsDetail`: `updateMileageResult` Erfolg → `updateMileageCalls == [vehicle to 24600]` mit dem zuletzt geladenen `Vehicle`, Dialog `null`, `vehicleCalls.size == 2`
  - `saveFailureKeepsDialogWithError`: `Failure(Network)` → `error == Save(Network)`, Eingabe bleibt
  - `saveTwiceSendsOnce`: Gate offen halten, `saveMileage()` zweimal → ein Aufruf
  - Screen `enterMileageDialogSavesAndShowsTooLow`: Knopf „Kilometerstand eintragen“ → Textfeld leeren, „24000“ eingeben, „Speichern“ → Text „Darf nicht kleiner sein als bisher (24.500 km).“ sichtbar
  - Screen `logButtonHiddenWithoutTasks`
- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest --tests '*ui.vehicle.*'` · Expected: FAIL.
- [ ] **Step 3: Implementieren** wie oben. In `AppNavHost` bekommt `VehicleScreen` vorerst `onLogMaintenance = {}`; Task 4 verdrahtet es.
- [ ] **Step 4: Run** `./gradlew --no-daemon testDebugUnitTest koverVerifyDebug lintDebug` · Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** – `feat: enter mileage from vehicle detail (#11)`

---

### Task 3: Wartung erfassen (Bildschirm)

**Files:**
- Create: `app/src/main/java/de/bikemaintainer/app/ui/logmaintenance/LogMaintenanceViewModel.kt`, `LogMaintenanceScreen.kt`
- Modify: strings (`log_date`, `log_tasks`, `log_notes`, `log_notes_counter`, `log_costs_hint`, `log_raise_mileage`, `error_no_task_selected`)
- Test: `app/src/test/java/de/bikemaintainer/app/ui/logmaintenance/LogMaintenanceViewModelTest.kt`, `LogMaintenanceScreenTest.kt`

**Interfaces:**
- Consumes: `VehicleRepository.vehicle`, `MaintenanceRepository.tasks`/`logMaintenance`, `VehicleRepository.updateMileage`, `forDisplay()`, `parseMileage`, `saveMessageRes`, `vehicleLoadMessageRes`, `TaskLabel`/Statustext aus `ui.vehicle`
- Produces:
  - `enum class LogResult { SAVED, SAVED_MILEAGE_FAILED }`
  - `data class LogMaintenanceUiState(val vehicle: Vehicle? = null, val tasks: List<MaintenanceTask> = emptyList(), val loadError: AppError? = null, val date: LocalDate, val mileageInput: String = "", val selected: Set<Long> = emptySet(), val notes: String = "", val raiseMileage: Boolean = true, val saving: Boolean = false, val error: LogError? = null, val done: LogResult? = null)` plus `val offerRaise: Boolean`. `offerRaise` ist wahr, wenn ein gültiger Kilometerstand über `vehicle.currentMileage` liegt.
  - `sealed interface LogError { data object InvalidMileage; data object NoTask; data class Save(val error: AppError) }`
  - `class LogMaintenanceViewModel(vehicleId: Long, vehicles: VehicleRepository, maintenance: MaintenanceRepository, today: () -> LocalDate = LocalDate::now)` mit:
    - `retryLoad()`, `onDate(LocalDate)`, `onMileageInput(String)`, `toggleTask(Long)`
    - `onNotes(String)`: schneidet auf 1000 Zeichen
    - `onRaiseMileage(Boolean)`, `save()`
  - `@Composable fun LogMaintenanceScreen(viewModel, onBack: () -> Unit, onDone: (LogResult) -> Unit)` und ein zustandsloses `LogMaintenanceContent` für Tests. `onDone` wird einmal aufgerufen, wenn `done` gesetzt wird (`LaunchedEffect(state.done)`).
- Verhalten:
  - Beim Laden ist das Fahrzeug parallel zu den Aufgaben, wie im Detail. Danach gilt: `tasks = forDisplay()` (nur aktive), `selected` = IDs mit `OVERDUE`/`DUE_SOON`, `mileageInput = currentMileage.toString()`, `date = today()`.
  - Ladefehler: `ErrorText(vehicleLoadMessageRes())` mit „Erneut versuchen“ bzw. bei `NotFound` „Zurück“.
  - `save()`:
    - ignoriert, solange `saving`
    - ungültiger Kilometerstand → `InvalidMileage`; leere Auswahl → `NoTask`
    - sonst `logMaintenance(vehicleId, date, km, selected, notes.ifBlank { null })`. Schlägt das fehl → `Save(error)`, die Eingaben bleiben.
    - Bei Erfolg und `offerRaise && raiseMileage` folgt `updateMileage(vehicle, km)`: Erfolg → `done = SAVED`, Fehler → `done = SAVED_MILEAGE_FAILED`. Ohne Anheben → `done = SAVED`.
- UI (`Scaffold` mit Zurück-Pfeil, Titel `log_title`, scrollbare Spalte):
  - Datum als `OutlinedButton` mit formatiertem Datum. Er öffnet den `DatePickerDialog`; `selectableDates` erlaubt nur Tage ≤ `today()`, also UTC-Millis von `today()` am Tagesende.
  - Kilometerstand-Feld (`mileage_label`, `KeyboardType.Number`).
  - Wenn `offerRaise`: Checkbox-Zeile `log_raise_mileage`.
  - Überschrift `log_tasks`, darunter je Aufgabe eine umschaltbare Zeile mit Checkbox, Name und Statustext.
  - Notizfeld (`log_notes`, mehrzeilig) mit Zähler `log_notes_counter`.
  - Hinweis `log_costs_hint` (`bodySmall`, `onSurfaceVariant`).
  - Fehlertext und `Button(save)` volle Breite.

- [ ] **Step 1: Failing tests**
  - ViewModel:
    - `prefillsTodayMileageAndDueTasks`: Aufgaben OVERDUE(1), DUE_SOON(2), OK(3), inaktiv(4) → `selected == {1,2}`, `tasks.map{it.id}` ohne 4, `mileageInput == "24500"`, `date == today`
    - `notesAreCappedAt1000`
    - `noTaskSelectedIsRejected`: alle abwählen → `NoTask`, kein `logCalls`
    - `invalidMileageIsRejected`: `"abc"` → `InvalidMileage`
    - `savesLogWithSelectionAndTrimmedNotes`: `logCalls == [LogCall(3, today, 24500, {1,2}, null)]` bei Notiz `"  "`, `done == SAVED`, kein `updateMileageCalls`
    - `higherMileageRaisesVehicle`: Eingabe `"24800"` → `offerRaise`, nach `save()` `updateMileageCalls == [vehicle to 24800]`, `done == SAVED`
    - `raiseUncheckedDoesNotUpdate`
    - `lowerMileageIsAllowedWithoutRaise`: `"20000"` → `offerRaise == false`, Log mit 20000
    - `raiseFailureStillFinishesAsSaved`: `updateMileageResult = Failure(Network)` → `done == SAVED_MILEAGE_FAILED`, `logCalls.size == 1`
    - `saveFailureKeepsInput`: `logResult = Failure(Http(400, null))` → `error == Save(Http(400,null))`, `done == null`, Eingaben unverändert
    - `saveTwiceSendsOnce`: `logGate` offen halten → ein Aufruf
    - `deletedVehicleShowsNotFound`
  - Screen (Compose-UI-Akzeptanztest der Spec):
    - `logMaintenanceEndToEnd`: Fakes mit zwei fälligen Aufgaben → beide Checkboxen an, eine abwählen, Notiz eingeben, Hinweis „Material und Kosten in der Web-App ergänzen.“ sichtbar, „Speichern“ → `logCalls` mit einer ID und der Notiz, `onDone(SAVED)` einmal aufgerufen
    - `noTaskShowsError`: alle abwählen, „Speichern“ → „Bitte mindestens eine Aufgabe auswählen.“
    - `raiseCheckboxAppearsForHigherMileage`
    - `taskRowIsOneToggleableNode`: Zeile mit Name und Status als ein Knoten mit `ToggleableState`
- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest --tests '*ui.logmaintenance*'` · Expected: FAIL.
- [ ] **Step 3: Implementieren** wie oben.
- [ ] **Step 4: Run** `./gradlew --no-daemon testDebugUnitTest koverVerifyDebug lintDebug` · Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** – `feat: log maintenance screen (#11)`

---

### Task 4: Navigation, Ergebnis-Meldung, Ende-zu-Ende

**Files:**
- Modify: `ui/navigation/Routes.kt`, `ui/navigation/AppNavHost.kt`, `ui/navigation/SessionRedirect.kt`, `ui/vehicle/VehicleScreen.kt`, strings (`log_saved`, `log_saved_mileage_failed`)
- Test: `ui/navigation/AppNavGraphTest.kt`, `ui/navigation/SessionNavigationTest.kt` (oder wo `loginRequiredOnVehicleDetailRedirects` steht)

**Interfaces:**
- Consumes: `LogMaintenanceScreen`, `LogResult`, `VehicleActions.onLogMaintenance`
- Produces:
  - `@Serializable data class LogMaintenanceRoute(val vehicleId: Long)`, in `isAuthenticated()` aufgenommen
  - `const val LOG_RESULT_KEY = "log_result"`
  - `VehicleScreen(viewModel, onBack, onLogMaintenance: () -> Unit, logResult: StateFlow<String?>, onLogResultShown: () -> Unit)`: zeigt `log_saved` bzw. `log_saved_mileage_failed` einmal per `SnackbarHost` und ruft dann `onLogResultShown()`
- Verdrahtung in `AppNavHost`:
  - `VehicleRoute`: `onLogMaintenance = { navController.navigate(LogMaintenanceRoute(route.vehicleId)) }`. `logResult = entry.savedStateHandle.getStateFlow<String?>(LOG_RESULT_KEY, null)`, `onLogResultShown = { entry.savedStateHandle[LOG_RESULT_KEY] = null }`.
  - `LogMaintenanceRoute`: ViewModel per `viewModelFactory`. `onBack = navController.popBackOnce()`. `onDone = { r -> navController.previousBackStackEntry?.savedStateHandle?.set(LOG_RESULT_KEY, r.name); navController.popBackStack() }`.
  - Das Detail lädt beim Zurückkommen über sein `onResume` neu (#10); kein zusätzlicher Reload-Weg.

- [ ] **Step 1: Failing tests** (echter Graph wie in #10)
  - `loggingReturnsToDetailAndReloads`: Liste → Fahrzeug → „Wartung erfassen“ → „Speichern“ → wieder Detail, Text „Wartung gespeichert.“ sichtbar, `vehicleCalls.size` gestiegen
  - `raiseFailureShowsSavedMileageFailed`
  - `backFromLogReturnsToDetailWithoutSaving`: Zurück-Pfeil → Detail, `logCalls` leer, keine Snackbar
  - `loginRequiredOnLogMaintenanceRedirects`: Sitzungsstatus wechselt auf „Anmeldung nötig“, während `LogMaintenanceRoute` offen ist → Login
- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest --tests '*ui.navigation*'` · Expected: FAIL.
- [ ] **Step 3: Implementieren** wie oben.
- [ ] **Step 4: Run** `./gradlew --no-daemon assembleDebug testDebugUnitTest koverVerifyDebug lintDebug` · Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** – `feat: wire log maintenance into navigation (#11)`
- [ ] **Step 6: Emulator-Check** (Controller, AVD `Medium_Phone_API_33.0`, Dev-Stack `http://10.0.2.2:8100`):
  - Kilometerstand eintragen: kleiner abgelehnt, größer gespeichert, in der Web-App sichtbar
  - Wartung erfassen mit Vorauswahl, Datum ändern, Notiz, Anheben → Detail zeigt neuen Stand und Status; Log in der Web-App sichtbar
  - de/en, hell/dunkel
  - Logcat `BikeMaintainerHttp` zeigt `PUT /vehicles/{id}` und `POST /maintenance-logs` ohne Inhalte
