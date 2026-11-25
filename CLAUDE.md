# CLAUDE.md – LightPilot

## Sprache
- Mit dem Maintainer auf Deutsch kommunizieren. App-Texte, README, docs/ und Release-Notes ebenfalls auf Deutsch.
- Code, Kommentare und Commit-Messages auf Englisch (bestehender Stil).
- Terminal-Befehle fish-kompatibel angeben (keine bash-only-Syntax).

## Zweck und Hardware
- Extension für den **Hammerhead Karoo 3** (karoo-ext SDK 1.1.8). Sie schaltet die Fahrradlichter automatisch nach Tag/Nacht, Radar, Fahrt- und Pausenzustand.
- **Garmin Varia RTL515** (Rücklicht und Radar, ANT+):
  - Geschaltet über die undokumentierte Licht-Schnittstelle des Karoo-SensorService.
  - Im Karoo gekoppelt, „Auto Control“ aus.
- **Magicshine EVO 1700** (Front, BLE):
  - Eigenes Protokoll, siehe `docs/magicshine-evo1700-protocol.md`.
  - Gruppe 1 = Abblendlicht, Gruppe 2 = Fernlicht.
- **Optional:** Shimano Di2 über die Extension Ki2 (`docs/di2-ki2.md`). SRAM AXS über die Karoo-Aktionen.
- **Installation:** per Sideload. Der Link den Link `…/releases/latest/download/lightpilot.apk` wird mit der Hammerhead Companion App geteilt.

## Architektur
- **`core/`:** reines Kotlin ohne Android, mit Unit-Tests (`./gradlew :core:test`).
  - `Model.kt`: ManualMode (AUTO → PELOTON → ON → OFF → HARD_OFF), LightConfig, Settings, Reason, Beam (LOW/HIGH).
  - `Brain.kt`: die **einzige** Stelle, die den Soll-Modus je Licht bestimmt. Reine Funktionen, Zeit wird hineingegeben. Priorität, höchste zuerst:
    1. keine Fahrt / Fahrtende
    2. Pause-Taste
    3. Hart aus
    4. lange Auto-Pause
    5. Radar-Warnung (Peloton: Dauerlicht)
    6. Pin, also Eingriff von außen oder über das Datenfeld
    7. Manuell An/Aus
    8. Tag/Nacht mit Sparmodus
  - `Reconciler.kt`: vergleicht Soll, Gesendet und Gemeldet. Sendet nur bei Abweichung und übernimmt externe Änderungen als Pin.
- **`app/`:**
  - `LightPilotExtension`: Karoo-Streams (RADAR, SPEED, RideState), Verbindungslogik, Di2, Tunnel und Suche.
  - `engine/LightPilotEngine`: verbindet Brain/Reconciler mit den Controllern.
  - `engine/TimeBasedController`: Sonnenzeiten.
  - `engine/AmbientLightSensor`: Tunnelmodus.
  - `karoo/KarooLightControl`: ANT+-Schaltung.
  - `ble/MagicshineBleController` und `MagicshineProtocol`.
  - `di2/Ki2Bridge`: handgeschriebener Binder zum `Ki2Service`.
  - `data/AppSettings` mit `PreferencesRepository`, inklusive `migrated()` für alte Einstellungen.
  - `diag/BrakeRecorder` und `log/EventLog`.
  - `ui/`: Compose. Startseite mit Status und Reitern **Licht | Tasten | Extras**, darunter höchstens eine Ebene Unterseiten.
- **Datenfelder** (RemoteViews, `datatypes/DataTypes.kt`), dazu gleichnamige Bonus-Aktionen für Tasten:
  - **LightPilot:** Status. Tippen schaltet ManualMode weiter.
  - **Frontlicht:** Stufe und Akku. Tippen wählt die nächste angehakte Stufe.
  - **Abblend/Fern:** Strahlwechsel.

## Designentscheidungen (Begründung · verworfene Alternative)

**Fahrt und Pause**
- **Pause-Taste:** Licht und Radar-Warnung sofort aus. Grund: bewusster Halt. Auto-Pause schaltet erst nach 2 min aus (0–5 min). Grund: Ampelstopps sollen das Licht nicht ausschalten.
- **Fahrtende:** Licht aus. **Neue Fahrt:** immer Automatik.
- **Zonenwechsel Tag/Nacht:** beendet manuelle Eingriffe (An/Aus/Hart aus, Feldwahl). Peloton bleibt, weil es eine Fahrsituation ist und kein Eingriff ins Licht.

