// Layout and light list derived from KarooFirefly (https://github.com/derstrassi/karoofirefly),
// Copyright (c) 2026 derstrassi, MIT License. Adapted for LightPilot.

package io.github.opaunke82.lightpilot.ui.screens

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.opaunke82.lightpilot.BuildConfig
import io.github.opaunke82.lightpilot.DiscoveredLight
import io.github.opaunke82.lightpilot.batteryColor
import io.github.opaunke82.lightpilot.batteryText
import io.github.opaunke82.lightpilot.connectionLabel
import io.github.opaunke82.lightpilot.core.ManualMode
import io.github.opaunke82.lightpilot.core.Reason
import io.github.opaunke82.lightpilot.core.Zone
import io.github.opaunke82.lightpilot.core.RidePhase
import io.github.opaunke82.lightpilot.data.AppSettings
import io.github.opaunke82.lightpilot.di2.Ki2Bridge
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
    val beamToggle: () -> Unit,
    val exportLog: () -> Unit,
    val factoryReset: () -> Unit,
    val brakeRecording: (Boolean) -> Unit,
    val latencyTest: () -> Unit,
)

fun reasonLabel(reason: Reason): String = when (reason) {
    Reason.NO_RIDE -> "keine Fahrt"
    Reason.RIDE_ENDED -> "Fahrt beendet"
    Reason.MANUAL_PAUSE -> "Pause-Taste"
    Reason.HARD_OFF -> "Hart aus"
    Reason.LONG_AUTO_PAUSE -> "lange Auto-Pause"
    Reason.RADAR -> "Radar-Warnung"
    Reason.RADAR_PELOTON -> "Radar-Warnung (Peloton)"
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
    brakeDiag: String,
    ki2State: Ki2Bridge.State,
    di2LastButton: String?,
    status: EngineStatus,
    currentLux: Float,
    tunnelLive: String,
    sunrise: Calendar?,
    sunset: Calendar?,
    sunSource: String,
    exportInfo: String?,
    extensionReady: Boolean,
    actions: SettingsActions,
) {
    // Start view (status) or one open tab; remembered across app starts (user's wish).
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var tabName by rememberSaveable { mutableStateOf(prefs.getString(KEY_TAB, null)) }
    val tab = tabName?.let { name -> Tab.entries.firstOrNull { it.name == name } }
    fun setTab(t: Tab?) {
        tabName = t?.name
        prefs.edit().putString(KEY_TAB, t?.name).apply()
    }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val current = page?.let { name -> Page.entries.firstOrNull { it.name == name } }
    BackHandler(enabled = current != null || tab != null) {
        if (current != null) page = null else setTab(null)
    }

    if (current == null) {
        HomePage(
            settings, discoveredLights, status, ki2State, sunrise, sunset, extensionReady, tab,
            selectTab = { t -> setTab(if (t == tab) null else t) },
            open = { p -> setTab(p.tab); page = p.name },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header of the sub-page: back and title. Only one level below the start page.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { page = null }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(end = 12.dp))
            Text(current.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.slowMarquee())
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (current) {
                Page.LIGHTS -> LightsPage(settings, discoveredLights, status, bleScanning, actions)
                Page.DAY_NIGHT -> DayNightBlock(settings, currentLux, sunrise, sunset, sunSource, actions.save)
                Page.TUNNEL -> TunnelContent(settings, tunnelLive, actions.save)
                Page.RADAR -> RadarPage(settings, actions)
                Page.PAUSE -> PausePage(settings, actions)
                Page.BATTERY -> BatteryPage(settings, actions)
                Page.CONTROLS -> ControlsPage(settings, ki2State, di2LastButton, actions)
                Page.TEST -> {
                    TestBlock(status, settings.pelotonEnabled, actions)
                    Spacer(Modifier.height(12.dp))
                    Text("Tunnelmodus live", style = MaterialTheme.typography.labelLarge)
                    Note(if (settings.tunnelEnabled) tunnelLive else "Tunnelmodus ist aus.")
                }
                Page.DIAGNOSE -> DiagnosePage(brakeDiag, exportInfo, actions)
                Page.INFO -> InfoPage(actions)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val KEY_TAB = "tab"

/** Tabs of the start page; tapping the open tab again shows the status. */
private enum class Tab(val label: String) {
    LIGHT("Licht"),
    CONTROLS("Bedienung"),
    TOOLS("Werkzeuge"),
}

/** Entries of a tab; each opens exactly one page. */
private enum class Page(val title: String, val tab: Tab) {
    LIGHTS("Lichter", Tab.LIGHT),
    DAY_NIGHT("Tag & Nacht", Tab.LIGHT),
    TUNNEL("Tunnelmodus", Tab.LIGHT),
    RADAR("Radar", Tab.LIGHT),
    PAUSE("Pause & Fahrtende", Tab.LIGHT),
    BATTERY("Akku", Tab.LIGHT),
    CONTROLS("Datenfelder & Tasten", Tab.CONTROLS),
    TEST("Test & Simulation", Tab.TOOLS),
    DIAGNOSE("Diagnose", Tab.TOOLS),
    INFO("Info & Zurücksetzen", Tab.TOOLS),
}

/** Slow ticker for single-line texts; it only moves when the text does not fit. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.slowMarquee(): Modifier = basicMarquee(
    iterations = Int.MAX_VALUE,
    repeatDelayMillis = 2000,
    initialDelayMillis = 2000,
    velocity = 20.dp,
)

@Composable
private fun HomePage(
    settings: AppSettings,
    discovered: List<DiscoveredLight>,
    status: EngineStatus,
    ki2State: Ki2Bridge.State,
    sunrise: Calendar?,
    sunset: Calendar?,
    extensionReady: Boolean,
    tab: Tab?,
    selectTab: (Tab) -> Unit,
    open: (Page) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Text("LightPilot", style = MaterialTheme.typography.headlineSmall)
        if (!extensionReady) {
            Text("Warte auf den Karoo-Dienst …", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        TabBar(tab, selectTab)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            if (tab == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                        .padding(12.dp),
                ) {
                    StatusCard(status, settings, discovered) { open(Page.LIGHTS) }
                }
            } else {
                Page.entries.filter { it.tab == tab }.forEach { p ->
                    MenuRow(p.title, summaryOf(p, settings, discovered, status, ki2State, sunrise, sunset)) { open(p) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TabBar(active: Tab?, select: (Tab) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Tab.entries.forEach { t ->
            val on = t == active
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.shapes.small,
                    )
                    .clickable { select(t) }
                    .padding(vertical = 10.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label,
                    fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private fun summaryOf(
    p: Page,
    s: AppSettings,
    discovered: List<DiscoveredLight>,
    status: EngineStatus,
    ki2State: Ki2Bridge.State,
    sunrise: Calendar?,
    sunset: Calendar?,
): String = when (p) {
    Page.LIGHTS -> {
        val connected = status.lights.count { it.connected }
        when {
            s.lights.isNotEmpty() -> "${s.lights.size} eingerichtet · $connected verbunden"
            discovered.isEmpty() -> "keins gefunden"
            else -> "0 von ${discovered.size} eingerichtet"
        }
    }
    Page.DAY_NIGHT -> if (s.useTimeBased) {
        "Tag ab ${hhmm(sunrise, s.dawnOffsetMinutes)} · Nacht ab ${hhmm(sunset, s.duskOffsetMinutes)}"
    } else {
        "Sonnenzeiten aus"
    }
    Page.TUNNEL -> if (s.tunnelEnabled) "an · ${s.tunnelOnBelowLux} / ${s.tunnelOffAboveLux} lx" else "aus"
    Page.RADAR -> "Haltezeit ${s.radarHoldSeconds} s"
    Page.PAUSE -> if (s.autoPauseOffMinutes == 0) "Auto-Pause: Licht sofort aus" else "Auto-Pause: Licht aus nach ${s.autoPauseOffMinutes} min"
    Page.BATTERY -> if (s.batterySaverEnabled) "Sparmodus unter ${s.batterySaverThresholdPct} %" else "Sparmodus aus"
    Page.CONTROLS -> "Peloton ${if (s.pelotonEnabled) "an" else "aus"} · Di2 " +
        if (!s.di2Enabled) "aus" else if (ki2State == Ki2Bridge.State.CONNECTED) "verbunden" else "an"
    Page.TEST -> if (status.simulation) "Simulation läuft" else "Simulation aus"
    Page.DIAGNOSE -> "Protokoll · Bremslicht-Messung"
    Page.INFO -> "Version ${BuildConfig.VERSION_NAME}"
}

private fun hhmm(c: Calendar?, minutes: Int): String = c?.let {
    val t = (it.clone() as Calendar).apply { add(Calendar.MINUTE, minutes) }
    "%02d:%02d".format(t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE))
} ?: "–"

@Composable
private fun MenuRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.slowMarquee())
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.slowMarquee(),
            )
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp))
    }
    HorizontalDivider()
}

private val ROLE_WIDTH = 52.dp
private val NEW_LIGHT_BG = Color(0xFFFFF3C4)

@Composable
private fun StatusCard(s: EngineStatus, settings: AppSettings, discovered: List<DiscoveredLight>, openLights: () -> Unit) {
    val warnColor = Color(0xFFB71C1C)
    val radar = when {
        s.radarWarning -> "Fahrzeug!"
        !s.radarSeen -> "Radar –"
        s.radarStale -> "Radar alt"
        else -> "Radar frei"
    }
    val manual = if (s.manual == ManualMode.AUTO) "Auto" else manualLabel(s.manual)
    val zone = zoneLabel(s.zone) + if (s.simZone != null) " (Sim)" else ""
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$zone · $manual",
            style = MaterialTheme.typography.titleMedium,
            color = if (s.radarWarning) warnColor else Color.Unspecified,
            maxLines = 1,
            modifier = Modifier.weight(1f).slowMarquee(),
        )
        Text(
            radar,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (s.radarWarning) FontWeight.Bold else null,
            color = if (s.radarWarning) warnColor else Color.Unspecified,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    val pause = s.autoPauseOffInSec?.let { sec ->
        if (sec > 0) " · Licht aus in %d:%02d".format(sec / 60, sec % 60) else " · Licht aus"
    } ?: ""
    Text(
        phaseLabel(s.phase) + pause + if (s.simulation) " · SIM" else "",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        modifier = Modifier.slowMarquee(),
    )

    val configuredIds = settings.lights.map { it.deviceId }.toSet()
    val newCount = discovered.count { it.id !in configuredIds }
    if (s.lights.isNotEmpty() || newCount > 0 || settings.lights.isEmpty()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
    }
    s.lights.forEachIndexed { index, l ->
        val sameRole = s.lights.filter { it.setup.role == l.setup.role }
        val role = (if (l.setup.role == LightRole.FRONT) "Front" else "Rück") +
            if (sameRole.size > 1) " ${sameRole.indexOf(l) + 1}" else ""
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (l.connected) "●" else "○",
                style = MaterialTheme.typography.bodyMedium,
                color = if (l.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                role,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(ROLE_WIDTH),
            )
            Text(
                modeDisplayName(l.setup.protocol, l.setup.deviceId, l.desired),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).slowMarquee(),
            )
            BatteryBadge(l.setup.protocol, l.batteryPct)
        }
        if (index == s.lights.lastIndex && newCount > 0) Spacer(Modifier.height(4.dp))
    }
    when {
        newCount > 0 -> {
            val text = when {
                settings.lights.isEmpty() -> if (newCount == 1) "1 Licht gefunden" else "$newCount Lichter gefunden"
                else -> if (newCount == 1) "1 weiteres gefunden" else "$newCount weitere gefunden"
            }
            NewLightsRow("$text · einrichten ›", NEW_LIGHT_BG, openLights)
        }
        settings.lights.isEmpty() -> NewLightsRow("Kein Licht gefunden · Lichter ›", Color.Transparent, openLights)
    }
}

@Composable
private fun NewLightsRow(text: String, bg: Color, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = if (bg == Color.Transparent) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF3E2E00),
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .slowMarquee(),
    )
}

/** Battery as a coloured box: percent for Magicshine, class for the Varia. */
@Composable
private fun BatteryBadge(protocol: LightProtocol, pct: Int?) {
    val text = when {
        pct == null -> "–"
        protocol == LightProtocol.BLE -> "$pct %"
        pct >= 100 -> "neu"
        pct >= 80 -> "gut"
        pct >= 50 -> "ok"
        pct >= 20 -> "niedrig"
        else -> "kritisch"
    }
    val bg = batteryColor(protocol, pct)?.let { Color(it) } ?: MaterialTheme.colorScheme.surface
    val fg = if (pct == null) MaterialTheme.colorScheme.onSurfaceVariant
    else if (bg.luminance() > 0.5f) Color.Black else Color.White
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        color = fg,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .background(bg, MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun LightsPage(
    settings: AppSettings,
    discovered: List<DiscoveredLight>,
    status: EngineStatus,
    bleScanning: Boolean,
    actions: SettingsActions,
) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    LightList(settings, discovered, status, openId, actions) { light ->
        openId = if (openId == light.id) null else light.id
    }
    // The unfolded light is drawn inside the list (see LightList).
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = actions.rescanBle, modifier = Modifier.fillMaxWidth()) {
        Text("Bluetooth-Lichter suchen", maxLines = 1, modifier = Modifier.slowMarquee())
    }
    Note(
        if (bleScanning) {
            "Suche läuft, solange diese Seite offen ist. Die Magicshine muss eingeschaltet und darf nicht " +
                "mit einem Handy (Magicshine-App) verbunden sein."
        } else {
            "Bluetooth-Suche aus."
        },
    )
    Note("ANT+-Lichter (Varia) in den Sensoren des Karoo koppeln, Auto Control aus.")
}

@Composable
private fun RadarPage(settings: AppSettings, actions: SettingsActions) {
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
    Note("Die Leuchtart bei Warnung (und im Peloton-Modus) stellst du pro Licht unter „Lichter“ ein.")
}

@Composable
private fun PausePage(settings: AppSettings, actions: SettingsActions) {
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

@Composable
private fun BatteryPage(settings: AppSettings, actions: SettingsActions) {
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

@Composable
private fun ControlsPage(
    settings: AppSettings,
    ki2State: Ki2Bridge.State,
    di2LastButton: String?,
    actions: SettingsActions,
) {
    SubHeader("Datenfelder")
    Note("LightPilot: Tippen schaltet Automatik → " + (if (settings.pelotonEnabled) "Peloton → " else "") + "An → Aus → Hart aus.")
    Note("Frontlicht: Tippen schaltet die angehakten Stufen des aktiven Lichts.")
    Note("Abblend/Fern: Tippen wechselt zwischen Abblend- und Fernlicht (bei Licht aus nur Vorwahl).")

    SubHeader("Peloton")
    SwitchRow("Peloton-Modus im Statusfeld", settings.pelotonEnabled) {
        actions.save(settings.copy(pelotonEnabled = it))
    }
    Note(
        "Alles wie Automatik, nur leuchtet das Rücklicht bei einer Radar-Warnung dauerhaft statt zu blinken. " +
            "Bleibt beim Tag/Nacht-Wechsel, jede neue Fahrt beginnt mit Automatik.",
    )

    SubHeader("Frontlicht-Feld")
    IntSlider(
        label = "Weitertippen innerhalb",
        value = settings.frontCycleWindowSeconds, range = 0..10, unit = "s",
    ) { actions.save(settings.copy(frontCycleWindowSeconds = it)) }
    Note(
        "Erster Tipp nach einer Pause: Licht aus, ist es aus, kleinste Stufe. Weitertippen innerhalb dieser Zeit: " +
            "nächste Stufe. 0 s = keine Pausenregel.",
    )

    SubHeader("Shimano Di2 (über Ki2)")
    Di2Content(settings, ki2State, di2LastButton, actions.save)

    SubHeader("SRAM AXS")
    Note(
        "Bonus Buttons und Blips am Karoo belegen: Sensoren → AXS-Gruppe → Configure Controls → Taste drücken → " +
            "Karoo Action → LightPilot.",
    )
}

@Composable
private fun DiagnosePage(brakeDiag: String, exportInfo: String?, actions: SettingsActions) {
    SubHeader("Protokoll")
    LogPanel(height = 320.dp)
    OutlinedButton(onClick = actions.exportLog, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Protokoll exportieren", maxLines = 1, modifier = Modifier.slowMarquee())
    }
    exportInfo?.let { Note("Gespeichert: $it") }

    SubHeader("Bremslicht-Messung")
    Note(
        "Zeichnet Beschleunigungssensor, Geschwindigkeit und Lichtbefehle in eine CSV-Datei auf " +
            "(Ordner wie das Protokoll). Läuft auch bei geschlossener Seite weiter, höchstens 3 h.",
    )
    SwitchRow("Aufzeichnen", brakeDiag.startsWith("läuft")) { actions.brakeRecording(it) }
    Note(brakeDiag)
    OutlinedButton(onClick = actions.latencyTest, modifier = Modifier.fillMaxWidth()) {
        Text("Latenztest Rücklicht", maxLines = 1, modifier = Modifier.slowMarquee())
    }
    Note(
        "Ohne Fahrt: 10× Dauerlicht hell und Aus im Abstand von 3 s, mit Piepton beim Senden " +
            "(hoch = an, tief = aus). Mit dem Handy Licht und Karoo mit Ton filmen.",
    )
}

@Composable
private fun InfoPage(actions: SettingsActions) {
    Text("LightPilot ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})", style = MaterialTheme.typography.titleMedium)
    Note("Lichtsteuerung nach Tag/Nacht und Radar für den Hammerhead Karoo. MIT-Lizenz.")
    Note("Lichtansteuerung abgeleitet aus KarooFirefly (derstrassi) und LightONKaroo.")
    Note("Anleitungen: github.com/opaunke82/lightpilot (README, docs/tunnelmodus.md, docs/di2-ki2.md)")
    SubHeader("Zurücksetzen")
    ResetBlock(actions.factoryReset)
}

@Composable
private fun SubHeader(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
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
internal fun withBatteryColor(text: String, protocol: LightProtocol, pct: Int?): AnnotatedString =
    buildAnnotatedString {
        append(text)
        val battery = batteryText(protocol, pct) ?: return@buildAnnotatedString
        if (text.isNotEmpty()) append(" · ")
        val color = batteryColor(protocol, pct)
        if (color != null) withStyle(SpanStyle(color = Color(color))) { append(battery) } else append(battery)
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
    openId: String?,
    actions: SettingsActions,
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
            null -> ""
        }
        val protocol = if (light.protocol == LightProtocol.BLE) "Bluetooth" else "ANT+"
        val info = withBatteryColor(
            listOfNotNull(protocol, light.manufacturer, light.connectionLabel()).joinToString(" · "),
            light.protocol,
            light.batteryPercent,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (setup == null) Color(0xFFFFF3C4) else Color.Transparent, MaterialTheme.shapes.small)
                .clickable { onSelect(light) }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Name, info and role stacked; only the small arrow sits on the right, so the text
            // always keeps the full width (a long role label squeezed the name, 24.11.2026).
            Column(modifier = Modifier.weight(1f)) {
                Text(light.name, style = MaterialTheme.typography.titleMedium)
                Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (setup == null) "Antippen zum Einrichten" else "Rolle: $role",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                if (openId == light.id) "▲" else "▼",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (openId == light.id) {
            LightDetailPanel(
                light = light,
                setup = setup,
                onUpdate = { newSetup -> actions.save(settings.withLight(newSetup, light.id)) },
                onTestMode = actions.testMode,
                onForget = { actions.forgetLight(light) },
                onDismiss = { onSelect(light) },
            )
        }
        HorizontalDivider()
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
}

@Composable
private fun ChoiceButton(text: String, selected: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Narrow padding and no automatic wrapping: three buttons per row fit on the Karoo.
    // Two-line labels are written with an explicit line break.
    val label: @Composable () -> Unit = {
        Text(text, fontSize = 15.sp, softWrap = false, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 17.sp)
    }
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = CHOICE_PADDING) { label() }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = CHOICE_PADDING) { label() }
    }
}

private val CHOICE_PADDING = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

@Composable
private fun SimGroup(title: String, hint: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(title, style = MaterialTheme.typography.labelLarge)
    Note(hint)
    content()
}

@Composable
private fun TestBlock(status: EngineStatus, pelotonEnabled: Boolean, actions: SettingsActions) {
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
        "Das Datenfeld schaltet reihum Automatik → " + (if (pelotonEnabled) "Peloton → " else "") +
            "An → Aus → Hart aus. Hier direkt wählbar. Ein Wechsel von Tag/Nacht setzt wieder auf " +
            "Automatik (Peloton bleibt). Peloton testen: wählen, dann unter 3. ein Fahrzeug melden.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = Modifier.weight(1f)
            ChoiceButton("Auto", status.manual == ManualMode.AUTO, riding, w) { actions.setManual(ManualMode.AUTO) }
            ChoiceButton("Peloton", status.manual == ManualMode.PELOTON, riding && pelotonEnabled, w) {
                actions.setManual(ManualMode.PELOTON)
            }
            ChoiceButton("An", status.manual == ManualMode.ON, riding, w) { actions.setManual(ManualMode.ON) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = Modifier.weight(1f)
            ChoiceButton("Aus", status.manual == ManualMode.OFF, riding, w) { actions.setManual(ManualMode.OFF) }
            ChoiceButton("Hart aus", status.manual == ManualMode.HARD_OFF, riding, w) { actions.setManual(ManualMode.HARD_OFF) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = Modifier.weight(1f)
            ChoiceButton("Frontlicht\nnächste Stufe", selected = false, enabled = riding, modifier = w) { actions.frontCycle() }
            ChoiceButton("Abblend/\nFernlicht", selected = false, enabled = riding, modifier = w) { actions.beamToggle() }
        }
    }

    SimGroup(
        "5. Pause",
        "Pause-Taste: Licht und Radar-Warnung sofort aus. Auto-Pause: erst nach der eingestellten Zeit " +
            "(Abschnitt „Pause und Fahrtende“), Countdown oben im Status.",
    ) {
        val paused = status.phase == RidePhase.PAUSED_MANUAL || status.phase == RidePhase.PAUSED_AUTO
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val w = Modifier.weight(1f)
            ChoiceButton("Pause\nTaste", status.phase == RidePhase.PAUSED_MANUAL, riding, w) { actions.simulatePause(true) }
            ChoiceButton("Auto\nPause", status.phase == RidePhase.PAUSED_AUTO, riding, w) { actions.simulatePause(false) }
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
