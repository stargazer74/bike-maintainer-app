# Android-App Version 1 – Design

Stand: 2026-11-06 · Epic stargazer74/bike-maintainer-backend#104, Teilprojekt B (Teilprojekt A: Geräte-Anmeldung,
Spec im Backend `docs/specs/2026-11-06-device-login-design.md`, umgesetzt mit API-Spec 2.10.0) · Tickets: #8–#12

## Ziel

Eine native Android-App für Bike Maintainer für unterwegs und die Werkstatt: Fälligkeiten sehen, Kilometerstand
eintragen, Wartungen erfassen und an fällige Wartungen erinnert werden. Sie verbindet sich mit dem Portal
(`https://app.bike-maintainer.de`) oder mit einem Haushalts-Server im Heimnetz.

**Erfolg:**

- Nach einmaliger Einrichtung öffnet die App ohne erneute Anmeldung (Portal: Geräte-Token, Haushalt: gespeichertes
  Profil).
- Kilometerstand und Wartung lassen sich in wenigen Tipps erfassen; das Ergebnis ist sofort in der Web-App sichtbar.
- Wird eine Wartung fällig, kommt einmal eine Benachrichtigung – ohne Google-Push und ohne Tracking.
- Die App läuft auf Deutsch und Englisch, im Werkstatt-Design hell und dunkel.

## Abgrenzung

- **Version 1 enthält:** Server einrichten, Anmelden (Portal) bzw. Profil wählen (Haushalt), Abmelden, Fahrzeugliste
  mit Fälligkeitsstatus, Fahrzeug-Detail mit Wartungsaufgaben, Kilometerstand eintragen, Wartung erfassen (Aufgaben,
  Datum, Kilometerstand, Notiz), Benachrichtigungen, Einstellungen, de/en, hell/dunkel.
- **Nicht in Version 1 (Web-App bzw. später):** Fahrzeuge und Aufgaben anlegen, bearbeiten, löschen; Material und
  Kosten; Kostenübersicht; PDF-Bericht und Checkliste; Passwort vergessen, Konto löschen (Links in
  die Web-App); Offline-Nutzung (siehe „Später“).
- **Verteilung:** zunächst nur für den Betreiber (Android Studio bzw. Debug-APK). Die App wird so gebaut, dass F-Droid
  und Google Play später ohne Umbau möglich sind; Signatur, Store-Einträge und Release-Build folgen eigenen Tickets.

## Rahmenbedingungen

- **Privacy first:** keine Google-Play-Dienste, keine Tracker, keine Analyse, kein Absturzbericht an Dritte; nur
  freie Abhängigkeiten (F-Droid-tauglich): AndroidX, Jetpack Compose, OkHttp, Retrofit, kotlinx.serialization.
