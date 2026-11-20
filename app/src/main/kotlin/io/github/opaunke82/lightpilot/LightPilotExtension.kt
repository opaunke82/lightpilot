// Parts (light discovery, BLE handling, radar stream) derived from KarooFirefly
// (https://github.com/derstrassi/karoofirefly), Copyright (c) 2026 derstrassi, MIT License.

package io.github.opaunke82.lightpilot

import io.github.opaunke82.lightpilot.core.MODE_OFF
import io.github.opaunke82.lightpilot.diag.BrakeRecorder
import io.github.opaunke82.lightpilot.ble.MagicshineBleController
import io.github.opaunke82.lightpilot.core.RidePhase
import io.github.opaunke82.lightpilot.core.Zone
import io.github.opaunke82.lightpilot.data.AppSettings
import io.github.opaunke82.lightpilot.data.LightProtocol
import io.github.opaunke82.lightpilot.data.LightSetup
import io.github.opaunke82.lightpilot.data.PreferencesRepository
import io.github.opaunke82.lightpilot.datatypes.BeamDataType
import io.github.opaunke82.lightpilot.datatypes.FrontLightDataType
import io.github.opaunke82.lightpilot.datatypes.StatusDataType
import io.github.opaunke82.lightpilot.engine.AmbientLightSensor
import io.github.opaunke82.lightpilot.engine.LightIo
import io.github.opaunke82.lightpilot.engine.LightPilotEngine
import io.github.opaunke82.lightpilot.engine.TimeBasedController
import io.github.opaunke82.lightpilot.karoo.KarooLightControl
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

data class DiscoveredLight(
    val id: String,
    val name: String,
    val manufacturer: String? = null,
    val protocol: LightProtocol = LightProtocol.ANT_PLUS,
    val connected: Boolean = true,
    val batteryPercent: Int? = null,
    val temperature: Int? = null,
    /** Raw state from the Karoo (ANT+ only), null if the Karoo has not reported one. */
    val connectionState: String? = null,
    /** ANT+ light in a radar (Varia): connection judged by the radar data of the same device. */
    val viaRadar: Boolean = false,
)

/**
 * ANT+ lights only report a battery class (NEW/GOOD/OK/LOW/CRITICAL), no percentage.
 * It is mapped to a percentage for the saver threshold and shown as a word.
 */
fun batteryPercentFor(status: String?): Int? = when (status) {
    "NEW" -> 100
    "GOOD" -> 80
    "OK" -> 50
    "LOW" -> 20
    "CRITICAL" -> 5
    else -> null
}

fun batteryText(protocol: LightProtocol, pct: Int?): String? = pct?.let {
    if (protocol == LightProtocol.BLE) {
        "Akku $it %"
    } else {
        "Akku " + when {
            it >= 100 -> "neu"
            it >= 80 -> "gut"
            it >= 50 -> "ok"
            it >= 20 -> "niedrig"
            else -> "kritisch"
        }
    }
}

/**
 * Colour of the battery text: green = full to half (Varia: neu, gut), yellow = 49 to 20 %
 * (Varia: ok), red = below (Varia: niedrig, kritisch). null if unknown.
 */
fun batteryColor(protocol: LightProtocol, pct: Int?): Int? = pct?.let {
    val level = if (protocol == LightProtocol.BLE) {
        when {
            it >= 50 -> 2
            it >= 20 -> 1
            else -> 0
        }
    } else {
        // Varia classes: NEW 100, GOOD 80, OK 50, LOW 20, CRITICAL 5.
        when {
            it >= 80 -> 2
            it >= 50 -> 1
            else -> 0
        }
    }
    when (level) {
        2 -> BATTERY_GREEN
        1 -> BATTERY_YELLOW
        else -> BATTERY_RED
    }
}

const val BATTERY_GREEN = 0xFF2E7D32.toInt()
const val BATTERY_YELLOW = 0xFFF9A825.toInt()
const val BATTERY_RED = 0xFFC62828.toInt()

