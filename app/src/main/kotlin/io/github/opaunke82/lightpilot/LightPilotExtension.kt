// Parts (light discovery, BLE handling, radar stream) derived from KarooFirefly
// (https://github.com/derstrassi/karoofirefly), Copyright (c) 2026 derstrassi, MIT License.

package io.github.opaunke82.lightpilot

import io.github.opaunke82.lightpilot.ble.MagicshineBleController
import io.github.opaunke82.lightpilot.core.RidePhase
import io.github.opaunke82.lightpilot.core.Zone
import io.github.opaunke82.lightpilot.data.AppSettings
import io.github.opaunke82.lightpilot.data.LightProtocol
import io.github.opaunke82.lightpilot.data.LightSetup
import io.github.opaunke82.lightpilot.data.PreferencesRepository
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
)

/** Text for the light list and dialog. */
fun DiscoveredLight.connectionLabel(): String = when {
    protocol == LightProtocol.BLE -> if (connected) "verbunden" else "nicht verbunden"
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
    private var radarConsumerId: String? = null
    private var discoveryJob: Job? = null
    private var bleStartJob: Job? = null

    override val types by lazy {
        listOf(StatusDataType(this), FrontLightDataType(this))
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
            ) { ant, ble, conn, modes ->
                ant.map { it.copy(connected = isAntConnected(it.id, conn, modes), connectionState = conn[it.id]) } + ble
            }.collect { merged ->
                _discoveredLights.value = merged
                merged.filter { it.protocol == LightProtocol.BLE }.forEach { engine.onBattery(it.id, it.batteryPercent) }
            }
        }

        extensionScope.launch {
            repository.settingsFlow.collect { applySettings(it) }
        }

        karooSystem.connect { connected ->
            Timber.d("$TAG: Karoo system connected=$connected")
            if (connected) {
                karooSystem.addConsumer<OnLocationChanged> { event ->
                    timeController.onLocationUpdate(event.lat, event.lng)
                }
                karooSystem.addConsumer<RideState> { state -> onRideState(state) }
            }
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
     * The Karoo does not always report a connection state after registering
     * (seen on the Varia RTL515, 18.11.2026; LightONKaroo handles it the same way).
     * So a light counts as connected unless the Karoo explicitly says otherwise,
     * or as soon as it reports a mode. Sending to a light that is away is harmless:
     * the reconciler simply sends again.
     */
    private fun isAntConnected(id: String, conn: Map<String, String>, modes: Map<String, String>): Boolean {
        if (!lightControl.isConnected()) return false
        if (modes.containsKey(id)) return true
        return when (conn[id]) {
            "DISCONNECTED", "DISABLED", "NOT_AVAILABLE" -> false
            else -> true
        }
    }

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
            _antLights.value = lights.map { device ->
                val battery = when (device.details?.lastBattery?.name) {
                    "NEW", "GOOD" -> 80
                    "OK" -> 50
                    "LOW" -> 20
                    "CRITICAL" -> 5
                    else -> null
                }
                engine.onBattery(device.id, battery)
                DiscoveredLight(
                    id = device.id,
                    name = device.name,
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

    private fun stopBleScanIfDone() {
        if (!settingsUiActive) magicshineController.stopDiscovery()
    }

    private fun releaseBleIfUnused() {
        if (!hasBleLights()) {
            magicshineController.stopDiscovery()
            karooSystem.dispatch(ReleaseBluetooth(extension))
        }
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
                val level = state.dataPoint.values[DataType.Field.RADAR_THREAT_LEVEL]?.toInt() ?: 0
                engine.onRadar(level)
            }
        }
    }

    private fun stopRadar() {
        radarConsumerId?.let { karooSystem.removeConsumer(it) }
        radarConsumerId = null
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
        }
    }

    override fun onDestroy() {
        Timber.d("$TAG: extension onDestroy")
        instance = null
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
