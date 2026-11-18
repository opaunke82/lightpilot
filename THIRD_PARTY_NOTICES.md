# Fremdcode und Lizenzen

LightPilot steht unter der MIT-Lizenz (siehe `LICENSE`). Teile des Codes stammen aus
folgenden Projekten, ebenfalls MIT-lizenziert. Die betroffenen Dateien tragen einen
Herkunftshinweis im Kopf.

| Projekt | Übernommen | Lizenz |
| --- | --- | --- |
| [KarooFirefly](https://github.com/derstrassi/karoofirefly) | Lichtansteuerung über den Karoo-SensorService (`karoo/KarooLightControl.kt`), Magicshine-Bluetooth (`ble/`), Sonnenzeiten und Lichtsensor (`engine/TimeBasedController.kt`, `engine/AmbientLightSensor.kt`), Aufbau von Einstellungen und Build | MIT, Copyright (c) 2026 derstrassi |
| [LightONKaroo](https://github.com/JaJaJim/LightONKaroo) | Tippen auf Datenfelder per PendingIntent | MIT, Copyright (c) 2026 Dennis Straßer (derstrassi) |
| [karoo-ext](https://github.com/hammerheadnav/karoo-ext) | SDK als Abhängigkeit, nicht im Repository | Apache 2.0, Copyright (c) 2025 SRAM LLC |

## Lizenztext KarooFirefly

```
MIT License

Copyright (c) 2026 derstrassi

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
