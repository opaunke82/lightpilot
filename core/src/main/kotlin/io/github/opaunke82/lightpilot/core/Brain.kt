package io.github.opaunke82.lightpilot.core

/**
 * Everything the app knows that influences the light modes.
 * Immutable: every input produces a new state via [Brain.reduce].
 */
data class BrainState(
    val phase: RidePhase = RidePhase.IDLE,
    /** Start of the current auto-pause, null if not auto-paused. */
    val autoPauseSinceMs: Long? = null,
    /** True after a ride ended until the next ride starts. */
    val rideEnded: Boolean = false,
    val zone: Zone = Zone.DAY,
    val manual: ManualMode = ManualMode.AUTO,
    val pins: Map<String, Pin> = emptyMap(),
    val radar: RadarState = RadarState(),
    val batteryPct: Map<String, Int> = emptyMap(),
)

data class RadarState(
    /** Last sample showed a vehicle at or above the threshold. */
    val threat: Boolean = false,
    val lastThreatMs: Long? = null,
    val lastSampleMs: Long? = null,
) {
    /** No sample within the watchdog time (or never): radar data is stale. */
    fun isStale(nowMs: Long, settings: Settings): Boolean =
        lastSampleMs == null || nowMs - lastSampleMs > settings.radarWatchdogMs

    /** Warning is on while a vehicle is seen and for the hold time after the last one. */
    fun isWarning(nowMs: Long, settings: Settings): Boolean {
        if (threat && !isStale(nowMs, settings)) return true
        val last = lastThreatMs ?: return false
        return nowMs - last < settings.radarHoldMs
    }
}

sealed interface BrainEvent {
    data class RideChanged(val phase: RidePhase, val nowMs: Long) : BrainEvent
    data class ZoneChanged(val zone: Zone) : BrainEvent
    data class RadarSample(val threatLevel: Int, val nowMs: Long) : BrainEvent

    /** Data field tap: cycle the global manual setting. */
    data object ManualCycle : BrainEvent
    data class ManualSet(val mode: ManualMode) : BrainEvent

    /** Someone changed the light outside the app. */
    data class ExternalChange(val lightId: String, val mode: String) : BrainEvent

    /** Per-light data field tap: next mode of [LightConfig.fieldModes]. */
    data class FieldCycle(val light: LightConfig) : BrainEvent
    data class Battery(val lightId: String, val pct: Int?) : BrainEvent
}

/**
 * The single place that decides which mode every light should have.
 * Pure functions only; time is passed in, so all of it is unit-testable.
 *
 * Priority, highest first:
 *  1. no ride / ride ended
 *  2. manual pause (pause button) -> off, no radar
 *  3. HARD_OFF -> off, no radar
 *  4. auto-pause longer than the delay -> off, no radar
 *  5. radar warning (including hold time)
 *  6. per-light pin (external change or data field)
 *  7. manual ON / OFF
 *  8. day/night zone, with battery saver
 */
object Brain {

    fun reduce(state: BrainState, event: BrainEvent, settings: Settings): BrainState = when (event) {
        is BrainEvent.RideChanged -> onRide(state, event)

        is BrainEvent.ZoneChanged ->
            if (event.zone == state.zone) state
            // A zone change ends every manual intervention (M3).
            else state.copy(zone = event.zone, manual = ManualMode.AUTO, pins = emptyMap())

        is BrainEvent.RadarSample -> {
            val threat = event.threatLevel >= settings.radarMinThreatLevel
            state.copy(
                radar = state.radar.copy(
                    threat = threat,
                    lastThreatMs = if (threat) event.nowMs else state.radar.lastThreatMs,
                    lastSampleMs = event.nowMs,
                ),
            )
        }

        BrainEvent.ManualCycle -> state.copy(manual = state.manual.next(), pins = emptyMap())

        is BrainEvent.ManualSet -> state.copy(manual = event.mode, pins = emptyMap())

        is BrainEvent.ExternalChange ->
            state.copy(pins = state.pins + (event.lightId to Pin(event.mode, PinSource.EXTERNAL)))

        is BrainEvent.FieldCycle -> {
            val modes = event.light.fieldModes
            if (modes.isEmpty()) {
                state
            } else {
                val current = state.pins[event.light.id]?.mode
                val index = modes.indexOf(current)
                val next = modes[(index + 1) % modes.size]
                state.copy(pins = state.pins + (event.light.id to Pin(next, PinSource.FIELD)))
            }
        }

        is BrainEvent.Battery ->
            if (event.pct == null) state.copy(batteryPct = state.batteryPct - event.lightId)
            else state.copy(batteryPct = state.batteryPct + (event.lightId to event.pct))
    }

