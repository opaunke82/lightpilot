// Derived from KarooFirefly (https://github.com/derstrassi/karoofirefly),
// Copyright (c) 2026 derstrassi, MIT License. Adapted for LightPilot.

package io.github.opaunke82.lightpilot.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.opaunke82.lightpilot.ble.magicshineModeName
import io.github.opaunke82.lightpilot.data.isTwoGroupMagicshine
import io.github.opaunke82.lightpilot.DiscoveredLight
import io.github.opaunke82.lightpilot.batteryText
import io.github.opaunke82.lightpilot.connectionLabel
import io.github.opaunke82.lightpilot.data.LightModeOption
import io.github.opaunke82.lightpilot.data.LightProtocol
import io.github.opaunke82.lightpilot.data.LightRole
import io.github.opaunke82.lightpilot.data.LightSetup
import io.github.opaunke82.lightpilot.data.modeProviderFor

private const val NO_SAVER = "__none__"

/** Settings of one light, shown unfolded below its row on the "Lichter" page. */
@Composable
fun LightDetailPanel(
    light: DiscoveredLight,
    setup: LightSetup?,
    onUpdate: (LightSetup?) -> Unit,
    onTestMode: (String, String) -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    val modes = modeProviderFor(light.protocol, light.id).availableModes()
    var confirmForget by remember { mutableStateOf(false) }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Licht entfernen") },
            text = { Text("${light.name} aus LightPilot entfernen? Die Einstellungen dieses Lichts gehen verloren.") },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; onForget(); onDismiss() }) {
                    Text("Entfernen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Abbrechen") } },
        )
    }

    run {
        run {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 8.dp)) {
                val protocol = if (light.protocol == LightProtocol.BLE) "Bluetooth" else "ANT+"
                Text(
                    withBatteryColor(
                        listOfNotNull(light.manufacturer, protocol, light.connectionLabel()).joinToString(" · "),
                        light.protocol,
                        light.batteryPercent,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                InlineDropdown(
                    label = "Rolle",
                    selectedId = setup?.role?.name ?: "NONE",
                    options = listOf("FRONT" to "Frontlicht", "REAR" to "Rücklicht", "NONE" to "nicht steuern"),
                    onSelected = { id ->
                        val role = when (id) {
                            "FRONT" -> LightRole.FRONT
                            "REAR" -> LightRole.REAR
                            else -> null
                        }
                        val updated = if (role == null) {
                            null
                        } else if (setup == null) {
                            LightSetup.defaultsFor(light.id, light.name, light.protocol, role)
                        } else {
                            setup.copy(role = role, radarWarning = role == LightRole.REAR && setup.radarWarning)
                        }
                        onUpdate(updated)
                    },
                )

                if (setup != null) {
                    val tester: ((String) -> Unit)? = { m: String -> onTestMode(light.id, m) }.takeIf { light.connected }
                    // Low/high beam lamp (Magicshine EVO): any brightness via slider as well.
                    val beams = setup.protocol == LightProtocol.BLE && isTwoGroupMagicshine(setup.deviceName)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ModeRow("Tag", setup.dayMode, modes, tester, beams) {
                        onUpdate(setup.copy(dayMode = it))
                    }
                    ModeRow("Nacht", setup.nightMode, modes, tester, beams) {
                        onUpdate(setup.copy(nightMode = it))
                    }

                    if (setup.role == LightRole.REAR) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Radar-Warnung")
                                Text(
                                    "Blinkt, wenn ein Fahrzeug kommt",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = setup.radarWarning, onCheckedChange = { onUpdate(setup.copy(radarWarning = it)) })
                        }
                        if (setup.radarWarning) {
                            ModeRow("Warnung", setup.warnMode, modes, tester) {
                                onUpdate(setup.copy(warnMode = it))
                            }
                            ModeRow("Warnung Peloton", setup.pelotonWarnMode, modes, tester) {
                                onUpdate(setup.copy(pelotonWarnMode = it))
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Sparmodus bei niedrigem Akku", style = MaterialTheme.typography.labelMedium)
                    val saverOptions = listOf(LightModeOption(NO_SAVER, "unverändert")) + modes
                    ModeRow("Tag", setup.saverDayMode ?: NO_SAVER, saverOptions, null, beams) {
                        onUpdate(setup.copy(saverDayMode = it.takeIf { m -> m != NO_SAVER }))
                    }
                    ModeRow("Nacht", setup.saverNightMode ?: NO_SAVER, saverOptions, null, beams) {
                        onUpdate(setup.copy(saverNightMode = it.takeIf { m -> m != NO_SAVER }))
                    }

                    if (setup.role == LightRole.FRONT) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text("Stufen für das Frontlicht-Datenfeld", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "„Aus“ angehakt: Aus kommt auch beim schnellen Durchtippen. Nach einer Pause schaltet " +
                                "der erste Tipp ohnehin aus (Zeit unter „Lichter“ einstellbar).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (beams) {
                            BeamSteps("Abblendlicht", "LOW_", setup.fieldModes) { onUpdate(setup.copy(fieldModes = it)) }
                            BeamSteps("Fernlicht", "HIGH_", setup.fieldModes) { onUpdate(setup.copy(fieldModes = it)) }
                        }
                        // Order of the cycle: the light's modes from low to high, "Aus" last.
                        val cycleOrder = if (beams) {
                            listOf(LightModeOption("OFF", "Aus"))
                        } else {
                            modes.filter { it.id != "OFF" } + LightModeOption("OFF", "Aus")
                        }
                        cycleOrder.forEach { option ->
                            val checked = option.id in setup.fieldModes
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        val selected = if (beams) {
                                            orderedFieldModes(setup.fieldModes.filter { it != option.id } + listOfNotNull(option.id.takeIf { on }))
                                        } else {
                                            cycleOrder.map { it.id }
                                                .filter { id -> if (id == option.id) on else id in setup.fieldModes }
                                        }
                                        onUpdate(setup.copy(fieldModes = selected))
                                    },
                                )
                                Text(option.displayName)
                            }
                        }
                    }
                }
                if (setup != null) {
                    OutlinedButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("Licht entfernen", color = MaterialTheme.colorScheme.error, maxLines = 1)
                    }
                }
            }
        }
    }
}

