# LightPilot

Karoo-Extension, die Fahrradlichter abhängig von Tageszeit, Radar und Fahrtzustand schaltet.
Gebaut für das Garmin Varia RTL515 (ANT+) und das Magicshine EVO 1700 (Bluetooth) am Hammerhead Karoo 3.

**Status:** im Alltagstest mit Varia RTL515 und EVO 1700 am Karoo 3. Die Steuerlogik ist mit
Unit-Tests abgedeckt (`./gradlew :core:test`). Was sich je Version geändert hat, steht in den
Release-Notes unter **Releases**, gegliedert in New, Update und Fix.

## Was es tut

- **Nacht:** Rücklicht blinkt langsam (Night Flash), Frontlicht leuchtet mit der Nachtstufe.
- **Tag:** Rücklicht aus, blinkt schnell, sobald das Radar ein Fahrzeug meldet; Frontlicht aus.
- **Tag oder Nacht:** nach Sonnenauf- und -untergang, Nacht ab 30 min vor Sonnenuntergang
  (beides einstellbar). Ohne GPS-Fix rechnet die App mit der letzten bekannten Position, gab es
  noch nie GPS, mit einer Schätzung aus der Zeitzone. Die Sonnenzeiten werden täglich neu
  berechnet. Optional zusätzlich mit dem Lichtsensor des Karoo.
- **Haltezeit:** Nach dem letzten Fahrzeug bleibt die Warnung 3 s an (einstellbar 0–10 s).
- **Watchdog:** Kommen 5 s lang keine Radardaten, endet die Warnung. Nichts bleibt hängen.
- **Statusfeld:** Tippen schaltet reihum Automatik → Peloton → An → Aus → Hart aus.
  An, Aus und Hart aus gelten bis zum nächsten Tag/Nacht-Wechsel, Peloton bleibt dabei erhalten.
  Jede neue Fahrt beginnt mit Automatik. Änderungen über das Control Center oder die Taste am
  Licht übernimmt die App, statt dagegen zu arbeiten.
- **Peloton-Modus:** alles wie Automatik, nur leuchtet das Rücklicht bei einer Radar-Warnung
  dauerhaft statt zu blinken, damit Mitfahrer nicht irritiert werden. Die Stufe ist pro Licht
  wählbar („Warnung Peloton“, Standard: Dauerlicht hell). Global abschaltbar (Abschnitt „Radar“),
  dann wird er im Statusfeld übersprungen.
- **Abblend- und Fernlicht (Magicshine EVO):** Beide Lichtgruppen sind getrennt einstellbar
  (Abblend 10–100 %, Fernlicht 10–100 %, oder ein eigener Wert per Schieberegler). Standard ist
  immer das Abblendlicht. Das Datenfeld **Abblend/Fern** wechselt zwischen beiden: Jedes Licht
  merkt sich seine zuletzt genutzte Stufe, das Fernlicht beginnt mit seiner höchsten angehakten
  Stufe. Ist die Lampe aus, wird nur vorgewählt.
- **Frontlicht-Feld:** Tippen schaltet durch die beim Licht angehakten Stufen des aktiven Lichts
  (Standard: Abblend 30/60/100, Fernlicht 50/100), optional mit Aus.
  Pausenregel (Standard 5 s, einstellbar 0–10 s unter „Lichter“): Der erste Tipp nach einer Pause
  schaltet eine leuchtende Lampe aus, eine ausgeschaltete auf die kleinste angehakte Stufe; danach
  beginnt der Zyklus wieder von unten. Eine gewählte Stufe bleibt beim Wechsel zwischen Automatik
  und Peloton erhalten.
- **Pause:** Pause-Taste am Karoo schaltet Licht und Radar-Warnung sofort aus.
  Auto-Pause erst nach 2 min (einstellbar bis 5 min).
- **Fahrtende:** Licht aus.
- **Sparmodus:** Bei niedrigem Akku ein sparsamerer Modus (abschaltbar).
- **Verbindung der Magicshine:** LightPilot sucht die eingerichtete Lampe gezielt (nach Adresse
  und Namen, verbunden wird nur mit der eingerichteten Adresse) und verbindet sich, sobald sie
  sendet, auch wenn sie erst nach dem Karoo oder nach dem Fahrtstart eingeschaltet wird. Die
  Suche läuft höchstens 5 min und wird neu angestoßen durch Fahrtstart, Tipp auf ein
  LightPilot-Feld, Wechsel auf Nacht und Verbindungsabbruch; bei offener Einstellungsseite
  durchgehend. Ist die Lampe verbunden, wird nicht gesucht. Sie bleibt auch im Standby
  verbunden und lässt sich von der App einschalten.