- **Plattform:** Kotlin, Jetpack Compose, `minSdk 29`, `targetSdk`/`compileSdk 37`, Package `de.bikemaintainer.app`
  (Grundgerüst aus stargazer74/bike-maintainer-app#3, CI aus #5).
- **Contract-first:** Der API-Client wird beim Build aus `maintenance-api.yaml` generiert (OpenAPI Generator,
  Kotlin, Retrofit + OkHttp + kotlinx.serialization) – lokal aus `../bike-maintainer-api`, in der CI aus dem
  gleichnamigen api-Branch (sonst `main`). Generierter Code wird nicht eingecheckt.
- **API:** mindestens Spec 2.10.0 (Geräte-Anmeldung). Die App nutzt `config`, `auth/device-login`,
  `auth/device-session`, `auth/logout`, `auth/me`, `profiles` (+ `select`), `vehicles` (list, get, put),
  `vehicles/{id}/maintenance-tasks` (list) und `vehicles/{id}/maintenance-logs` (create). Den Fälligkeitsstatus
  (`OVERDUE`, `DUE_SOON`, `OK`, `DONE`) berechnet der Server.

## Bildschirme und Abläufe

### Einrichtung (erster Start)

Geändert 2026-11-12 (app#42, app#45, Epic stargazer74/bike-maintainer-backend#136): neuer Einstieg mit Auswahl,
Registrierung in der App, Verbinden per QR-Code.

1. **Auswahl:** „Online-Konto“ (Portal, Adresse `https://app.bike-maintainer.de` fest eingebaut, kein Eingabefeld)
   oder „Eigener Server“. Neutrale Texte, kein Preisvergleich (Play-Zahlungsrichtlinie).
   **Eigener Server:** Adresse eingeben oder „QR-Code scannen“ (Kamera-Berechtigung erst dann; zxing-cpp, offline;
   akzeptiert nur `http(s)://`-Adressen, Query/Fragment fallen weg). Beide Wege prüfen per `GET /api/v1/config`, ob es
   ein Bike-Maintainer-Server ist und in welchem Modus; keine Antwort oder kein Bike-Maintainer-Server → verständliche
   Meldung. Bei `http://` einmaliger Hinweis: „Die Verbindung ist unverschlüsselt. Das ist nur im eigenen Heimnetz
   vertretbar.“ (Regel siehe „Netz und Sicherheit“). Kein automatisches Suchen im Netz.
2. **Portal:** E-Mail, Passwort und Gerätename (vorbelegt mit dem Handymodell, änderbar) → `device-login`. Link
   „Passwort vergessen“ öffnet die Web-App im Browser. Fehler wie in der Web-App: falsche Daten, E-Mail nicht bestätigt,
   Konto deaktiviert, zu viele Versuche.
   **Konto erstellen:** E-Mail, Passwort (zweimal; 10–72 Zeichen, höchstens 72 Byte) → `register` mit der Sprache der
   App. Den ALTCHA-Proof-of-Work (`GET /auth/challenge`, Protokoll v2, PBKDF2) löst die App nativ in Kotlin
   (`AltchaSolver`, ohne WebView, ohne Drittanbieter); die Challenge geht unverändert in den Payload, weil die Signatur
   sie abdeckt. Danach „Bitte bestätige deine E-Mail-Adresse“ mit „Erneut senden“ (`verify-email/resend`, ebenfalls
   mit gelöster Challenge); der Link in der Mail öffnet die Web-App. Die Antwort ist für neue und bekannte Adressen
   gleich (keine Konto-Enumeration). Link zur Datenschutzerklärung der Website, falls der Server eine nennt.
   Ein Abo wird nur im Web abgeschlossen; die App erwähnt weder Abo noch Preise noch Kauf.
   **Haushalt:** Profilliste (`GET /profiles`), ein Tipp wählt das Profil (`POST /profiles/{id}/select`); die App
   merkt sich das Profil. „Neue Person“ (bzw. „Erste Person anlegen“, solange es keine gibt) legt ein Profil an
   (`POST /profiles`, 409 → „Diesen Namen gibt es schon“) und wählt es gleich aus.
   **Server wechseln** (Einstellungen) bzw. „Anderer Server“ führt zurück zur Auswahl.
3. Danach einmalig die Frage nach der Erlaubnis für Benachrichtigungen (Android 13+); später in den Einstellungen
   änderbar.

### Fahrzeugliste (Startbildschirm)

- Karte je Fahrzeug: Name, Kilometerstand, dringendster Status der aktiven Aufgaben (überfällig > bald fällig > ok)
  und Anzahl fälliger Aufgaben.
- Nach unten ziehen lädt neu. Leere Liste: Hinweis, dass Fahrzeuge in der Web-App angelegt werden, mit Link.

### Fahrzeug-Detail

- Kopf mit Name und Kilometerstand; Knopf **„Kilometerstand eintragen“**: Dialog mit Zahl, nicht kleiner als bisher;
  Speichern per `PUT /vehicles/{id}` mit den übrigen Feldern unverändert aus dem zuletzt geladenen Stand.
- Wartungsaufgaben mit Status, Rest-km bzw. Resttagen und Fortschrittsbalken; fällige zuerst.
- Knopf **„Wartung erfassen“**: Datum (heute) und Kilometerstand (aktueller) vorbelegt; fällige Aufgaben
  vorausgewählt, alle aktiven auswählbar; optionale Notiz (bis 1000 Zeichen); `POST /maintenance-logs` mit
  `performedTaskIds`. Hinweis „Material und Kosten in der Web-App ergänzen“. Liegt der erfasste Kilometerstand über
  dem des Fahrzeugs, bietet die App an, ihn mit anzuheben (das Backend tut das nicht von selbst).

### Einstellungen

Benachrichtigungen an/aus; Sprache (System, Deutsch, Englisch – Android „Sprache pro App“); verbundener Server und
Konto bzw. Profil; **Abmelden**; **Server wechseln**; Links zu Datenschutz und Impressum, wenn `config` eine
`websiteUrl` meldet.

**Konto löschen** (nur Portal, Vorgabe von Google Play, #48): zwei Schritte. Erst ein Dialog mit den Folgen (alle
Fahrzeuge, Aufgaben, Einträge und Kosten, kein Rückgängig) und dem Passwort, dann eine zweite Nachfrage „Wirklich
löschen?“; erst sie ruft `DELETE /auth/me`. Fehler (falsches Passwort, letzter Admin, 429, Netz) führen zurück zum
ersten Schritt. Danach Erinnerungen stoppen, alles Lokale löschen wie bei **Server wechseln**, zurück zur Auswahl.
Das Passwort liegt nur im Speicher des Dialogs. Der generierte Client schickt `DELETE` mit Body über `@DELETE`, was
Retrofit ablehnt; daher `AccountApi` mit `@HTTP(hasBody = true)`.

### Fehler im Betrieb

„Keine Verbindung“ bzw. „Server antwortet nicht“ mit „Erneut versuchen“; ungültiges Geräte-Token → zurück zur
Anmeldung mit „Bitte melde dich erneut an“; gelöschtes Profil → zurück zur Profilauswahl.

## Anmeldung, Sitzung und Speicherung

### Gespeicherte Daten

| Was | Wo | Hinweis |
|---|---|---|
| Server-Adresse, Modus | DataStore | |
| Geräte-Token (Portal) | DataStore, mit AES-Schlüssel aus dem Android Keystore verschlüsselt (`TokenCipher`) | Schlüssel verlässt den Keystore nicht |
| gewähltes Profil (Haushalt) | DataStore | Profile haben kein Passwort |
| Sitzungs-Cookie, `XSRF-TOKEN` | nur Arbeitsspeicher (OkHttp-CookieJar) | nach Neustart neue Sitzung |
| Einstellungen, zuletzt gemeldete Fälligkeiten | DataStore | |
| noch nicht übertragene Einträge (app#65) | `files/outbox/entries.json` (`FileOutbox`) | geleert bei Abmelden, Serverwechsel und Kontolöschung (mit Warnung); übersteht eine neue Anmeldung |
| letzte Server-Antworten für die Offline-Ansicht: Fahrzeuge, Aufgaben, Messkontext (app#64) | `files/offline/*.json` (`FileOfflineStore`) | gelöscht bei Anmeldung, Profilwahl, Abmelden, Serverwechsel und Kontolöschung sowie für Fahrzeuge, die es nicht mehr gibt |

E-Mail und Passwort werden nie gespeichert. `android:allowBackup="false"` und Datenextraktionsregeln, die alles
ausschließen: Token und Einstellungen gelangen in kein Cloud-Backup; ein neues Handy meldet sich neu an.

### Sitzung (Portal)

- Jede Anfrage mit Sitzungs-Cookie; ein Interceptor setzt bei schreibenden Anfragen `X-XSRF-TOKEN` aus dem Cookie.
  Vor dem ersten Login holt `GET /api/v1/config` das CSRF-Cookie.
- **Bei 401** tauscht ein OkHttp-`Authenticator` das Geräte-Token über `device-session`, speichert das neue Token
  **bevor** die ursprüngliche Anfrage wiederholt wird, und wiederholt sie einmal.
  - Ein Mutex erlaubt nur einen Tausch zur Zeit (Vordergrund und Hintergrund-Job teilen den Client).
  - Hat sich das gespeicherte Token seit Beginn der Anfrage geändert, wird nicht erneut getauscht, sondern mit der
    neuen Sitzung wiederholt (Regel aus API-Spec 2.10.0: nacheinander tauschen, nach 401 einmal mit dem neuen Token).
  - 401 beim Tausch selbst → Token löschen, Zustand „neu anmelden“.
- **Abmelden:** `POST /auth/logout` (widerruft das Gerät), dann Token, Profil und Cookies löschen; **Server
  wechseln** löscht zusätzlich die Adresse.

### Sitzung (Haushalt)

Bei 401 wählt der `Authenticator` das gespeicherte Profil neu (`POST /profiles/{id}/select`); 404 → Zustand
„Profil wählen“.

### Netz und Sicherheit

- **`http://` nur im Heimnetz:** Ein Netzwerk-Interceptor prüft bei jeder `http://`-Verbindung die tatsächlich
  verbundene IP-Adresse (nach DNS-Auflösung). Erlaubt: IPv4 `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`,
  `127.0.0.0/8`, `169.254.0.0/16`, `100.64.0.0/10` (VPNs wie Tailscale); IPv6 `fc00::/7`, `fe80::/10`, `::1`.
  Alles andere wird vor dem Senden abgebrochen: „Unverschlüsselte Verbindung nur im Heimnetz erlaubt – bitte
  https:// verwenden.“ Der Name ist egal (`http://muggel.privat:8080`, `http://192.168.178.20:8080`).
- `network_security_config` erlaubt Klartext grundsätzlich (Heimnetz-Adressen sind vorab unbekannt); die Prüfung
  oben ist die eigentliche Regel.
- Kein Logging von Anfragen/Antworten im Release-Build; im Debug-Build höchstens Methode, Pfad, Status.
- Zeitlimits: 15 s Verbindung, 30 s Lesen.

## Benachrichtigungen

- **WorkManager**, periodisch einmal täglich, nur mit Netz; bevorzugt morgens (`setInitialDelay` auf den nächsten
  Morgen), die genaue Zeit bestimmt Android.
- Ablauf: alle Fahrzeuge und Aufgaben über dieselben Repositories laden (Sitzung wie oben) → aktive Aufgaben mit
  `OVERDUE` oder `DUE_SOON` → mit dem zuletzt gemeldeten Stand (Aufgaben-ID → Status) vergleichen → melden, was neu
  fällig ist oder von `DUE_SOON` zu `OVERDUE` wechselt → **eine Benachrichtigung je Fahrzeug** (z. B. „Africa Twin:
  Ölwechsel überfällig, Kette schmieren bald fällig“), Tipp öffnet das Fahrzeug → neuen Stand speichern; nicht mehr
  fällige Aufgaben fallen heraus und werden beim nächsten Fälligwerden wieder gemeldet.
- Kanal „Fällige Wartungen“; Sichtbarkeit `PRIVATE`, öffentliche Kurzfassung „Wartung fällig“ (Sperrbildschirm ohne
  Namen).
- Läuft nicht ohne Erlaubnis oder bei abgeschaltetem Schalter. Abmelden und Server wechseln stornieren den Job und
  löschen den gemeldeten Stand.
- Fehler: ohne Netz Wiederholung mit Backoff; ungültiges Token → Job endet, einmalige Benachrichtigung „Bitte erneut
  anmelden, sonst keine Erinnerungen“.
- Bewusst nicht: Echtzeit-Push (bräuchte einen Push-Dienst). Änderungen aus der Web-App erscheinen beim nächsten Lauf
  oder beim Öffnen.

## Aufbau

Ein Modul, Paket `de.bikemaintainer.app`:

| Paket | Inhalt |
|---|---|
| `data.network` | OkHttp-Client, CookieJar (Arbeitsspeicher), CSRF-Interceptor, Session-Authenticator (Token-Tausch / Profil-Neuwahl, Mutex), Heimnetz-Wächter |
| `data.storage` | `SettingsStore` (DataStore), `TokenCipher` (Interface; Keystore-AES-GCM in der App, Fake in Tests) |
| `data.repository` | `AuthRepository`, `VehicleRepository`, `MaintenanceRepository`; bilden generierte DTOs auf App-Modelle ab; Schreiben als Absicht (`updateMileage(vehicleId, km)`, `logMaintenance(…)`) |
| `domain` | App-Modelle (`Vehicle`, `MaintenanceTask`, `TaskStatus`, …), Logik „was ist neu zu melden“ (reine Funktion) |
| `ui.*` | `setup`, `login`, `register`, `profiles`, `vehicles`, `vehicle`, `logmaintenance`, `settings`, `theme`; je Bildschirm Composable + ViewModel mit `StateFlow` |
| `notifications` | `DueTasksWorker`, `DueTaskNotifier`, Kanal |
| Wurzel | `BikeMaintainerApp` (Application), `AppContainer` (Abhängigkeiten von Hand, kein Hilt), `MainActivity` mit Navigation Compose |

- **Theme:** Werkstatt-Design der Web-App (dunkel, Orange-Akzent) plus helle Variante nach Systemeinstellung;
  Statusfarben (überfällig/bald fällig/ok) fest und in beiden Varianten ausreichend kontrastreich.
- **Texte:** `values/strings.xml` (Englisch) und `values-de/strings.xml`; keine festen Texte im Code.
- **Barrierefreiheit:** Inhaltsbeschreibungen für Icons und Status, skalierbare Schrift, Touch-Ziele ≥ 48 dp.

## Tests

- **Netzwerk (MockWebServer):** CSRF-Header; Tausch bei 401 und Wiederholung; paralleler Zugriff → genau ein Tausch;
  geändertes gespeichertes Token → kein zweiter Tausch; 401 beim Tausch → „neu anmelden“; Haushalt wählt Profil neu,
  404 → „Profil wählen“; `http://` gegen öffentliche IP abgelehnt, gegen private/Tailscale-IP erlaubt.
- **Repositories:** Abbildung der DTOs; Kilometerstand per PUT mit unveränderten übrigen Feldern.
- **ViewModels** mit Fake-Repositories: Laden, Fehler, Erfolg; Kilometerstand nicht kleiner als bisher.
- **Benachrichtigungslogik:** neu fällig, `DUE_SOON` → `OVERDUE`, unverändert, erledigt, inaktive Aufgaben.
- **Compose-UI** (Robolectric, ohne Emulator): Anmeldung, Wartung erfassen.
- **Abdeckung (Kover):** ≥ 80 % Zeilen für `data` und `domain`; generierter Code und `ui.theme` ausgenommen.

## CI

Erweitert `.github/workflows/ci.yml` aus #5: gleichnamigen api-Branch (sonst `main`) nach `../bike-maintainer-api`
auschecken, Client generieren, `testDebugUnitTest`, Kover-Grenze, Lint; Trivy und CodeQL wie bisher (CodeQL ebenfalls
mit Spec-Checkout).

## Umsetzung (Tickets im App-Repo, in dieser Reihenfolge)

1. **Netzwerk und Speicherung:** Client-Generierung, OkHttp-Bausteine, `SettingsStore`, `TokenCipher`,
   Repositories-Grundgerüst, CI mit Spec-Checkout und Kover.
2. **Einrichtung und Anmeldung:** Server-Adresse, Geräte-Login bzw. Profilauswahl, Abmelden, Server wechseln,
   Einstellungen, Werkstatt-Theme hell/dunkel, de/en, `allowBackup=false`.
3. **Fahrzeuge:** Liste mit Status, Detail mit Aufgaben.
4. **Kilometerstand und Wartung erfassen.**
5. **Benachrichtigungen.**

## Später

- **Offline** (Epic backend#159): Ansehen ist umgesetzt (app#64). Ohne Verbindung liefern die Repositories die zuletzt
  geladenen Antworten aus `FileOfflineStore` mit ihrem Abrufzeitpunkt (`Outcome.Success.asOf`), Liste und Fahrzeug
  zeigen „Offline – Stand vom …“ statt „Keine Verbindung.“. Statt Room genügen JSON-Dateien je Antwort: wenige,
  kleine Daten, keine Abfragen, keine neue Abhängigkeit. Die Erinnerungsprüfung wertet gespeicherte Daten nicht aus
  (ihre Status sind so alt wie die Daten) und versucht es später erneut.

  **Eintragen ohne Verbindung (app#65):** Gespeichert wird weiter zuerst direkt. Scheitert das an der Verbindung, kommt
  der Eintrag in die Warteschlange (`FileOutbox`, `files/outbox/entries.json`) und `OutboxWorker` (WorkManager, nur
  mit Netz, Backoff ab 30 s) überträgt sie der Reihe nach (`OutboxSync`). Kilometerstand über `PUT …/mileage`
  (api#40: nur anheben, gleicher Wert unschädlich); Wartung mit `clientEntryId` (api#42), die das Formular einmal
  erzeugt und bei jedem Versuch mitschickt – ein Wiederholungsversuch legt nichts doppelt an. Vor dem ersten Senden
  prüft die App die Einträge des Fahrzeugs: gleicher Tag, gleiche Aufgaben → „Trotzdem übertragen?“ statt senden.
  Was nicht übertragbar ist (Fahrzeug/Aufgabe gelöscht, höherer Stand auf dem Server, schreibgeschützt), bleibt mit
  Grund stehen, bis es verworfen wird. Die Warteschlange übersteht eine neue Anmeldung (Sitzung abgelaufen) und wird
  beim Abmelden und Serverwechsel – nach Warnung – geleert.
- Fahrzeuge/Aufgaben verwalten, Kosten und Material, PDF-Bericht, Passwort/Konto löschen in der App (das Abo bleibt
  dauerhaft im Web).
- Veröffentlichung: Release-Signatur, F-Droid (reproduzierbarer Build), Google Play (Datensicherheits-Angaben,
  Datenschutzerklärung mit App-Abschnitt).
- ktlint.