private const val CUSTOM = "__custom__"

/** Field steps: low beam ascending, high beam ascending, other modes, "Aus" last. */
private fun orderedFieldModes(modes: List<String>): List<String> {
    fun rank(m: String) = when {
        m.startsWith("LOW_") -> 0
        m.startsWith("HIGH_") -> 1
        m == "OFF" -> 3
        else -> 2
    }
    return modes.distinct().sortedWith(compareBy({ rank(it) }, { it.substringAfter("_").toIntOrNull() ?: 0 }))
}

/** Toggle buttons 10 … 100 % for one beam of the front field cycle. */
@Composable
private fun BeamSteps(title: String, prefix: String, fieldModes: List<String>, onChange: (List<String>) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    (10..100 step 10).chunked(5).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { pct ->
                val id = prefix + pct
                val on = id in fieldModes
                val toggle = { onChange(orderedFieldModes(if (on) fieldModes - id else fieldModes + id)) }
                val pad = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                if (on) {
                    Button(onClick = toggle, modifier = Modifier.weight(1f), contentPadding = pad) { Text("$pct", fontSize = 12.sp) }
                } else {
                    OutlinedButton(onClick = toggle, modifier = Modifier.weight(1f), contentPadding = pad) { Text("$pct", fontSize = 12.sp) }
                }
            }
        }
    }
}

/** Free brightness for low or high beam (1 … 100 %). */
@Composable
private fun CustomValueDialog(initial: String, onDone: (String?) -> Unit) {
    var high by remember { mutableStateOf(initial.startsWith("HIGH_")) }
    var pct by remember { mutableFloatStateOf((initial.substringAfter("_").toIntOrNull() ?: 50).coerceIn(1, 100).toFloat()) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Eigener Wert") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val w = Modifier.weight(1f)
                    if (!high) Button(onClick = {}, modifier = w) { Text("Abblend") }
                    else OutlinedButton(onClick = { high = false }, modifier = w) { Text("Abblend") }
                    if (high) Button(onClick = {}, modifier = w) { Text("Fernlicht") }
                    else OutlinedButton(onClick = { high = true }, modifier = w) { Text("Fernlicht") }
                }
                Text("${pct.toInt()} %", modifier = Modifier.padding(top = 12.dp))
                Slider(value = pct, onValueChange = { pct = it }, valueRange = 1f..100f, steps = 98)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone((if (high) "HIGH_" else "LOW_") + pct.toInt()) }) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Abbrechen") } },
    )
}

@Composable
private fun ModeRow(
    label: String,
    selected: String,
    modes: List<LightModeOption>,
    onTest: ((String) -> Unit)?,
    custom: Boolean = false,
    onSelected: (String) -> Unit,
) {
    var editCustom by remember { mutableStateOf(false) }
    if (editCustom) {
        CustomValueDialog(selected) { chosen ->
            editCustom = false
            if (chosen != null) onSelected(chosen)
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        InlineDropdown(
            label = label,
            selectedId = selected,
            options = modes.map { it.id to it.displayName } + listOfNotNull(("$CUSTOM" to "Eigener Wert …").takeIf { custom }),
            onSelected = { id -> if (id == CUSTOM) editCustom = true else onSelected(id) },
            modifier = Modifier.weight(1f),
            fallbackName = { magicshineModeName(it) },
        )
        if (onTest != null) {
            IconButton(onClick = { onTest(selected) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "$label testen",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun InlineDropdown(
    label: String,
    selectedId: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    fallbackName: (String) -> String = { it },
) {
    var expanded by remember { mutableStateOf(false) }
    val name = options.find { it.first == selectedId }?.second ?: fallbackName(selectedId)
    Row(
        modifier = modifier.clickable { expanded = true }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelected(id); expanded = false })
            }
        }
    }
}