    private fun onRide(state: BrainState, event: BrainEvent.RideChanged): BrainState {
        val old = state.phase
        val new = event.phase
        if (old == new) return state
        return when (new) {
            RidePhase.IDLE -> state.copy(
                phase = new,
                autoPauseSinceMs = null,
                rideEnded = old != RidePhase.IDLE,
                manual = ManualMode.AUTO,
                pins = emptyMap(),
                radar = RadarState(),
            )
            RidePhase.RECORDING -> {
                val freshRide = old == RidePhase.IDLE
                state.copy(
                    phase = new,
                    autoPauseSinceMs = null,
                    rideEnded = false,
                    // A new ride starts in automatic mode (A1).
                    manual = if (freshRide) ManualMode.AUTO else state.manual,
                    pins = if (freshRide) emptyMap() else state.pins,
                )
            }
            RidePhase.PAUSED_AUTO -> state.copy(phase = new, autoPauseSinceMs = event.nowMs)
            RidePhase.PAUSED_MANUAL -> state.copy(phase = new, autoPauseSinceMs = null)
        }
    }

    fun decide(state: BrainState, light: LightConfig, settings: Settings, nowMs: Long): Decision {
        when (state.phase) {
            RidePhase.IDLE ->
                return if (state.rideEnded && settings.offAfterRide) Decision(MODE_OFF, Reason.RIDE_ENDED)
                else Decision(null, Reason.NO_RIDE)
            RidePhase.PAUSED_MANUAL -> return Decision(MODE_OFF, Reason.MANUAL_PAUSE)
            else -> Unit
        }
        if (state.manual == ManualMode.HARD_OFF) return Decision(MODE_OFF, Reason.HARD_OFF)

        val pauseStart = state.autoPauseSinceMs
        if (state.phase == RidePhase.PAUSED_AUTO && pauseStart != null &&
            nowMs - pauseStart >= settings.autoPauseOffDelayMs
        ) {
            return Decision(MODE_OFF, Reason.LONG_AUTO_PAUSE)
        }

        if (light.radarWarning && state.radar.isWarning(nowMs, settings)) {
            return Decision(light.warnMode, Reason.RADAR)
        }

        state.pins[light.id]?.let { pin ->
            val reason = if (pin.source == PinSource.EXTERNAL) Reason.PINNED_EXTERNAL else Reason.PINNED_FIELD
            return Decision(pin.mode, reason)
        }

        val saver = saverActive(state, light, settings)
        return when (state.manual) {
            ManualMode.ON -> Decision(withSaver(light.onMode, light.saverNightMode, saver), Reason.MANUAL_ON)
            ManualMode.OFF -> Decision(MODE_OFF, Reason.MANUAL_OFF)
            ManualMode.HARD_OFF -> Decision(MODE_OFF, Reason.HARD_OFF)
            ManualMode.AUTO -> {
                val (normal, saverMode) = when (state.zone) {
                    Zone.DAY -> light.dayMode to light.saverDayMode
                    Zone.NIGHT -> light.nightMode to light.saverNightMode
                }
                val mode = withSaver(normal, saverMode, saver)
                Decision(mode, if (mode != normal) Reason.ZONE_SAVER else Reason.ZONE)
            }
        }
    }

    private fun saverActive(state: BrainState, light: LightConfig, settings: Settings): Boolean {
        if (!settings.batterySaverEnabled) return false
        val pct = state.batteryPct[light.id] ?: return false
        return pct <= settings.batterySaverThresholdPct
    }

    /** The saver never switches a light on that would otherwise be off. */
    private fun withSaver(normal: String, saverMode: String?, saver: Boolean): String =
        if (saver && saverMode != null && normal != MODE_OFF) saverMode else normal
}
