package io.github.opaunke82.lightpilot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.opaunke82.lightpilot.log.EventLog
import kotlinx.coroutines.delay

/** Filters by the text a log line contains (the tag is part of the message). */
private enum class LogFilter(val label: String, val match: String?) {
    ALL("Alle", null),
    ENGINE("Logik", "Engine"),
    BLE("BLE", "Magicshine"),
    SYSTEM("System", "LightPilot"),
}

/**
 * Full-screen log window. Costs nothing while closed; while open it takes a copy of
 * the newest lines once per second, and only if something new was logged.
 */
@Composable
fun LogPanel(height: Dp) {
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var paused by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(EventLog.snapshot(MAX_SHOWN)) }
    val listState = rememberLazyListState()

    LaunchedEffect(paused) {
        var seen = -1L
        while (!paused) {
            val version = EventLog.version
            if (version != seen) {
                seen = version
                lines = EventLog.snapshot(MAX_SHOWN)
            }
            delay(1_000)
        }
    }

    val shown = remember(lines, filter) {
        val m = filter.match
        if (m == null) lines else lines.filter { it.contains(m) }
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LogFilter.entries.forEach { f ->
                SmallChoice(f.label, filter == f, Modifier.weight(1f)) { filter = f }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${shown.size} Zeilen, neueste oben" + if (paused) " · angehalten" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            TextButton(onClick = { paused = !paused }) { Text(if (paused) "Weiter" else "Anhalten", maxLines = 1) }
        }
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(height)) {
            items(shown) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SmallChoice(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier, contentPadding = TIGHT) { Text(text, fontSize = 13.sp, maxLines = 1, softWrap = false) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = TIGHT) { Text(text, fontSize = 13.sp, maxLines = 1, softWrap = false) }
    }
}

private const val MAX_SHOWN = 1_000
private val TIGHT = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