/** Text for the light list and dialog. */
fun DiscoveredLight.connectionLabel(): String = when {
    protocol == LightProtocol.BLE -> if (connected) "verbunden" else "nicht verbunden"
    viaRadar -> if (connected) "verbunden (Radar liefert Daten)" else "nicht verbunden (Radar liefert keine Daten)"
    connectionState == "CONNECTED" -> "verbunden"
    connectionState == null && connected -> "verbunden (vom Karoo nicht gemeldet)"
    connectionState == "SEARCHING" -> "sucht"
    connectionState == null -> "nicht verbunden"
    else -> "nicht verbunden ($connectionState)"
}

class LightPilotExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {

    companion object {
        const val TAG = "LightPilot"
        const val EXTENSION_ID = "lightpilot"
        private const val BIKE_LIGHT_DATA_TYPE = "TYPE_BIKE_LIGHT_ID"
        private const val DEVICE_TYPE_BIKE_LIGHT = 35
        private const val MAX_BRAKE_RECORDING_S = 3 * 3600L
        /** Same as the radar watchdog: after this long without radar data the Varia counts as off. */
        private const val RADAR_ALIVE_MS = 5_000L

        @Volatile
        private var instance: LightPilotExtension? = null
        fun getInstance(): LightPilotExtension? = instance
    }

    init {
        instance = this
    }

    internal lateinit var karooSystem: KarooSystemService
    internal lateinit var lightControl: KarooLightControl
    internal lateinit var magicshineController: MagicshineBleController
    internal lateinit var timeController: TimeBasedController
    internal lateinit var ambientLightSensor: AmbientLightSensor
    internal lateinit var engine: LightPilotEngine
    internal lateinit var repository: PreferencesRepository

    private val extensionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _antLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    private val _discoveredLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    val discoveredLights: StateFlow<List<DiscoveredLight>> = _discoveredLights

    @Volatile private var settings = AppSettings()
    @Volatile private var lightsNeeded = false
    @Volatile private var settingsUiActive = false
    @Volatile var isRideActive = false
        private set

    private var savedDevicesConsumerId: String? = null
    @Volatile private var radarConsumerId: String? = null
    private var radarWatchJob: Job? = null
    @Volatile private var radarLastDataMs = 0L
    private val _radarAlive = MutableStateFlow(false)

    /** ANT+ lights that sit in a radar (same serial number as a radar entry). */
    @Volatile private var antLightsWithRadar: Set<String> = emptySet()
    private var discoveryJob: Job? = null
    private var bleStartJob: Job? = null

    override val types by lazy {
        listOf(StatusDataType(this), FrontLightDataType(this), BeamDataType(this))
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("$TAG: extension onCreate")

        karooSystem = KarooSystemService(applicationContext)
        repository = PreferencesRepository(applicationContext)
        lightControl = KarooLightControl(applicationContext)
        magicshineController = MagicshineBleController(applicationContext)
        timeController = TimeBasedController()
        ambientLightSensor = AmbientLightSensor(applicationContext)

        engine = LightPilotEngine(io = lightIo, zoneProvider = ::currentZone)
        engine.onActive = { onLightsNeeded() }
        engine.onIdle = { onLightsReleased() }
        engine.onZoneChange = { zone -> notifyZone(zone) }

        // After (re)binding, start over so every light gets its mode again.
        lightControl.onServiceReady = { discoverKarooLights() }
        magicshineController.onDeviceConnected = { stopBleScanIfDone() }

        extensionScope.launch {
            combine(
                _antLights,
                magicshineController.discoveredLights,
                lightControl.connectionStates,
                lightControl.currentModes,
                _radarAlive,
            ) { ant, ble, conn, modes, radarAlive ->
                ant.map {
                    it.copy(
                        connected = isAntConnected(it.id, conn, modes, radarAlive),
                        connectionState = conn[it.id],
                        viaRadar = usesRadarForConnection(it.id),
                    )
                } + ble
            }.collect { merged ->
                _discoveredLights.value = merged
                syncLightNames(merged)
                merged.filter { it.protocol == LightProtocol.BLE }.forEach { engine.onBattery(it.id, it.batteryPercent) }
                engine.refresh()
            }
        }

        extensionScope.launch {
            repository.settingsFlow.collect { applySettings(it) }
        }

        loadFallbackLocation()

        karooSystem.connect { connected ->
            Timber.d("$TAG: Karoo system connected=$connected")
            if (connected) {
                karooSystem.addConsumer<OnLocationChanged> { event ->
                    timeController.onLocationUpdate(event.lat, event.lng)
                    saveLocation(event.lat, event.lng)
                }
                karooSystem.addConsumer<RideState> { state -> onRideState(state) }
            }
        }
    }

