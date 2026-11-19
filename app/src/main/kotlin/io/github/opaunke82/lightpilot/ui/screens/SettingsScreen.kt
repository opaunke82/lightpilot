// Layout and light list derived from KarooFirefly (https://github.com/derstrassi/karoofirefly),
// Copyright (c) 2026 derstrassi, MIT License. Adapted for LightPilot.

package io.github.opaunke82.lightpilot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.opaunke82.lightpilot.BuildConfig
import io.github.opaunke82.lightpilot.DiscoveredLight
import io.github.opaunke82.lightpilot.batteryText
import io.github.opaunke82.lightpilot.connectionLabel
import io.github.opaunke82.lightpilot.core.ManualMode
import io.github.opaunke82.lightpilot.core.Reason
import io.github.opaunke82.lightpilot.core.Zone
import io.github.opaunke82.lightpilot.core.RidePhase
import io.github.opaunke82.lightpilot.data.AppSettings
import io.github.opaunke82.lightpilot.data.LightProtocol
import io.github.opaunke82.lightpilot.data.LightRole
import io.github.opaunke82.lightpilot.data.modeDisplayName
import io.github.opaunke82.lightpilot.datatypes.manualLabel
import io.github.opaunke82.lightpilot.datatypes.zoneLabel
import io.github.opaunke82.lightpilot.engine.EngineStatus
import java.util.Calendar

class SettingsActions(
    val save: (AppSettings) -> Unit,
    val testMode: (String, String) -> Unit,
    val forgetLight: (DiscoveredLight) -> Unit,
    val rescanBle: () -> Unit,
    val setSimulation: (Boolean) -> Unit,
    val setSimulatedRide: (Boolean) -> Unit,
    val simulateVehicle: () -> Unit,
    val simulatePause: (Boolean) -> Unit,
    val simulateResume: () -> Unit,
    val manualCycle: () -> Unit,
    val setManual: (ManualMode) -> Unit,
    val simulateZone: (Zone?) -> Unit,
    val frontCycle: () -> Unit,
    val exportLog: () -> Unit,
    val factoryReset: () -> Unit,
)

fun reasonLabel(reason: Reason): String = when (reason) {
    Reason.NO_RIDE -> "keine Fahrt"
    Reason.RIDE_ENDED -> "Fahrt beendet"
    Reason.MANUAL_PAUSE -> "Pause-Taste"
    Reason.HARD_OFF -> "Hart aus"
    Reason.LONG_AUTO_PAUSE -> "lange Auto-Pause"
    Reason.RADAR -> "Radar-Warnung"
    Reason.PINNED_EXTERNAL -> "von außen geändert"
    Reason.PINNED_FIELD -> "über Datenfeld gewählt"
    Reason.MANUAL_ON -> "manuell an"
    Reason.MANUAL_OFF -> "manuell aus"
    Reason.ZONE -> "Tag/Nacht"
    Reason.ZONE_SAVER -> "Sparmodus"
}

