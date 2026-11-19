# Magicshine EVO 1700 – Bluetooth-Protokoll

Ausgewertet aus einem Bluetooth-HCI-Mitschnitt der Magicshine-App (Samsung S8, Android 9)
mit einer EVO 1700 (`M2-B0 EVO_1700`), 19.11.2026. Die EVO 1700SD (`M2-B0 EVO_1700SD_…`)
verhält sich bei den bisherigen LightPilot-Befehlen gleich.

## Verbindung

- GATT-Service `FFE1`, Charakteristik `FFE0` (Handle 0x0E), Benachrichtigungen über CCCD 0x0F.
- Alle Befehle als Write Request, jeder Rahmen `DE <Länge> <Typ> <Status> <Inhalt…> <Prüfsumme> ED`.
- Prüfsumme: XOR über alle Bytes von `<Länge>` bis zum letzten Inhaltsbyte.

## Licht schalten (Typ `A2`, Länge 0x14)

```
DE 14 A2 01  II  E1 M1 B1  E2 M2 B2  00 00 00 00 00 00  BB  CS  ED
```

| Feld | Bedeutung |
| --- | --- |
| `II` | Platz der Stufe in der Liste der App (1–20). Gruppe 1 belegt in der App 01, 02, 04–0B, Gruppe 2 03, 0C–14 |
| `E1 M1 B1` | Lichtgruppe 1: an (01) / aus (00), Modus, Helligkeit in % (hex) |
| `E2 M2 B2` | Lichtgruppe 2: an (01) / aus (00), Modus, Helligkeit in % (hex) |
| `BB` | festes Kennzeichen |

Beispiele aus dem Mitschnitt:

| Befehl | Wirkung |
| --- | --- |
| `DE14A201 01 01010A 000000 000000000000 BB 07 ED` | Gruppe 1, Dauerlicht 10 % |
| `DE14A201 0B 010164 000000 000000000000 BB 63 ED` | Gruppe 1, Dauerlicht 100 % |
| `DE14A201 03 000000 01010A 000000000000 BB 05 ED` | Gruppe 2, Dauerlicht 10 % |
| `DE14A201 14 000000 010164 000000000000 BB 7C ED` | Gruppe 2, Dauerlicht 100 % |

- Helligkeit stufenlos: Beim Verschieben des Reglers schickt die App 3, 4, 5, 12, 33, 59 % usw.
- Modus: `01` Dauerlicht; `02` und `03` sind in der App beim Bearbeiten einer Stufe gewählt worden
  (Bedeutung Blinken/SOS noch zuzuordnen).
- Die Lampe bestätigt jeden Befehl mit `DE 07 B6 00 01 B0 ED`.
- Bisheriger LightPilot-Befehl (`DE14A201 02 00010A 01 MM VV …`) schaltet Gruppe 1 aus und
  **Gruppe 2** auf Modus `MM`, Helligkeit `VV`.
- Ein Aus-Befehl kommt in der App nicht vor (sie bietet kein Ausschalten an). LightPilot schickt
  Gruppe 1 mit Helligkeit 0 und Gruppe 2 aus; das funktioniert.

## Stufenliste schreiben (Typ `A2`, Länge 0x16)

Beim Verbinden und nach dem Bearbeiten schreibt die App alle 20 Stufen nacheinander, eingeleitet
von den Abfragen `AC` und `AB`. Für LightPilot nicht nötig.

## Abfragen

| Abfrage | Antwort | Inhalt |
| --- | --- | --- |
| `DE 06 A4 00 A2 ED` | `DE 13 B4 …` | Akku in %, 6. Inhaltsbyte (`64` = 100 %) |
| `DE 06 A1 00 A7 ED` | `DE 0D B1 00 00 11 17 03 TT 01 01 …` | `TT` = Temperatur in °C; der Rest bleibt bei jeder Stufe gleich |
| `DE 06 AD 00 AB ED` | `DE 13 BD 00 "EVO_1700 " …` | Modellname |
| `DE 06 AC 00 AA ED`, `DE 06 AB 00 AD ED` | `DE 08 BC/BB 00 00 FF …` | vor dem Schreiben der Stufenliste |

Die App fragt Akku und Status alle 20 s ab.

## Was die Lampe nicht meldet

- Tastendruck an der Lampe: keine Benachrichtigung.
- Aktuelle Stufe oder an/aus: steht in keiner Antwort. Die App kann nur annehmen, dass der letzte
  eigene Befehl noch gilt.
