package io.github.opaunke82.lightpilot.engine

import io.github.opaunke82.lightpilot.core.Brain
import io.github.opaunke82.lightpilot.core.BrainEvent
import io.github.opaunke82.lightpilot.core.BrainState
import io.github.opaunke82.lightpilot.core.LinkState
import io.github.opaunke82.lightpilot.core.ManualMode
import io.github.opaunke82.lightpilot.core.Reason
import io.github.opaunke82.lightpilot.core.Reconciler
import io.github.opaunke82.lightpilot.core.RidePhase
import io.github.opaunke82.lightpilot.core.Zone
import io.github.opaunke82.lightpilot.data.AppSettings
import io.github.opaunke82.lightpilot.data.LightRole
import io.github.opaunke82.lightpilot.data.LightSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors

/** Access to the real lights, implemented by the extension. */
interface LightIo {
    fun connected(light: LightSetup): Boolean

    /** Mode the light reports, null if unknown or not reported (BLE). */
    fun observedMode(light: LightSetup): String?
    fun send(light: LightSetup, mode: String)
}

data class LightStatus(
    val setup: LightSetup,
    val desired: String?,
    val reason: Reason,
    val connected: Boolean,
    val confirmed: Boolean,
    val observed: String?,
    val batteryPct: Int?,
)

data class EngineStatus(
    val phase: RidePhase = RidePhase.IDLE,
    val zone: Zone = Zone.DAY,
    val manual: ManualMode = ManualMode.AUTO,
    val radarWarning: Boolean = false,
    val radarSeen: Boolean = false,
    val radarStale: Boolean = true,
    val simulation: Boolean = false,
    /** Zone forced in the simulation, null = real day/night. */
    val simZone: Zone? = null,
    /** Seconds until a long auto-pause switches the lights off, null if not auto-paused. */
    val autoPauseOffInSec: Long? = null,
    val lights: List<LightStatus> = emptyList(),
)

/**
 * Runs the core logic on one thread: collects inputs, asks [Brain] for the
 * desired mode of every light, and lets [Reconciler] decide what to send.
 */