private fun phaseLabel(phase: RidePhase): String = when (phase) {
    RidePhase.IDLE -> "keine Fahrt"
    RidePhase.RECORDING -> "Fahrt läuft"
    RidePhase.PAUSED_AUTO -> "Auto-Pause"
    RidePhase.PAUSED_MANUAL -> "Pause (Taste)"
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    discoveredLights: List<DiscoveredLight>,
    bleScanning: Boolean,
    status: EngineStatus,
    currentLux: Float,
    sunrise: Calendar?,
    sunset: Calendar?,
    sunSource: String,
    logLines: List<String>,
    exportInfo: String?,
    extensionReady: Boolean,
    actions: SettingsActions,
) {
    var selectedLight by remember { mutableStateOf<DiscoveredLight?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("LightPilot", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Lichtsteuerung nach Tag/Nacht und Radar",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!extensionReady) {
            Spacer(Modifier.height(8.dp))
            Text("Warte auf den Karoo-Dienst …", color = MaterialTheme.colorScheme.error)
        }

        Section("Status") {
            StatusBlock(status)
        }

        Section("Lichter") {
            LightList(settings, discoveredLights, status) { selectedLight = it }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = actions.rescanBle, modifier = Modifier.fillMaxWidth()) {
                Text("Nach Bluetooth-Lichtern suchen")
            }
            Note(
                if (bleScanning) {
                    "Bluetooth-Suche läuft, solange diese Seite offen ist. Die Magicshine muss eingeschaltet " +
                        "und darf nicht mit einem Handy (Magicshine-App) verbunden sein."
                } else {
                    "Bluetooth-Suche aus."
                },
            )
        }

        selectedLight?.let { light ->
            LightDetailDialog(
                light = light,
                setup = settings.lights.find { it.deviceId == light.id },
                onUpdate = { setup -> actions.save(settings.withLight(setup, light.id)) },
                onTestMode = actions.testMode,
                onForget = { actions.forgetLight(light) },
                onDismiss = { selectedLight = null },
            )
        }

        Section("Tag und Nacht") {
            DayNightBlock(settings, currentLux, sunrise, sunset, sunSource, actions.save)
        }

        Section("Radar") {
            IntSlider(
                label = "Haltezeit nach dem letzten Fahrzeug",
                value = settings.radarHoldSeconds, range = 0..10, unit = "s",
            ) { actions.save(settings.copy(radarHoldSeconds = it)) }
            IntSlider(
                label = "Warnung endet ohne Radardaten nach",
                value = settings.radarWatchdogSeconds, range = 2..30, unit = "s",
            ) { actions.save(settings.copy(radarWatchdogSeconds = it)) }
            SwitchRow("Erst bei schnellem Fahrzeug warnen", settings.radarMinThreatLevel >= 2) {
                actions.save(settings.copy(radarMinThreatLevel = if (it) 2 else 1))
            }
        }

        Section("Pause und Fahrtende") {
            IntSlider(
                label = "Auto-Pause: Licht aus nach",
                value = settings.autoPauseOffMinutes, range = 0..5, unit = "min",
            ) { actions.save(settings.copy(autoPauseOffMinutes = it)) }
            Note("Pause-Taste am Karoo: Licht und Radar-Warnung sofort aus.")
            SwitchRow("Licht aus bei Fahrtende", settings.offAfterRide) {
                actions.save(settings.copy(offAfterRide = it))
            }
            SwitchRow("Hinweis bei Tag/Nacht-Wechsel", settings.zoneNotifications) {
                actions.save(settings.copy(zoneNotifications = it))
            }
        }

        Section("Akku") {
            SwitchRow("Sparmodus bei niedrigem Akku", settings.batterySaverEnabled) {
                actions.save(settings.copy(batterySaverEnabled = it))
            }
            if (settings.batterySaverEnabled) {
                IntSlider(
                    label = "Sparmodus ab",
                    value = settings.batterySaverThresholdPct, range = 5..50, unit = "%",
                ) { actions.save(settings.copy(batterySaverThresholdPct = it)) }
                Note("Das Varia meldet den Akku nur grob (gut / ok / niedrig / kritisch); „niedrig“ zählt als 20 %.")
            }
        }

        Section("Test und Diagnose") {
            TestBlock(status, actions)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = actions.exportLog) { Text("Protokoll exportieren") }
            exportInfo?.let { Note("Gespeichert: $it") }
            Spacer(Modifier.height(8.dp))
            Text("Letzte Einträge", style = MaterialTheme.typography.labelMedium)
            logLines.take(15).forEach {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
            }
        }

        Section("Zurücksetzen") {
            ResetBlock(actions.factoryReset)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) · MIT-Lizenz · " +
                "Lichtansteuerung aus KarooFirefly (derstrassi)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    content()
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, unit: String, onDone: (Int) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Text("$label: ${current.toInt()} $unit")
    Slider(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { onDone(current.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
    )
}

@Composable
private fun StatusBlock(s: EngineStatus) {
    val radar = when {
        s.radarWarning -> "Fahrzeug erkannt"
        !s.radarSeen -> "keine Daten"
        s.radarStale -> "keine aktuellen Daten"
        else -> "frei"
    }
    val pause = s.autoPauseOffInSec?.let { sec ->
        if (sec > 0) " (Licht aus in %d:%02d)".format(sec / 60, sec % 60) else " (Licht aus)"
    } ?: ""
    val zone = zoneLabel(s.zone) + if (s.simZone != null) " (erzwungen)" else ""
    Text("Fahrt: ${phaseLabel(s.phase)}$pause")
    Text("Tag/Nacht: $zone · Steuerung: ${manualLabel(s.manual)}" + if (s.simulation) " · SIMULATION" else "")
    Text("Radar: $radar", color = if (s.radarWarning) Color(0xFFB71C1C) else Color.Unspecified)
    s.lights.forEach { l ->
        val mode = modeDisplayName(l.setup.protocol, l.setup.deviceId, l.desired)
        val conn = if (l.connected) "" else " · getrennt"
        val check = when {
            l.desired == null -> ""
            l.setup.protocol == LightProtocol.BLE -> " · gesendet"
            l.confirmed -> " · bestätigt"
            else -> " · unbestätigt"
        }
        Note("${l.setup.deviceName}: $mode (${reasonLabel(l.reason)})$check$conn")
    }
}

@Composable
private fun LightList(
    settings: AppSettings,
    discovered: List<DiscoveredLight>,
    status: EngineStatus,
    onSelect: (DiscoveredLight) -> Unit,
) {
    val ids = discovered.map { it.id }.toSet()
    val missing = settings.lights.filter { it.deviceId !in ids }.map {
        DiscoveredLight(it.deviceId, it.deviceName, null, it.protocol, connected = false)
    }
    val all = discovered + missing
    if (all.isEmpty()) {
        Note("Keine Lichter gefunden. ANT+-Lichter in den Sensoren des Karoo koppeln (Auto Control aus); Magicshine einschalten.")
        return
    }
    all.forEach { light ->
        val setup = settings.lights.find { it.deviceId == light.id }
        val role = when (setup?.role) {
            LightRole.FRONT -> "Front"
            LightRole.REAR -> "Rück"
            null -> "antippen zum Einrichten"
        }
        val protocol = if (light.protocol == LightProtocol.BLE) "Bluetooth" else "ANT+"
        val info = listOfNotNull(
            protocol,
            light.manufacturer,
            light.connectionLabel(),
            batteryText(light.protocol, light.batteryPercent),
        ).joinToString(" · ")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (setup == null) Color(0xFFFFF3C4) else Color.Transparent, MaterialTheme.shapes.small)
                .clickable { onSelect(light) }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(light.name)
                Note(info)
            }
            Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (status.lights.isEmpty()) Note("Noch kein Licht eingerichtet.")
}