**Radar**
- **Hold 3 s** (0–10 s): kein Flackern zwischen zwei Fahrzeugen.
- **Watchdog 5 s** (2–30 s): Ohne Radardaten endet die Warnung, nichts bleibt hängen.
- **Hart aus** unterdrückt als einziger Modus auch die Radar-Warnung. **Aus** warnt weiter (Sicherheit).

**Peloton und manuelle Modi**
- **Peloton:** wie Automatik, nur ist die Radar-Warnung Dauerlicht statt Blinken. Gilt tageszeitunabhängig, damit Mitfahrer nicht irritiert werden. Die Stufe kommt aus der Licht-Einstellung „Warnung Peloton“. Global abschaltbar, dann wird Peloton im Zyklus übersprungen.
  - Verworfen: Peloton nur nachts.
- **Wechsel Automatik ↔ Peloton** behält die Feldwahl. Jeder andere manuelle Wechsel verwirft sie.
- **Externe Änderungen** (Control Center, Taste am Licht) übernimmt die App, statt dagegen zu senden. Kurz nach einem eigenen Befehl werden Meldungen ignoriert, weil der Karoo manchmal den alten Modus noch einmal meldet.

**Frontlicht und Helligkeitsstufen**
- **Frontfeld-Pausenregel** (Fenster 5 s, 0–10 s): Der erste Tipp nach einer Pause schaltet eine leuchtende Lampe aus und eine ausgeschaltete auf die kleinste angehakte Stufe, nie auf Aus. Innerhalb des Fensters geht es zur nächsten Stufe.
  - Verworfen: Regel nur bei angehaktem „Aus“ (v0.1.8, in der Praxis nicht spürbar).
  - Verworfen: Doppeltipp oder Halten, weil Datenfelder nur einfache Taps liefern.
- **Helligkeit:** Magicshine Aus und 10–100 % in 10-%-Schritten je Gruppe, dazu ein eigener Wert per Schieberegler. Grund: Die Magicshine-App rundet; „Aus“ war dort nicht erreichbar.
  - Feld-Standard: Abblend 30/60/100, Fern 50/100, Aus.
  - Nacht: Abblend 60. Sparmodus: Abblend 30.
- **Immer mit Abblendlicht starten, nie mit Fernlicht.** Fernlicht beginnt mit seiner höchsten angehakten Stufe, jeder Strahl merkt sich seine letzte Stufe. Bei ausgeschalteter Lampe wird der Strahl nur vorgewählt.
  - Verworfen: Beim Strahlwechsel einschalten.
- **Sparmodus** (unter 20 %): Er schaltet nie ein Licht ein, das sonst aus wäre.

**Verbindungen**
- **Varia „verbunden“** nur, solange ihr Radar Daten liefert (5 s). Grund: Der Karoo meldet keinen Lichtzustand.
  - Verworfen: Karoo-Verbindungsstatus, der auch bei ausgeschalteter Varia „verbunden“ zeigte.
- **Magicshine-Verbindung:** erst scannen, dann verbinden, und nur mit der eingerichteten Adresse (Lampen anderer Gruppenfahrer bleiben unberührt). Grund: Zufallsadresse, Android 12 braucht den Scan.
  - Verworfen: direkter Connect per Adresse, der bei spätem Einschalten der Lampe scheiterte.
- **„Licht entfernen“** trennt GATT wirklich, sonst ist die Lampe nicht mehr auffindbar.
- **BLE-Suche** nur in 5-min-Fenstern. Neu angestoßen durch Fahrtstart, Feldtipp, Wechsel auf Nacht und Verbindungsabbruch; durchgehend nur bei offener Lichter-Seite. Grund: Strom und CPU.
  - Verworfen: Dauersuche.
- **Magicshine meldet weder Tastendrücke noch ihre Stufe.** Angezeigt wird die zuletzt gesendete.

