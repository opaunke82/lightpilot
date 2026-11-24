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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.opaunke82.lightpilot.data.AppSettings
import kotlin.math.roundToInt

/** Separate page: tunnel mode (ambient light sensor) with thresholds and times. */
@Composable
fun TunnelScreen(
    settings: AppSettings,
    live: String,
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
                    Text("Tunnelmodus", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onClose) { Text("Schließen") }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tunnelmodus verwenden", modifier = Modifier.weight(1f))
                    Switch(checked = settings.tunnelEnabled, onCheckedChange = { save(settings.copy(tunnelEnabled = it)) })
                }
                Hint(
                    "Der Lichtsensor des Karoo schaltet bei Dunkelheit auf Nacht (Tunnel, dichter Wald). " +
                        "Zusammen mit den Sonnenzeiten kann er nur auf Nacht schalten, nie auf Tag.",
                )
                if (settings.tunnelEnabled) {
                    Hint("Jetzt: $live")
                    Spacer(Modifier.height(12.dp))
                    StepSlider("Licht an unter", settings.tunnelOnBelowLux, 10..2000, 10, "lx") {
                        val on = it
                        val off = maxOf(settings.tunnelOffAboveLux, on + 10)
                        save(settings.copy(tunnelOnBelowLux = on, tunnelOffAboveLux = off))
                    }
                    StepSlider("Licht aus über", settings.tunnelOffAboveLux, 20..5000, 10, "lx") {
                        val off = maxOf(it, settings.tunnelOnBelowLux + 10)
                        save(settings.copy(tunnelOffAboveLux = off))
                    }
                    StepSlider("Dunkel für", settings.tunnelOnDelaySeconds, 0..10, 1, "s") {
                        save(settings.copy(tunnelOnDelaySeconds = it))
                    }
                    StepSlider("Hell für", settings.tunnelOffDelaySeconds, 0..60, 1, "s") {
                        save(settings.copy(tunnelOffDelaySeconds = it))
                    }
                    Spacer(Modifier.height(8.dp))
                    Hint(
                        "Nacht, wenn es ${settings.tunnelOnDelaySeconds} s lang dunkler als ${settings.tunnelOnBelowLux} lx ist. " +
                            "Tag, wenn es ${settings.tunnelOffDelaySeconds} s lang heller als ${settings.tunnelOffAboveLux} lx ist. " +
                            "Dazwischen bleibt der Zustand (Hysterese). Richtwerte: beleuchteter Tunnel 50–300 lx, " +
                            "dichter Wald 500–2000 lx, bedeckter Tag über 1000 lx.",
                    )
                    Hint("Live-Anzeige auch unter „Test und Diagnose“.")
                }
            }
        }
    }
}

/** Slider with a step (e.g. 10 lx); saves when released. */
@Composable
private fun StepSlider(label: String, value: Int, range: IntRange, step: Int, unit: String, onDone: (Int) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    fun snap(v: Float) = ((v / step).roundToInt() * step).coerceIn(range.first, range.last)
    Text("$label: ${snap(current)} $unit")
    Slider(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { onDone(snap(current)) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