@Composable
private fun DayNightBlock(
    settings: AppSettings,
    lux: Float,
    sunrise: Calendar?,
    sunset: Calendar?,
    sunSource: String,
    save: (AppSettings) -> Unit,
) {
    fun shifted(c: Calendar?, minutes: Int): String = c?.let {
        val t = (it.clone() as Calendar).apply { add(Calendar.MINUTE, minutes) }
        "%02d:%02d".format(t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE))
    } ?: "–"

    SwitchRow("Nach Sonnenauf- und -untergang", settings.useTimeBased) { save(settings.copy(useTimeBased = it)) }
    if (settings.useTimeBased) {
        Note("Position für die Sonnenzeiten: $sunSource")
        IntSlider("Tag beginnt ab Sonnenaufgang +", settings.dawnOffsetMinutes, -180..180, "min") {
            save(settings.copy(dawnOffsetMinutes = it))
        }
        Note("Tag ab ${shifted(sunrise, settings.dawnOffsetMinutes)}")
        IntSlider("Nacht beginnt ab Sonnenuntergang +", settings.duskOffsetMinutes, -180..180, "min") {
            save(settings.copy(duskOffsetMinutes = it))
        }
        Note("Nacht ab ${shifted(sunset, settings.duskOffsetMinutes)}")
    }
    SwitchRow("Umgebungslichtsensor (Tunnel, Wald)", settings.useAmbientLight) {
        save(settings.copy(useAmbientLight = it))
    }
    if (settings.useAmbientLight) {
        Note("Aktuell: %.0f lx. Mit Sonnenzeiten zusammen schaltet der Sensor nur auf Nacht.".format(lux))
        IntSlider("Nacht unter", settings.ambientNightThresholdLux, 10..500, "lx") {
            save(settings.copy(ambientNightThresholdLux = it))
        }
    }
}

@Composable
private fun ChoiceButton(text: String, selected: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text, fontSize = 13.sp) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text, fontSize = 13.sp) }
    }
}

@Composable
private fun SimGroup(title: String, hint: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(title, style = MaterialTheme.typography.labelLarge)
    Note(hint)
    content()
}

