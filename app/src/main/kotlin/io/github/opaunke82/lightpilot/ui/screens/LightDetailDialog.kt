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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.opaunke82.lightpilot.DiscoveredLight
import io.github.opaunke82.lightpilot.batteryText
import io.github.opaunke82.lightpilot.connectionLabel
import io.github.opaunke82.lightpilot.data.LightModeOption
import io.github.opaunke82.lightpilot.data.LightProtocol
import io.github.opaunke82.lightpilot.data.LightRole
import io.github.opaunke82.lightpilot.data.LightSetup
import io.github.opaunke82.lightpilot.data.modeProviderFor

private const val NO_SAVER = "__none__"

@Composable
fun LightDetailDialog(
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

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        dismissButton = if (setup != null) {
            { TextButton(onClick = { confirmForget = true }) { Text("Entfernen", color = MaterialTheme.colorScheme.error) } }
        } else {
            null
        },
        title = { Text(light.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val protocol = if (light.protocol == LightProtocol.BLE) "Bluetooth" else "ANT+"
                Text(
                    listOfNotNull(
                        light.manufacturer,
                        protocol,
                        light.connectionLabel(),
                        batteryText(light.protocol, light.batteryPercent),
                    ).joinToString(" · "),
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

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ModeRow("Tag", setup.dayMode, modes, tester) {
                        onUpdate(setup.copy(dayMode = it))
                    }
                    ModeRow("Nacht", setup.nightMode, modes, tester) {
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
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Sparmodus bei niedrigem Akku", style = MaterialTheme.typography.labelMedium)
                    val saverOptions = listOf(LightModeOption(NO_SAVER, "unverändert")) + modes
                    ModeRow("Tag", setup.saverDayMode ?: NO_SAVER, saverOptions, null) {
                        onUpdate(setup.copy(saverDayMode = it.takeIf { m -> m != NO_SAVER }))
                    }
                    ModeRow("Nacht", setup.saverNightMode ?: NO_SAVER, saverOptions, null) {
                        onUpdate(setup.copy(saverNightMode = it.takeIf { m -> m != NO_SAVER }))
                    }

                    if (setup.role == LightRole.FRONT) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text("Stufen für das Frontlicht-Datenfeld", style = MaterialTheme.typography.labelMedium)
                        modes.filter { it.id != "OFF" }.forEach { option ->
                            val checked = option.id in setup.fieldModes
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        // Keep the order of the mode list.
                                        val selected = modes.map { it.id }
                                            .filter { id -> if (id == option.id) on else id in setup.fieldModes }
                                        onUpdate(setup.copy(fieldModes = selected))
                                    },
                                )
                                Text(option.displayName)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ModeRow(
    label: String,
    selected: String,
    modes: List<LightModeOption>,
    onTest: ((String) -> Unit)?,
    onSelected: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        InlineDropdown(
            label = label,
            selectedId = selected,
            options = modes.map { it.id to it.displayName },
            onSelected = onSelected,
            modifier = Modifier.weight(1f),
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
private fun InlineDropdown(
    label: String,
    selectedId: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val name = options.find { it.first == selectedId }?.second ?: selectedId
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
