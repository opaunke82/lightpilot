# Shimano-Di2-Tasten mit Ki2 und LightPilot

Mit dieser Anleitung schaltest du das Licht über die verdeckten Tasten oben auf den Di2-Griffen
(Hood-Tasten). Die Verbindung zur Di2 hält die Extension **Ki2**; LightPilot hört bei Ki2 mit und
löst seine Aktionen aus. Schalten über die Hebel funktioniert weiter wie gewohnt.

## Was du brauchst

- Di2 mit Funk (12-fach oder 11-fach mit Funkeinheit EW-WU111)
- Hammerhead Karoo mit LightPilot ab **v0.3.0**
- Ki2 (Karoo-Software `1.527.2014` oder neuer)
- Smartphone mit der Shimano-App **E-Tube Project**

## Ablauf

### 1. Hood-Tasten auf D-Fly-Kanäle legen (Smartphone, E-Tube Project)

1. E-Tube Project öffnen und mit der Di2 verbinden.
2. In den Einstellungen der Schaltereinheiten der linken und der rechten verdeckten Taste je einen
   **D-FLY-Kanal** zuweisen. Üblich: links **Kanal 1**, rechts **Kanal 2**.
3. Einstellungen auf die Di2 übertragen.

Ohne diesen Schritt senden die Hood-Tasten nichts, was Ki2 oder LightPilot empfangen können.

### 2. Ki2 installieren und mit der Di2 verbinden (Karoo)

1. Ki2 auf den Karoo bringen: Link der APK aus dem
   [neuesten Ki2-Release](https://github.com/valterc/ki2/releases/latest/) auf dem Handy lange
   drücken und mit der **Hammerhead Companion App** teilen (wie bei LightPilot).
2. Ki2 öffnen, **`+`** drücken, die Di2 aufwecken (einmal schalten) und das gefundene Gerät mit
   **Add** hinzufügen.
3. Am Karoo unter **Sensoren** → **`+`** → **Extensions** (Puzzle-Symbol) das Ki2-Gerät hinzufügen.
4. Prüfen: In Ki2 auf das Gerät tippen. Unter **D-Fly Channel Status** zeigt Ki2 beim Drücken einer
   Hood-Taste, welcher Kanal reagiert.

### 3. In Ki2 die Druckart für LightPilot freigeben (Karoo, Ki2)

Ki2 und LightPilot bekommen jeden Tastendruck. Damit nicht beide reagieren, gibst du LightPilot
eine **Druckart**, die Ki2 nicht nutzt.

1. Ki2 → **Settings** → **Switches**.
2. Unter **D-Fly Ch. 1** und **D-Fly Ch. 2** die Druckart, die LightPilot bekommen soll, auf
   **None** stellen. Empfehlung: **Double Press**.
3. **Single Press** (z. B. Seite wechseln) und **Hold** können in Ki2 bleiben, wie sie sind.

### 4. LightPilot einrichten (Karoo, LightPilot)

1. LightPilot öffnen → Reiter **Tasten** → **Datenfelder & Tasten** → Abschnitt **Shimano Di2 (über Ki2)**.
2. **Di2-Tasten verwenden** einschalten. Der Status wechselt auf **mit Ki2 verbunden**.
3. Jede Hood-Taste einmal mit der gewählten Druckart drücken. Unter **Letzte Taste** steht z. B.
   „Kanal 1 · Doppelt“. So siehst du, welche Taste welcher Kanal ist.
4. Für den Kanal und die Druckart eine Aktion wählen, z. B.:

   | Taste | Druckart | Aktion |
   | --- | --- | --- |
   | links (Kanal 1) | Doppelt | Frontlicht: Abblend / Fernlicht |
   | rechts (Kanal 2) | Doppelt | Frontlicht: nächste Helligkeit |

   Möglich sind außerdem **Licht: Automatik / Peloton / An / Aus / Hart aus** und **Keine**.
   Die Zuordnung in LightPilot muss zu der Druckart passen, die du in Ki2 auf **None** gestellt hast.
5. Seite schließen. Die Einstellungen sind sofort gespeichert.

### 5. Testen

1. In LightPilot unter Reiter **Extras** → **Test & Simulation** die Simulation einschalten und unter **1. Fahrt**
   starten (oder eine echte Fahrt starten). Außerhalb einer Fahrt reagiert das Licht nicht.
2. Die belegten Hood-Tasten drücken: Der Karoo piept kurz, das Licht reagiert.
3. Ki2 darf bei dieser Druckart nichts tun (kein Seitenwechsel o. Ä.).

## Wenn es nicht klappt

| Anzeige / Verhalten | Ursache und Abhilfe |
| --- | --- |
| Status **Ki2 ist nicht installiert** | Ki2 installieren (Schritt 2), dann LightPilot neu öffnen. |
| Status bleibt **verbinde mit Ki2 …** | Ki2 einmal öffnen, dann den Schalter in LightPilot aus- und wieder einschalten. Sonst unter **Extras** → **Diagnose** das Protokoll ansehen (Filter „Alle“) und die Zeilen von `Ki2Bridge` ansehen. |
| Status **Ki2-Version passt nicht** | Ki2 hat seine Schnittstelle geändert. LightPilot muss angepasst werden, bitte melden. |
| **Letzte Taste** bleibt „–“ | Di2 nicht mit Ki2 verbunden oder Hood-Taste hat keinen D-Fly-Kanal (Schritt 1). In Ki2 unter **D-Fly Channel Status** prüfen. |
| Taste wird angezeigt, aber nichts passiert | Keine Aktion für diesen Kanal und diese Druckart gewählt, oder keine Fahrt aktiv. |
| Ki2 und LightPilot reagieren beide | Die Druckart steht in Ki2 noch nicht auf **None** (Schritt 3). |

## SRAM AXS

Für SRAM AXS brauchst du das alles nicht: Bonus Buttons und Blips lassen sich am Karoo direkt mit
LightPilot-Aktionen belegen (**Sensoren** → AXS-Gruppe → **Configure Controls** → Taste drücken →
**Karoo Action** → **LightPilot**).