class LightPilotEngine(
    private val io: LightIo,
    private val zoneProvider: () -> Zone,
) {
    companion object {
        private const val TAG = "Engine"
        const val TICK_MS = 250L
        private const val AFTER_RIDE_MS = 10_000L
    }

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loopJob: Job? = null

    @Volatile var settings: AppSettings = AppSettings()
        private set

    private var brain = BrainState()
    private val links = mutableMapOf<String, LinkState>()
    private var rideEndedAtMs: Long? = null
    private var lastDesired = mutableMapOf<String, String?>()

    // Simulation (D1)
    private var simRide = false
    private var simulation = false
    private var simVehicleUntilMs = 0L
    private var simZone: Zone? = null

    /** Real zone, or the one forced in the simulation. */
    private fun zone(): Zone = if (simulation) simZone ?: zoneProvider() else zoneProvider()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    /** Called on a zone change during a ride (for the in-ride notification). */
    var onZoneChange: ((Zone) -> Unit)? = null

    /** Called when the engine no longer needs the lights (ride over, simulation off). */
    var onIdle: (() -> Unit)? = null

    /** Called when the engine starts needing the lights. */
    var onActive: (() -> Unit)? = null

    private fun now() = System.currentTimeMillis()

    private fun post(block: () -> Unit) {
        scope.launch { block() }
    }

    private fun apply(event: BrainEvent) {
        val before = brain
        brain = Brain.reduce(brain, event, settings.toCore())
        if (event !is BrainEvent.RadarSample) Timber.d("$TAG: $event")
        if (before.radar.threat != brain.radar.threat) {
            Timber.d("$TAG: radar ${if (brain.radar.threat) "VEHICLE" else "clear"}")
        }
    }

    fun updateSettings(newSettings: AppSettings) = post {
        settings = newSettings
        val ids = newSettings.lights.map { it.deviceId }.toSet()
        links.keys.retainAll(ids)
        evaluate()
    }

    fun onRideState(phase: RidePhase) = post {
        if (simRide) return@post
        setPhase(phase)
    }

    private fun setPhase(phase: RidePhase) {
        val wasIdle = brain.phase == RidePhase.IDLE
        apply(BrainEvent.RideChanged(phase, now()))
        if (phase == RidePhase.IDLE) {
            rideEndedAtMs = if (brain.rideEnded) now() else null
        } else {
            rideEndedAtMs = null
            if (wasIdle) {
                // Fresh ride: start with the current zone and resend everything.
                apply(BrainEvent.ZoneChanged(zone()))
                links.clear()
            }
        }
        ensureLoop()
    }

    fun onRadar(threatLevel: Int) = post {
        if (simulation) return@post
        apply(BrainEvent.RadarSample(threatLevel, now()))
    }

    fun manualCycle() = post {
        apply(BrainEvent.ManualCycle)
        evaluate()
    }

    /** Front data field tap: next brightness of the first front light that has field modes. */
    fun frontCycle() = post {
        val front = settings.lights.firstOrNull { it.role == LightRole.FRONT && it.fieldModes.isNotEmpty() }
            ?: return@post
        apply(BrainEvent.FieldCycle(front.toCore(), now()))
        evaluate()
    }

    /** Factory reset: forget all state, leave the simulation. Lights get no command. */
    fun resetAll() = post {
        brain = BrainState()
        links.clear()
        lastDesired.clear()
        rideEndedAtMs = null
        simulation = false
        simRide = false
        simVehicleUntilMs = 0
        simZone = null
        settings = AppSettings()
        _status.value = EngineStatus()
        Timber.d("$TAG: reset")
    }

    /** Re-evaluates now, e.g. after a light connected; also keeps the status page current. */
    fun refresh() = post { evaluate() }

    fun onBattery(lightId: String, pct: Int?) = post {
        if (brain.batteryPct[lightId] != pct) apply(BrainEvent.Battery(lightId, pct))
    }

    // ---- Simulation -------------------------------------------------------------------

    fun setSimulation(enabled: Boolean) = post {
        simulation = enabled
        simVehicleUntilMs = 0
        simZone = null
        if (!enabled && simRide) {
            simRide = false
            setPhase(RidePhase.IDLE)
        }
        Timber.d("$TAG: simulation=${if (enabled) "on" else "off"}")
        ensureLoop()
    }

    fun setSimulatedRide(enabled: Boolean) = post {
        if (!simulation) return@post
        simRide = enabled
        setPhase(if (enabled) RidePhase.RECORDING else RidePhase.IDLE)
    }

    fun simulateVehicle(seconds: Int) = post {
        if (!simulation) return@post
        simVehicleUntilMs = now() + seconds * 1_000L
        Timber.d("$TAG: simulated vehicle for $seconds s")
    }

    /** Forces day or night in the simulation; null = real day/night. */
    fun simulateZone(zone: Zone?) = post {
        if (!simulation) return@post
        simZone = zone
        Timber.d("$TAG: simulated zone=${zone ?: "real"}")
        evaluate()
    }

    /** Sets the manual mode directly (simulation page), instead of cycling. */
    fun setManual(mode: ManualMode) = post {
        apply(BrainEvent.ManualSet(mode))
        evaluate()
    }

    fun simulatePause(manual: Boolean) = post {
        if (!simRide) return@post
        setPhase(if (manual) RidePhase.PAUSED_MANUAL else RidePhase.PAUSED_AUTO)
    }

    fun simulateResume() = post {
        if (!simRide) return@post
        setPhase(RidePhase.RECORDING)
    }

    // ---- Loop -------------------------------------------------------------------------

    private fun needsLoop(): Boolean =
        brain.phase != RidePhase.IDLE || rideEndedAtMs != null || simulation

    private fun ensureLoop() {
        if (needsLoop()) {
            if (loopJob?.isActive == true) return
            onActive?.invoke()
            loopJob = scope.launch {
                Timber.d("$TAG: loop started")
                while (isActive && needsLoop()) {
                    tick()
                    delay(TICK_MS)
                }
                Timber.d("$TAG: loop stopped")
                publishStatus()
                onIdle?.invoke()
            }
        } else {
            evaluate()
        }
    }

    private fun tick() {
        val t = now()
        if (simulation) {
            apply(BrainEvent.RadarSample(if (t < simVehicleUntilMs) 2 else 0, t))
        }
        if (brain.phase != RidePhase.IDLE) {
            val zone = zone()
            if (zone != brain.zone) {
                apply(BrainEvent.ZoneChanged(zone))
                onZoneChange?.invoke(zone)
            }
        }
        evaluate()
        val ended = rideEndedAtMs
        if (ended != null && t - ended > AFTER_RIDE_MS) {
            // The lights got their OFF after the ride; from now on they are left alone.
            rideEndedAtMs = null
            brain = brain.copy(rideEnded = false)
        }
    }

    private fun evaluate() {
        val t = now()
        val core = settings.toCore()
        val result = mutableListOf<LightStatus>()
        for (setup in settings.lights) {
            val config = setup.toCore()
            val decision = Brain.decide(brain, config, core, t)
            val connected = io.connected(setup)
            val observed = if (connected) io.observedMode(setup) else null
            val step = Reconciler.step(links[setup.deviceId] ?: LinkState(), decision.mode, observed, connected, t)
            links[setup.deviceId] = step.link

            if (lastDesired[setup.deviceId] != decision.mode) {
                Timber.d("$TAG: ${setup.deviceName}: desired=${decision.mode} (${decision.reason})")
                lastDesired[setup.deviceId] = decision.mode
            }
            step.externalChange?.let { mode ->
                Timber.d("$TAG: ${setup.deviceName}: changed outside the app to $mode, adopting it")
                apply(BrainEvent.ExternalChange(setup.deviceId, mode))
            }
            step.send?.let { mode ->
                Timber.d("$TAG: ${setup.deviceName}: send $mode (observed=$observed, retry=${step.link.retries})")
                io.send(setup, mode)
            }
            result += LightStatus(
                setup = setup,
                desired = decision.mode,
                reason = decision.reason,
                connected = connected,
                confirmed = step.confirmed,
                observed = observed,
                batteryPct = brain.batteryPct[setup.deviceId],
            )
        }
        publishStatus(result)
    }

    private fun publishStatus(lights: List<LightStatus> = _status.value.lights) {
        val t = now()
        val core = settings.toCore()
        val next = EngineStatus(
            phase = brain.phase,
            zone = if (brain.phase == RidePhase.IDLE) zone() else brain.zone,
            manual = brain.manual,
            radarWarning = brain.radar.isWarning(t, core),
            radarSeen = brain.radar.lastSampleMs != null,
            radarStale = brain.radar.isStale(t, core),
            simulation = simulation,
            simZone = if (simulation) simZone else null,
            autoPauseOffInSec = brain.autoPauseSinceMs?.let { since ->
                ((since + core.autoPauseOffDelayMs - t) / 1_000).coerceAtLeast(0)
            },
            lights = lights,
        )
        if (next != _status.value) _status.value = next
    }

    fun destroy() {
        scope.cancel()
        dispatcher.close()
    }
}