    // ---- Location fallback ------------------------------------------------------------

    private val locationPrefs by lazy { getSharedPreferences("lightpilot_location", android.content.Context.MODE_PRIVATE) }
    private var lastSavedLocationMs = 0L

    /**
     * Until the first GPS fix (indoors, start of a ride) the sun times come from the
     * last known position or, before the very first fix, from the time zone.
     * Without this the zone stayed DAY at night (18.11.2026).
     */
    private fun loadFallbackLocation() {
        val lat = locationPrefs.getFloat("lat", Float.NaN)
        val lng = locationPrefs.getFloat("lng", Float.NaN)
        if (!lat.isNaN() && !lng.isNaN()) {
            timeController.setFallbackLocation(lat.toDouble(), lng.toDouble(), estimated = false)
            Timber.d("$TAG: sun times from last known position")
            return
        }
        val tz = java.util.TimeZone.getDefault()
        val (estLat, estLng) = when (tz.id) {
            // Germany: roughly the centre of the country.
            "Europe/Berlin", "Europe/Busingen" -> 51.2 to 10.4
            else -> 50.0 to tz.rawOffset / 3_600_000.0 * 15.0
        }
        timeController.setFallbackLocation(estLat, estLng, estimated = true)
        Timber.d("$TAG: sun times estimated from time zone ${tz.id}")
    }

    private fun saveLocation(lat: Double, lng: Double) {
        val now = System.currentTimeMillis()
        if (now - lastSavedLocationMs < 10 * 60_000L) return
        lastSavedLocationMs = now
        locationPrefs.edit().putFloat("lat", lat.toFloat()).putFloat("lng", lng.toFloat()).apply()
    }

    /** Stored lights keep the name from the moment they were set up; keep it current. */
    private fun syncLightNames(lights: List<DiscoveredLight>) {
        val current = settings
        var changed = false
        val updated = current.lights.map { setup ->
            val name = lights.firstOrNull { it.id == setup.deviceId }?.name
            if (name != null && name != setup.deviceName) {
                changed = true
                setup.copy(deviceName = name)
            } else {
                setup
            }
        }
        if (changed) {
            val next = current.copy(lights = updated)
            settings = next
            extensionScope.launch { repository.updateSettings(next) }
        }
    }

    /** Factory reset: all settings, stored position and log, then a fresh engine state. */
    fun factoryReset() {
        Timber.w("$TAG: factory reset")
        engine.resetAll()
        for (id in magicshineController.assignedDeviceIds) magicshineController.disconnect(id)
        locationPrefs.edit().clear().apply()
        lastSavedLocationMs = 0
        extensionScope.launch {
            repository.clear()
            applySettings(AppSettings())
            loadFallbackLocation()
        }
    }

    // ---- Settings ---------------------------------------------------------------------