- **Anzeige:** Die Varia gilt als verbunden, solange ihr Radar Daten liefert (der Karoo meldet
  für das Licht selbst keinen Zustand). Die Magicshine meldet weder Tastendrücke noch ihre
  aktuelle Stufe; angezeigt wird die zuletzt gesendete. Akku farbig: grün (Magicshine 100–50 %,
  Varia neu/gut), gelb (49–20 %, Varia ok), rot (darunter, Varia niedrig/kritisch).
- **Shimano-Di2-Tasten (über Ki2):** eigene Seite „Di2-Tasten (Ki2)“, Standard aus. LightPilot
  hört bei der Extension Ki2 mit, die die Verbindung zur Di2 hält, und bekommt die Tasten als
  D-Fly-Kanal 1–4 mit Druckart (einfach, doppelt, halten). Jede Kombination lässt sich mit einer
  der drei LightPilot-Aktionen belegen. Damit Ki2 nicht zusätzlich reagiert, die Druckart in Ki2
  auf „None“ stellen. SRAM AXS braucht das nicht: Bonus Buttons und Blips lassen sich am
  Karoo unter „Sensoren“ → AXS → „Configure Controls“ direkt mit LightPilot-Aktionen belegen.
  Schritt-für-Schritt-Anleitung: [docs/di2-ki2.md](docs/di2-ki2.md).
- **Werkseinstellungen:** setzt Lichter-Zuordnung, Einstellungen, gespeicherte Position und
  Protokoll zurück (mit Bestätigungsabfrage). Die Kopplung im Karoo bleibt.

### Priorität

Eine einzige Funktion (`core/.../Brain.kt`) entscheidet für jedes Licht, höchste Priorität zuerst:

1. keine Fahrt / Fahrt beendet
2. Pause-Taste → aus, kein Radar
3. Hart aus → aus, kein Radar
4. Auto-Pause länger als eingestellt → aus, kein Radar
5. Radar-Warnung inklusive Haltezeit, im Peloton-Modus dauerhaft statt blinkend
6. Eingriff von außen oder über das Frontlicht-Feld
7. Manuell An / Aus
8. Tag/Nacht, ggf. mit Sparmodus

`Reconciler.kt` vergleicht danach Soll und Ist und sendet nur bei Abweichung.
Die Logik im Modul `core` ist reines Kotlin ohne Android und läuft mit `./gradlew :core:test`.

## Einrichtung (einmalig)

### 1. Token für das Karoo-SDK

Das SDK liegt auf GitHub Packages. In GitHub unter
**Settings → Developer settings → Personal access tokens → Tokens (classic)** einen Token
nur mit `read:packages` anlegen und im Repository unter
**Settings → Secrets and variables → Actions** als Secret `KAROO_EXT_TOKEN` speichern.

### 2. Fester Signierschlüssel

Damit sich Updates ohne Neuinstallation einspielen lassen, wird jedes Release mit demselben Schlüssel signiert.
Auf dem eigenen Rechner (fish-Shell, `keytool` kommt mit dem JDK):

```fish
keytool -genkeypair -v -keystore ~/lightpilot-release.jks -alias lightpilot -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 ~/lightpilot-release.jks > ~/lightpilot-release.b64
```

Dann vier Secrets anlegen:

| Secret | Inhalt |
| --- | --- |
| `KEYSTORE_BASE64` | Inhalt von `lightpilot-release.b64` |
| `KEYSTORE_PASSWORD` | Passwort des Keystores |
| `KEY_ALIAS` | `lightpilot` |
| `KEY_PASSWORD` | Passwort des Schlüssels |

Danach `lightpilot-release.b64` löschen. Die `.jks`-Datei sicher aufheben: Geht sie verloren,
muss LightPilot auf dem Karoo einmal deinstalliert und neu installiert werden.

Ohne diese Secrets baut der Workflow eine Debug-Version; die muss vor jedem Update deinstalliert werden.

### 3. Release erstellen

Auf GitHub **Releases → Draft a new release**, den Tag (z. B. `v0.1.9`) über **Tag: Select tag**
neu anlegen, nicht als Pre-Release markieren, veröffentlichen. Die Release-Notes nach
**New** (neue Funktionen), **Update** (geänderte Funktionen) und **Fix** (Fehlerbehebungen) gliedern.
Der Workflow hängt danach `lightpilot.apk` an das Release (dauert einige Minuten).

### 4. Auf den Karoo bringen

Auf dem Handy diesen Link lange drücken und mit der **Hammerhead Companion App** teilen
(Karoo im WLAN):

```
https://github.com/opaunke82/lightpilot/releases/latest/download/lightpilot.apk
```

Die installierte Version steht ganz unten auf der Einstellungsseite von LightPilot.

### 5. Am Karoo einrichten

1. Varia unter **Einstellungen → Sensoren** per **ANT+** koppeln. Es muss als Licht erscheinen
   (Lichtseite im Control Center während einer Fahrt). Fehlt die Seite: Varia zurücksetzen
   (Taste 8 s halten) und neu koppeln.