**Tunnelmodus** (Standard aus, `docs/tunnelmodus.md`)
- Standardwerte: unter 300 lx für 2 s → Nacht, über 600 lx für 10 s → Tag.
- Ausgewertet wird der letzte Sensorwert im festen 250-ms-Takt.
  - Verworfen: Mittelwert über die Sensor-Events. Der Sensor meldet nur bei Änderung, das ergab 2 s bis „nie“ Verzögerung.
- Der Sensor ist nur aktiv, wenn Licht gebraucht wird oder die Einstellungen offen sind.

**Di2**
- Ansatz A: Mithören bei Ki2 (Binder, Switch-Callback). Standard aus.
- In Ki2 die genutzte Druckart auf **„None“** stellen.
  - Verworfen: eigene Di2-Verbindung, die mit Ki2 kollidieren würde.

**Bremslicht** (offen)
- Erst messen (BrakeRecorder, Latenztest). Gemessen: Befehl bis Varia-Licht 107–322 ms.
- Kriterium: Erkennung plus Schalten höchstens **500 ms** gesamt, sonst wird die Funktion nicht gebaut.

**GUI**
- Maximal eine Untermenü-Ebene. Schrift ×1.15.
- Keine unnötigen Umbrüche in Buttons. Einzeilige Texte, die nicht passen, laufen als langsame Laufschrift statt „…“, nicht aber in Reitern.
- Statusfeld zeigt Rolle (Front/Rück) statt Gerätename. Akku als farbiges Kästchen: grün ≥ 50 % bzw. Varia neu/gut, gelb 20–49 % bzw. ok, rot darunter.
- Die App öffnet die zuletzt benutzte Ansicht.

## Build und Release
- **CI** (`.github/workflows/build.yml`): Auf jedem Push laufen `:core:test` und der APK-Build. Bei einem veröffentlichten Release wird `lightpilot.apk` angehängt.
- **Signatur:** Keystore und Passwörter **nur als GitHub-Secrets**, Namen siehe Workflow-Kopf. Niemals Keystore, Passwörter oder Tokens ins Repo, in Logs oder in Antworten. Ohne Secrets entsteht ein Debug-APK, für ein Update dann deinstallieren.
- **Versionen:**
  - `versionName` = Release-Tag ohne `v` (Schema `vMAJOR.MINOR.PATCH`). Neue Funktionen erhöhen MINOR, Korrekturen PATCH.
  - `versionCode` = Anzahl der Commits (`git rev-list --count HEAD`).
  - `app/manifest.json` (`latestVersion`, `latestVersionCode`, `releaseNotes`) bei jedem Release mitziehen.
- **Releases** erstellt der Maintainer selbst auf GitHub (Tag `vX.Y.Z`, kein Pre-Release).
- **CI-Status prüfen:** `gh api repos/opaunke82/lightpilot/actions/runs?per_page=1`

## Arbeitsregeln
- **Vor neuen Features** erst einen Plan mit Rückfragen vorlegen. Bauen erst nach ausdrücklichem „Go“. Bei GUI-Änderungen Skizze oder Liste zum Abhaken.
- **Jeder PR** wird als **New**, **Update** oder **Fix** eingeordnet (Titel oder erste Zeile).
- **Jeder PR** beschreibt, was **auf dem Karoo getestet** werden muss: konkrete Schritte, erwartetes Verhalten, ggf. Simulation unter Extras → Test & Simulation.
- **Bei jedem Release** die Release-Notes als Copy-Paste-Text (Markdown-Codeblock) liefern, gegliedert in `## New` (neue Funktionen), `## Update` (funktional geänderte Dinge) und `## Fix` (Fehlerbehebungen). Leere Kategorien weglassen.
- Logikänderungen gehören in `core/` und bekommen Tests in `BrainTest` bzw. `ReconcilerTest`.
- README, `docs/` und Menüpfade bei jeder Änderung aktuell halten, offene Punkte im README pflegen.
- Ursachen erklären, nicht nur Symptome beheben.
- Keine Zugangsdaten in Code, Docs, Commits oder Memory.
- Keine persönlichen Daten (Namen, Adressen, Kontaktdaten, Standorte o. Ä.) in Dateien, Commits, PRs oder Releases – außer der Maintainer gibt sie ausdrücklich frei.