@Composable
private fun TestBlock(status: EngineStatus, actions: SettingsActions) {
    SwitchRow("Simulationsmodus", status.simulation) { actions.setSimulation(it) }
    if (!status.simulation) {
        Note("Prüft die Logik am Tisch: Fahrt, Pausen, Tag/Nacht, Fahrzeug und Bedienung. Das echte Radar wird dabei ignoriert.")
        return
    }
    val riding = status.phase != RidePhase.IDLE
    val wide = Modifier.fillMaxWidth()

    // Live state, so the effect of every button is visible right here.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp),
    ) {
        StatusBlock(status)
    }

    SimGroup("1. Fahrt", "Ohne laufende Fahrt steuert LightPilot nichts.") {
        ChoiceButton(if (riding) "Fahrt beenden" else "Fahrt starten", selected = riding, modifier = wide) {
            actions.setSimulatedRide(!riding)
        }
    }

    SimGroup("2. Tag oder Nacht", "Erzwingt die Tageszeit, um z. B. tagsüber den Nachtmodus zu prüfen.") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = Modifier.weight(1f)
            ChoiceButton("Echt", status.simZone == null, modifier = w) { actions.simulateZone(null) }
            ChoiceButton("Tag", status.simZone == Zone.DAY, modifier = w) { actions.simulateZone(Zone.DAY) }
            ChoiceButton("Nacht", status.simZone == Zone.NIGHT, modifier = w) { actions.simulateZone(Zone.NIGHT) }
        }
    }

    SimGroup("3. Radar", "Meldet 5 s lang ein Fahrzeug; danach läuft die Haltezeit.") {
        ChoiceButton(
            if (status.radarWarning) "Fahrzeug erkannt …" else "Fahrzeug 5 s",
            selected = status.radarWarning, enabled = riding, modifier = wide,
        ) { actions.simulateVehicle() }
    }

    SimGroup(
        "4. Steuerung (wie Datenfeld)",
        "Das Datenfeld schaltet reihum Automatik → An → Aus → Hart aus. Hier direkt wählbar. " +
            "Ein Wechsel von Tag/Nacht setzt wieder auf Automatik.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = Modifier.weight(1f)
            ChoiceButton("Auto", status.manual == ManualMode.AUTO, riding, w) { actions.setManual(ManualMode.AUTO) }
            ChoiceButton("An", status.manual == ManualMode.ON, riding, w) { actions.setManual(ManualMode.ON) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = Modifier.weight(1f)
            ChoiceButton("Aus", status.manual == ManualMode.OFF, riding, w) { actions.setManual(ManualMode.OFF) }
            ChoiceButton("Hart aus", status.manual == ManualMode.HARD_OFF, riding, w) { actions.setManual(ManualMode.HARD_OFF) }
        }
        ChoiceButton("Frontlicht: nächste Stufe", selected = false, enabled = riding, modifier = wide) { actions.frontCycle() }
    }

    SimGroup(
        "5. Pause",
        "Pause-Taste: Licht und Radar-Warnung sofort aus. Auto-Pause: erst nach der eingestellten Zeit " +
            "(Abschnitt „Pause und Fahrtende“), Countdown oben im Status.",
    ) {
        val paused = status.phase == RidePhase.PAUSED_MANUAL || status.phase == RidePhase.PAUSED_AUTO
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = Modifier.weight(1f)
            ChoiceButton("Pause-Taste", status.phase == RidePhase.PAUSED_MANUAL, riding, w) { actions.simulatePause(true) }
            ChoiceButton("Auto-Pause", status.phase == RidePhase.PAUSED_AUTO, riding, w) { actions.simulatePause(false) }
        }
        ChoiceButton("Weiterfahren", selected = false, enabled = paused, modifier = wide) { actions.simulateResume() }
    }
}

@Composable
private fun ResetBlock(onReset: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Note("Setzt LightPilot komplett auf den Auslieferungszustand: Lichter-Zuordnung, alle Einstellungen, gespeicherte Position und Protokoll.")
    OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Werkseinstellungen", color = MaterialTheme.colorScheme.error)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Alles zurücksetzen?") },
            text = {
                Text(
                    "Alle Lichter müssen danach neu eingerichtet werden. Die Kopplung der Lichter " +
                        "im Karoo bleibt erhalten. Das lässt sich nicht rückgängig machen.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; onReset() }) {
                    Text("Zurücksetzen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
        )
    }
}