2. **Auto Control** für das Licht ausschalten.
3. Andere Licht-Extensions (KarooFirefly, LightONKaroo) deinstallieren oder beenden.
4. Magicshine einschalten. Sie darf nicht mit der Magicshine-App auf einem Handy verbunden sein,
   sonst ist sie für den Karoo unsichtbar.
5. LightPilot unter **Extensions** öffnen. Unter **Lichter** erscheinen die gefundenen Lichter gelb
   („antippen zum Einrichten“); antippen und Rolle wählen (Varia: Rück, Magicshine: Front). Beim
   Frontlicht die Stufen für das Datenfeld wählen, bei der Magicshine getrennt für Abblend- und Fernlicht. Fehlt die Magicshine:
   **Nach Bluetooth-Lichtern suchen**. Die Standardwerte entsprechen dem Anforderungsprotokoll.
6. Datenfelder **LightPilot**, **Frontlicht** und **Abblend/Fern** in ein Fahrprofil legen.

## Bedienung während der Fahrt

| | Anzeige | Tippen |
| --- | --- | --- |
| Datenfeld **LightPilot** | Tag/Nacht, Radar, Modus, Rücklicht und Akku; rot bei Radar-Warnung | Automatik → Peloton → An → Aus → Hart aus |
| Datenfeld **Frontlicht** | aktuelle Stufe und Akku | nächste angehakte Stufe im aktiven Licht, Pausenregel siehe oben |
| Datenfeld **Abblend/Fern** | aktives oder vorgewähltes Licht | wechselt Abblend- und Fernlicht |

Alle drei Aktionen gibt es auch als Zusatzaktion für eine Taste des Karoo
(„Licht: Automatik / Peloton / An / Aus / Hart aus“, „Frontlicht: nächste Helligkeit“,
„Frontlicht: Abblend / Fernlicht“).
Außerhalb einer Fahrt reagieren die Felder nicht auf Tippen.

## Testen

Im Abschnitt **Test und Diagnose** der App den Simulationsmodus einschalten. Er spielt die
Abläufe am Tisch durch, das echte Radar wird dabei ignoriert:

1. **Fahrt** starten und beenden
2. **Tag oder Nacht:** Echt (nach Uhrzeit), Tag oder Nacht erzwingen
3. **Radar:** ein Fahrzeug für 5 s melden
4. **Steuerung** wie das Datenfeld: Auto, Peloton, An, Aus, Hart aus und Frontlicht
5. **Pause:** Pause-Taste, Auto-Pause, Weiterfahren

Das Bluetooth-Protokoll der Magicshine EVO 1700 ist in `docs/magicshine-evo1700-protocol.md`
beschrieben (ausgewertet aus einem Mitschnitt der Magicshine-App).

Die Varia meldet ihren neuen Modus etwa 0,4 s nach dem tatsächlichen Umschalten zurück; für
Zeitmessungen taugt die Rückmeldung daher nicht.

**Protokoll öffnen** zeigt die letzten 1000 Einträge im Vollbild, neueste oben, mit Filter
(Alle, Steuerung, Magicshine, System) und Anhalten. **Exportieren** speichert das ganze
Protokoll als Textdatei auf dem Karoo; am Rechner holen mit adb (unter Linux im Paket
`android-tools`):

```fish
adb pull /sdcard/Android/data/io.github.opaunke82.lightpilot/files/
```

## Offene Punkte

- **Bremslicht:** in Prüfung. Der Karoo stellt Beschleunigungssensor, Gyroskop und lineare
  Beschleunigung für Apps bereit. Gemessen: Befehl bis Licht an der Varia etwa 0,1–0,35 s
  (Latenztest mit Video). Offen: Erkennungszeit und Fehlauslösungen aus einer Bremsfahrt
  (Bremslicht-Messung unter „Test und Diagnose“). Grenze: insgesamt höchstens 0,5 s.
- **Varia ohne Radar:** Die Verbindung der Varia wird über ihr Radar erkannt. Ist das Radar am
  Karoo abgeschaltet und nur das Licht in Betrieb, zeigt die App sie als „nicht verbunden“.
- **Inoffizielle Schnittstelle:** Das Schalten der ANT+-Lichter nutzt eine nicht dokumentierte
  Schnittstelle des Karoo. Ein Firmware-Update kann sie ändern.
- **Bildschirm aus / Slower Processing:** Reaktion des Radars in diesem Zustand messen.
- **Testprotokoll:** Ein ausführliches Testprotokoll für die Abläufe der Steuerung ist geplant.

## Lizenz

MIT, siehe `LICENSE`. Herkunft übernommener Teile: `THIRD_PARTY_NOTICES.md`.
