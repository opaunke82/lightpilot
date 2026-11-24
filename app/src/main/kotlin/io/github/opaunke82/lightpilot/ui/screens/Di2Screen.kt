package io.github.opaunke82.lightpilot.ui.screens

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.opaunke82.lightpilot.data.AppSettings
import io.github.opaunke82.lightpilot.di2.Di2Action
import io.github.opaunke82.lightpilot.di2.Di2Press
import io.github.opaunke82.lightpilot.di2.Ki2Bridge
import io.github.opaunke82.lightpilot.di2.di2Key

fun ki2StateLabel(state: Ki2Bridge.State): String = when (state) {
    Ki2Bridge.State.OFF -> "aus"
    Ki2Bridge.State.NOT_INSTALLED -> "Ki2 ist nicht installiert"
    Ki2Bridge.State.CONNECTING -> "verbinde mit Ki2 …"
    Ki2Bridge.State.CONNECTED -> "mit Ki2 verbunden"
    Ki2Bridge.State.INCOMPATIBLE -> "Ki2-Version passt nicht (Schnittstelle geändert)"
}

/** Separate page: Shimano Di2 hood buttons through Ki2. */
@Composable
fun Di2Screen(
    settings: AppSettings,
    state: Ki2Bridge.State,
    lastButton: String?,
    save: (AppSettings) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Di2-Tasten (Ki2)", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onClose) { Text("Schließen") }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Di2-Tasten verwenden", modifier = Modifier.weight(1f))
                    Switch(checked = settings.di2Enabled, onCheckedChange = { save(settings.copy(di2Enabled = it)) })
                }
                Hint("Status: ${ki2StateLabel(state)}")
                if (settings.di2Enabled) {
                    Hint("Letzte Taste: ${lastButton ?: "–"}")
                }
                Spacer(Modifier.height(8.dp))
                Hint(
                    "Ki2 hält die Verbindung zur Di2, LightPilot hört nur mit. Welche Taste welchen D-Fly-Kanal " +
                        "sendet, legst du in der Shimano E-Tube-App fest. Drück eine Taste und lies oben ab, welcher " +
                        "Kanal ankommt. Damit eine Druckart nicht zusätzlich in Ki2 etwas auslöst, stell sie in Ki2 " +
                        "auf „None“ (z. B. Doppelt), und belege sie hier.",
                )
                if (settings.di2Enabled) {
                    (1..4).forEach { channel ->
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text("D-Fly Kanal $channel", style = MaterialTheme.typography.labelLarge)
                        Di2Press.entries.forEach { press ->
                            val key = di2Key(channel, press)
                            val current = settings.di2Actions[key] ?: Di2Action.NONE.name
                            InlineDropdown(
                                label = press.label,
                                selectedId = current,
                                options = Di2Action.entries.map { it.name to it.label },
                                onSelected = { id ->
                                    val map = if (id == Di2Action.NONE.name) settings.di2Actions - key
                                    else settings.di2Actions + (key to id)
                                    save(settings.copy(di2Actions = map))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
