# Tunnelmodus

Der Tunnelmodus schaltet das Licht über den Umgebungslichtsensor des Karoo auf **Nacht**, sobald es
dunkel wird: Tunnel, Unterführung, dichter Wald. Danach schaltet er wieder auf **Tag**, wenn es hell
genug ist. Standard: **aus**.

## Einstellen

LightPilot → **Tag und Nacht** → **Tunnelmodus öffnen**:

| Einstellung | Standard | Bereich | Bedeutung |
| --- | --- | --- | --- |
| Tunnelmodus verwenden | aus | | Schaltet den Sensor ein |
| Licht an unter | 300 lx | 10–2000 lx | Darunter gilt es als dunkel |
| Licht aus über | 600 lx | 20–5000 lx | Darüber gilt es als hell (immer über „Licht an“) |
| Dunkel für | 2 s | 0–10 s | So lange muss es dunkel sein, bis Nacht gilt |
| Hell für | 10 s | 0–60 s | So lange muss es hell sein, bis wieder Tag gilt |

Zwischen beiden Schwellen bleibt der Zustand, wie er ist (Hysterese). So flackert das Licht nicht,
wenn die Helligkeit um eine Schwelle pendelt. Die längere Zeit für „hell“ verhindert, dass kurze
Lichtlücken (Allee, Tunnelfenster) das Licht ausschalten.

## Zusammenspiel mit den Sonnenzeiten

- **Sonnenzeiten an (Standard):** Der Sensor kann nur auf **Nacht** schalten, nie auf Tag. Nachts
  bleibt es also Nacht, auch unter einer hellen Straßenlaterne.
- **Sonnenzeiten aus:** Der Sensor allein entscheidet über Tag und Nacht.

## Wie es technisch funktioniert

1. Der Sensor im Karoo 3 (Lite-On LTR-X130A, bis 157.000 lx) meldet einen neuen Wert **nur, wenn
   sich die Helligkeit ändert**.
2. LightPilot merkt sich den letzten Wert und prüft ihn **alle 250 ms**, unabhängig davon, ob ein
   neuer Wert kommt.
3. Bei Tag: Liegt der Wert unter „Licht an unter“, läuft die Zeit „Dunkel für“. Bleibt es so lange
   dunkel, gilt Nacht. Wird es zwischendurch heller, beginnt die Zeit von vorn.
4. Bei Nacht: Liegt der Wert über „Licht aus über“, läuft die Zeit „Hell für“, danach gilt Tag.
5. Die Lichtsteuerung übernimmt den Wechsel beim nächsten Takt (250 ms). Bis die Varia reagiert,
   vergehen weitere 0,1–0,35 s (gemessen).

Gesamtzeit mit Standardwerten: abdecken → Licht an nach etwa **2,5 s**, wieder hell → Licht aus
nach etwa **10,5 s**.

## Richtwerte

| Situation | Helligkeit |
| --- | --- |
| Sonne direkt | 30.000–100.000 lx |
| bedeckter Tag | 1.000–20.000 lx |
| dichter Wald am Tag | 500–2.000 lx |
| beleuchteter Straßentunnel innen | 50–300 lx |
| Sonnenuntergang | um 400 lx |
| Ende der Dämmerung | 3–10 lx |

Der Karoo schaut schräg nach oben: Im Wald misst er vor allem den Himmel durch das Blätterdach.
Soll das Licht schon in lichtem Wald angehen, „Licht an unter“ erhöhen (z. B. 800 lx, dann
„Licht aus über“ 1500 lx).

## Testen

1. Tunnelmodus einschalten.
2. **Test und Diagnose** → **Tunnelmodus live** zeigt den aktuellen Wert und den Zustand, z. B.
   `212 lx · Tag · dunkel seit 1,2 s → Nacht in 0,8 s`.
3. Karoo mit einem Buch abdecken: nach der Zeit „Dunkel für“ steht dort **Nacht**.
4. Buch weg oder mit einer Taschenlampe anleuchten: nach der Zeit „Hell für“ steht dort **Tag**.
5. Mit gestarteter Fahrt (oder Simulation „Fahrt“ und Tag/Nacht auf „Echt“) schalten die Lichter mit.

## Stromverbrauch

Der Sensor läuft nur, wenn der Tunnelmodus an ist **und** eine Fahrt läuft oder die
Einstellungsseite offen ist. Der Lichtsensor ist ohnehin für die automatische Bildschirmhelligkeit
aktiv. Die Prüfung alle 250 ms ist ein Vergleich weniger Zahlen und fällt neben Display und GPS
nicht ins Gewicht.
