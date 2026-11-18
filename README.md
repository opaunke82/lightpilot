# LightPilot

Karoo-Extension, die Fahrradlichter abhängig von Tageszeit, Radar und Fahrtzustand schaltet.
Gebaut für das Garmin Varia RTL515 (ANT+) und das Magicshine EVO 1700 (Bluetooth) am Hammerhead Karoo 3.

**Status: erste Testversion.** Die Entscheidungslogik ist mit Unit-Tests abgedeckt; das Zusammenspiel
mit Karoo und Lichtern muss auf dem Gerät noch geprüft werden (siehe „Offene Punkte“).

## Was es tut

- **Nacht:** Rücklicht blinkt langsam (Night Flash), Frontlicht Abblendlicht.
- **Tag:** Rücklicht aus, blinkt schnell, sobald das Radar ein Fahrzeug meldet; Frontlicht aus.
- **Haltezeit:** Nach dem letzten Fahrzeug bleibt die Warnung 3 s an (einstellbar 0–10 s).
- **Watchdog:** Kommen 5 s lang keine Radardaten, endet die Warnung. Nichts bleibt hängen.
- **Manuelle Eingriffe:** Datenfeld tippen schaltet reihum Automatik → An → Aus → Hart aus.
  Änderungen über das Control Center oder die Taste am Licht übernimmt die App, statt dagegen zu arbeiten.
  Jeder Eingriff gilt bis zum nächsten Tag/Nacht-Wechsel.
- **Pause:** Pause-Taste am Karoo schaltet Licht und Radar-Warnung sofort aus.
  Auto-Pause erst nach 2 min (einstellbar bis 5 min).
- **Fahrtende:** Licht aus.
- **Sparmodus:** Bei niedrigem Akku ein sparsamerer Modus (abschaltbar).
- **Frontlicht-Datenfeld:** Tippen schaltet die Helligkeit reihum.

### Priorität

Eine einzige Funktion (`core/.../Brain.kt`) entscheidet für jedes Licht, höchste Priorität zuerst:

1. keine Fahrt / Fahrt beendet
2. Pause-Taste → aus, kein Radar
3. Hart aus → aus, kein Radar
4. Auto-Pause länger als eingestellt → aus, kein Radar
5. Radar-Warnung inklusive Haltezeit
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

Auf GitHub **Releases → Draft a new release**, neuen Tag wie `v0.1.0` anlegen, veröffentlichen.
Der Workflow hängt danach `lightpilot.apk` an das Release (dauert einige Minuten).

### 4. Auf den Karoo bringen

Auf dem Handy diesen Link lange drücken und mit der **Hammerhead Companion App** teilen
(Karoo im WLAN):

```
https://github.com/opaunke82/lightpilot/releases/latest/download/lightpilot.apk
```

### 5. Am Karoo einrichten

1. Varia unter **Einstellungen → Sensoren** per **ANT+** koppeln. Es muss als Licht erscheinen
   (Lichtseite im Control Center während einer Fahrt). Fehlt die Seite: Varia zurücksetzen
   (Taste 8 s halten) und neu koppeln.
2. **Auto Control** für das Licht ausschalten.
3. Andere Licht-Extensions (KarooFirefly, LightONKaroo) deinstallieren oder beenden.
4. LightPilot unter **Extensions** öffnen, Lichter antippen und Rolle wählen. Die Standardwerte
   entsprechen dem Anforderungsprotokoll.
5. Datenfelder **LightPilot** und **Frontlicht** in ein Fahrprofil legen.

## Testen

Im Abschnitt **Test und Diagnose** der App den Simulationsmodus einschalten: Fahrt starten,
„Fahrzeug 5 s“, Pausen und Datenfeld-Tipps lassen sich am Tisch durchspielen.

Das Protokoll (Radarstufen, gesendete Befehle, gemeldete Modi) lässt sich exportieren und am
Rechner holen:

```fish
adb pull /sdcard/Android/data/io.github.opaunke82.lightpilot/files/
```

## Offene Punkte

- **Rückmeldung des Varia:** Die App liest den gemeldeten Modus aus den LightParameters des Karoo.
  Ob der Karoo diese bei jeder Änderung schickt, ist ungeprüft. Im Protokoll: Zeilen „Reported mode“.
- **Radar-Datenstrom:** Der Watchdog setzt voraus, dass der Karoo Radardaten laufend liefert,
  auch ohne Fahrzeug. Im Protokoll prüfen; sonst Watchdog-Zeit erhöhen.
- **EVO 1700:** Helligkeit in Prozent (Code aus KarooFirefly); welche Stufe welchem Lumenwert
  entspricht, ist ungeprüft. Fernlicht folgt in Version 1.1 nach einem Bluetooth-Mitschnitt.
- **Inoffizielle Schnittstelle:** Das Schalten der ANT+-Lichter nutzt eine nicht dokumentierte
  Schnittstelle des Karoo. Ein Firmware-Update kann sie ändern.
- **Bildschirm aus / Slower Processing:** Reaktion des Radars in diesem Zustand messen.

## Lizenz

MIT, siehe `LICENSE`. Herkunft übernommener Teile: `THIRD_PARTY_NOTICES.md`.