    fun applySettings(newSettings: AppSettings) {
        settings = newSettings
        timeController.dawnOffsetMinutes = newSettings.dawnOffsetMinutes
        timeController.duskOffsetMinutes = newSettings.duskOffsetMinutes
        ambientLightSensor.nightThreshold = newSettings.ambientNightThresholdLux
        if (newSettings.useAmbientLight) ambientLightSensor.start() else ambientLightSensor.stop()
        magicshineController.assignedDeviceIds = newSettings.lights
            .filter { it.protocol == LightProtocol.BLE }
            .map { it.deviceId }
            .toSet()
        engine.updateSettings(newSettings)
        if (lightsNeeded || settingsUiActive) startBle()
    }

    private fun currentZone(): Zone {
        val s = settings
        val timeZone = if (s.useTimeBased) timeController.getCurrentZone() else Zone.DAY
        if (!s.useAmbientLight || !ambientLightSensor.isAvailable) return timeZone
        val sensorZone = ambientLightSensor.currentLightZone.value
        // With both on, the light sensor can only make it darker (tunnel, forest), T2.
        return if (s.useTimeBased) {
            if (sensorZone == Zone.NIGHT) Zone.NIGHT else timeZone
        } else {
            sensorZone
        }
    }

    // ---- Ride state -------------------------------------------------------------------

    private fun onRideState(state: RideState) {
        val phase = when (state) {
            is RideState.Recording -> RidePhase.RECORDING
            is RideState.Paused -> if (state.auto) RidePhase.PAUSED_AUTO else RidePhase.PAUSED_MANUAL
            is RideState.Idle -> RidePhase.IDLE
            else -> return
        }
        isRideActive = phase != RidePhase.IDLE
        engine.onRideState(phase)
    }

    // ---- Light access -----------------------------------------------------------------

    /**
     * The Karoo does not report a connection state for the light (Varia RTL515,
     * 18.11.2026), not even when it is switched off at its button. For a light inside
     * a radar the radar data of the same device tells the truth: no radar data, light
     * off. Other ANT+ lights count as connected unless the Karoo says otherwise.
     * (The last reported mode is not used: it stays stored after the light went off.)
     */
    private fun isAntConnected(
        id: String,
        conn: Map<String, String>,
        modes: Map<String, String>,
        radarAlive: Boolean = _radarAlive.value,
    ): Boolean {
        if (!lightControl.isConnected()) return false
        when (conn[id]) {
            "DISCONNECTED", "DISABLED", "NOT_AVAILABLE" -> return false
        }
        if (usesRadarForConnection(id)) return radarAlive
        return true
    }

    private fun usesRadarForConnection(id: String) = id in antLightsWithRadar && radarConsumerId != null

    private val lightIo = object : LightIo {
        override fun connected(light: LightSetup): Boolean = when (light.protocol) {
            LightProtocol.ANT_PLUS -> isAntConnected(
                light.deviceId,
                lightControl.connectionStates.value,
                lightControl.currentModes.value,
            )
            LightProtocol.BLE -> magicshineController.allConnected(setOf(light.deviceId))
        }

        override fun observedMode(light: LightSetup): String? = when (light.protocol) {
            LightProtocol.ANT_PLUS -> lightControl.currentModes.value[light.deviceId]
            LightProtocol.BLE -> null
        }

        override fun send(light: LightSetup, mode: String) {
            when (light.protocol) {
                LightProtocol.ANT_PLUS -> lightControl.setMode(light.deviceId, mode)
                LightProtocol.BLE -> magicshineController.setMode(light.deviceId, mode)
            }
        }
    }

    /** The engine starts working: claim the light session, find lights, start radar. */
    private fun onLightsNeeded() {
        lightsNeeded = true
        lightControl.bind()
        startDiscoveryPolling()
        startBle()
        startRadar()
    }

    /** The engine is done: give the lights back to the Karoo. */
    private fun onLightsReleased() {
        lightsNeeded = false
        stopRadar()
        if (!settingsUiActive) {
            stopDiscoveryPolling()
            lightControl.unbind()
            releaseBleIfUnused()
        }
    }

    fun setSettingsUiActive(active: Boolean) {
        settingsUiActive = active
        if (active) {
            lightControl.bind()
            startDiscoveryPolling()
            startBle()
        } else if (!lightsNeeded) {
            stopDiscoveryPolling()
            lightControl.unbind()
            magicshineController.stopDiscovery()
            releaseBleIfUnused()
        } else {
            magicshineController.stopDiscovery()
        }
    }

    /** Sends a mode for 3 s, then hands the light back to the engine (test button). */
    fun testMode(deviceId: String, mode: String) {
        val light = settings.lights.find { it.deviceId == deviceId } ?: return
        extensionScope.launch {
            lightIo.send(light, mode)
            delay(3_000)
            // The engine resends its mode if a ride runs; otherwise switch off again.
            if (!lightsNeeded) lightIo.send(light, io.github.opaunke82.lightpilot.core.MODE_OFF)
            engine.updateSettings(settings)
        }
    }

    // ---- Discovery (KarooFirefly) -------------------------------------------------------

    private fun startDiscoveryPolling() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = extensionScope.launch {
            while (true) {
                discoverKarooLights()
                delay(30_000)
            }
        }
    }

    private fun stopDiscoveryPolling() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    internal fun discoverKarooLights() {
        savedDevicesConsumerId?.let { karooSystem.removeConsumer(it) }
        savedDevicesConsumerId = karooSystem.addConsumer<SavedDevices> { saved ->
            val lights = saved.devices.filter { device ->
                device.supportedDataTypes.contains(BIKE_LIGHT_DATA_TYPE) && device.enabled
            }.filter { device ->
                val parts = device.id.split("-")
                parts.size >= 3 && parts[1].toIntOrNull() == DEVICE_TYPE_BIKE_LIGHT
            }
            antLightsWithRadar = lights.filter { device ->
                val serial = device.id.split("-").getOrNull(2)
                serial != null && saved.devices.any { other ->
                    other.id != device.id && other.id.split("-").getOrNull(2) == serial
                }
            }.map { it.id }.toSet()
            _antLights.value = lights.map { device ->
                // A radar with a built-in light shows up twice: as radar and as light,
                // with the same serial number. The Karoo shows the radar's name
                // ("Radar 126742"); the light entry carries a generic, badly translated
                // name ("Leicht 126742"). Use the sibling's name and battery where present.
                val serial = device.id.split("-").getOrNull(2)
                val sibling = saved.devices.firstOrNull { other ->
                    other.id != device.id && serial != null && other.id.split("-").getOrNull(2) == serial
                }
                val status = device.details?.lastBattery ?: sibling?.details?.lastBattery
                val battery = batteryPercentFor(status?.name)
                engine.onBattery(device.id, battery)
                if (_antLights.value.none { it.id == device.id }) {
                    Timber.d(
                        "$TAG: ANT+ light ${device.id} '${device.name}', sibling=${sibling?.id} '${sibling?.name}', " +
                            "battery=${status?.name}",
                    )
                }
                DiscoveredLight(
                    id = device.id,
                    name = sibling?.name ?: device.name,
                    manufacturer = device.details?.manufacturer,
                    connected = isAntConnected(
                        device.id,
                        lightControl.connectionStates.value,
                        lightControl.currentModes.value,
                    ),
                    connectionState = lightControl.connectionStates.value[device.id],
                    batteryPercent = battery,
                )
            }
            for (device in lights) {
                lightControl.registerConnectionState(device.id)
                lightControl.registerForLightParameters(device.id)
            }
        }
    }

    /** Short beep as feedback for a data field tap. */
    fun tapFeedback() {
        karooSystem.dispatch(PlayBeepPattern(listOf(PlayBeepPattern.Tone(frequency = 1318, durationMs = 60))))
    }

    // ---- BLE (KarooFirefly) -------------------------------------------------------------

    private fun hasBleLights() = settings.lights.any { it.protocol == LightProtocol.BLE }

    private fun startBle() {
        if (!settingsUiActive && !hasBleLights()) return
        bleStartJob?.cancel()
        bleStartJob = extensionScope.launch {
            delay(1_000)
            karooSystem.dispatch(RequestBluetooth(extension))
            for (id in magicshineController.assignedDeviceIds) magicshineController.connect(id)
            // The scanner only runs while the settings page is open (to find new lights).
            if (settingsUiActive) magicshineController.startDiscovery()
        }
    }

    /** "Nach Bluetooth-Lichtern suchen" on the settings page. */
    fun rescanBle() {
        if (!settingsUiActive) return
        Timber.d("$TAG: BLE rescan requested")
        karooSystem.dispatch(RequestBluetooth(extension))
        magicshineController.rescan()
    }

    private fun stopBleScanIfDone() {
        if (!settingsUiActive) magicshineController.stopDiscovery()
    }

    private fun releaseBleIfUnused() {
        if (!hasBleLights()) {
            magicshineController.stopDiscovery()
            karooSystem.dispatch(ReleaseBluetooth(extension))
        }
    }

    // ---- Brake light measurement ------------------------------------------------------

    private var speedConsumerId: String? = null
    private var latencyJob: Job? = null
    private val _brakeDiag = MutableStateFlow("aus")
    /** Status line of the brake measurement for the settings page. */
    val brakeDiag: StateFlow<String> = _brakeDiag
    private var brakeStatusJob: Job? = null

    fun startBrakeRecording() {
        val r = BrakeRecorder.start(this)
        if (speedConsumerId == null) {
            speedConsumerId = karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.SPEED)) { event: OnStreamState ->
                val state = event.state
                if (state is StreamState.Streaming) {
                    BrakeRecorder.active?.mark("S", state.dataPoint.values[DataType.Field.SPEED])
                }
            }
        }
        brakeStatusJob?.cancel()
        brakeStatusJob = extensionScope.launch {
            while (true) {
                val sec = (android.os.SystemClock.elapsedRealtime() - r.startedAtMs) / 1000
                _brakeDiag.value = if (r.hasAccelerometer) {
                    "läuft ${sec / 60}:${"%02d".format(sec % 60)} · ${r.samples} Messwerte · ${r.file.name}"
                } else {
                    "läuft, aber kein Beschleunigungssensor für Apps verfügbar · ${r.file.name}"
                }
                if (sec > MAX_BRAKE_RECORDING_S) {
                    stopBrakeRecording()
                    break
                }
                delay(1_000)
            }
        }
    }

    fun stopBrakeRecording() {
        latencyJob?.cancel()
        speedConsumerId?.let { karooSystem.removeConsumer(it) }
        speedConsumerId = null
        brakeStatusJob?.cancel()
        val file = BrakeRecorder.stop()
        _brakeDiag.value = file?.let { "gespeichert: ${it.name}" } ?: "aus"
    }

    /**
     * Rear light latency: 10 × "Dauerlicht hell" and back to "Aus", 3 s apart, each with a
     * beep at the moment the command is queued (high tone = on, low tone = off). Film light
     * and Karoo with sound: the time from beep to light change is the latency. Only
     * outside a ride, so the automatic control does not interfere.
     */
    fun runLatencyTest(): String? {
        if (isRideActive || engine.status.value.simulation) return "Nur ohne Fahrt und ohne Simulation."
        val rear = settings.lights.firstOrNull { it.protocol == LightProtocol.ANT_PLUS }
            ?: return "Kein ANT+-Rücklicht eingerichtet."
        if (latencyJob?.isActive == true) return null
        val ownRecording = BrakeRecorder.active == null
        if (ownRecording) startBrakeRecording()
        latencyJob = extensionScope.launch {
            try {
                delay(2_000)
                repeat(10) { i ->
                    BrakeRecorder.active?.mark("B", "on-${i + 1}")
                    karooSystem.dispatch(PlayBeepPattern(listOf(PlayBeepPattern.Tone(frequency = 1760, durationMs = 80))))
                    lightControl.setMode(rear.deviceId, "STEADY4")
                    delay(3_000)
                    BrakeRecorder.active?.mark("B", "off-${i + 1}")
                    karooSystem.dispatch(PlayBeepPattern(listOf(PlayBeepPattern.Tone(frequency = 880, durationMs = 80))))
                    lightControl.setMode(rear.deviceId, MODE_OFF)
                    delay(3_000)
                }
            } finally {
                if (ownRecording) stopBrakeRecording()
            }
        }
        return null
    }

    // ---- Radar ------------------------------------------------------------------------

    private fun startRadar() {
        if (radarConsumerId != null) return
        Timber.d("$TAG: radar stream start")
        radarConsumerId = karooSystem.addConsumer(
            OnStreamState.StartStreaming(DataType.Type.RADAR),
        ) { event: OnStreamState ->
            val state = event.state
            if (state is StreamState.Streaming) {
                radarLastDataMs = System.currentTimeMillis()
                if (!_radarAlive.value) Timber.d("$TAG: radar data present")
                _radarAlive.value = true
                val level = state.dataPoint.values[DataType.Field.RADAR_THREAT_LEVEL]?.toInt() ?: 0
                engine.onRadar(level)
            } else {
                if (_radarAlive.value) Timber.d("$TAG: radar stream ${state.javaClass.simpleName}")
                radarLastDataMs = 0
                _radarAlive.value = false
            }
        }
        radarWatchJob?.cancel()
        radarWatchJob = extensionScope.launch {
            while (true) {
                delay(1_000)
                val silent = System.currentTimeMillis() - radarLastDataMs > RADAR_ALIVE_MS
                if (silent && _radarAlive.value) {
                    Timber.d("$TAG: no radar data for ${RADAR_ALIVE_MS / 1000} s")
                    _radarAlive.value = false
                }
            }
        }
    }

    private fun stopRadar() {
        radarConsumerId?.let { karooSystem.removeConsumer(it) }
        radarConsumerId = null
        radarWatchJob?.cancel()
        radarWatchJob = null
        _radarAlive.value = false
    }

    // ---- In-ride input ----------------------------------------------------------------

    private fun notifyZone(zone: Zone) {
        if (!settings.zoneNotifications) return
        karooSystem.dispatch(
            PlayBeepPattern(
                listOf(
                    PlayBeepPattern.Tone(frequency = 784, durationMs = 120),
                    PlayBeepPattern.Tone(frequency = null, durationMs = 60),
                    PlayBeepPattern.Tone(frequency = 1046, durationMs = 200),
                ),
            ),
        )
        karooSystem.dispatch(
            InRideAlert(
                id = "lightpilot-zone",
                icon = R.drawable.ic_light,
                title = if (zone == Zone.NIGHT) "LightPilot: Nacht" else "LightPilot: Tag",
                detail = "Licht folgt jetzt dem ${if (zone == Zone.NIGHT) "Nacht" else "Tag"}modus",
                autoDismissMs = 10_000,
                backgroundColor = android.R.color.black,
                textColor = android.R.color.white,
            ),
        )
    }

    override fun onBonusAction(actionId: String) {
        Timber.d("$TAG: bonus action $actionId")
        when (actionId) {
            "manual-cycle" -> engine.manualCycle()
            "front-cycle" -> engine.frontCycle()
            "beam-toggle" -> engine.beamToggle()
        }
    }

    override fun onDestroy() {
        Timber.d("$TAG: extension onDestroy")
        instance = null
        stopBrakeRecording()
        stopRadar()
        stopDiscoveryPolling()
        engine.destroy()
        magicshineController.destroy()
        lightControl.unbind()
        ambientLightSensor.stop()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        extensionScope.cancel()
        super.onDestroy()
    }
}
