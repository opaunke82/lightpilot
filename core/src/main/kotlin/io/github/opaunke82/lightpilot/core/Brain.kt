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
    /** Time of the last data field tap per light (cycle window). */
    val fieldTapMs: Map<String, Long> = emptyMap(),
    /** Beam chosen with the beam field per light (also while the light is off: preselection). */
    val beam: Map<String, Beam> = emptyMap(),
    /** Last mode used per light and beam ("<id>/<beam>"), for switching back and forth. */
    val beamLast: Map<String, String> = emptyMap(),
) {
    /** Front field choices end together: zone change, new ride, manual change. */
    fun withoutFieldChoices() = copy(pins = emptyMap(), beam = emptyMap(), beamLast = emptyMap())
}

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

    /**
     * Per-light data field tap: next mode of [LightConfig.fieldModes]. With a cycle window,
     * a tap after a longer break switches the light off first (if "off" is in the cycle).
     */
    data class FieldCycle(val light: LightConfig, val nowMs: Long = 0) : BrainEvent

    /**
     * Beam field tap: switch between low and high beam. A light that is on changes at once
     * (to the last mode used in that beam, high beam first at its highest ticked step);
     * a light that is off only gets the beam preselected.
     */
    data class BeamToggle(val light: LightConfig, val nowMs: Long = 0) : BrainEvent
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
 *  5. radar warning (including hold time), steady instead of flashing in peloton mode
 *  6. per-light pin (external change or data field)
 *  7. manual ON / OFF
 *  8. day/night zone, with battery saver
 */
object Brain {

    /**
     * A manual change ends per-light choices (front brightness from the field, changes on the
     * light), except between Automatik and Peloton: both are automatic, so the choices stay.
     */
    private fun afterManualChange(state: BrainState, next: ManualMode): BrainState {
        val automatic = setOf(ManualMode.AUTO, ManualMode.PELOTON)
        val kept = if (state.manual in automatic && next in automatic) state else state.withoutFieldChoices()
        return kept.copy(manual = next)
    }

    /** Beam the front light is in or preselected for; null for lights without beams. */
    fun activeBeam(state: BrainState, light: LightConfig, settings: Settings, nowMs: Long): Beam? {
        if (!light.hasBeams()) return null
        return state.beam[light.id]
            ?: Beam.of(decide(state, light, settings, nowMs).mode)
            ?: Beam.of(state.pins[light.id]?.mode)
            ?: Beam.LOW
    }

    private fun LightConfig.hasBeams() =
        (fieldModes + listOf(dayMode, nightMode)).any { Beam.of(it) != null }

    private fun beamKey(id: String, beam: Beam) = "$id/${beam.name}"

    private fun onBeamToggle(state: BrainState, event: BrainEvent.BeamToggle, settings: Settings): BrainState {
        val light = event.light
        val current = activeBeam(state, light, settings, event.nowMs) ?: return state
        val target = current.other()
        val shown = decide(state, light, settings, event.nowMs).mode
        var s = state.copy(beam = state.beam + (light.id to target))
        if (Beam.of(shown) == current) s = s.copy(beamLast = s.beamLast + (beamKey(light.id, current) to shown!!))
        if (shown == null || shown == MODE_OFF) return s
        val mode = s.beamLast[beamKey(light.id, target)] ?: firstModeOf(light, target)
        return s.copy(pins = s.pins + (light.id to Pin(mode, PinSource.FIELD)))
    }

    /** High beam: highest ticked step. Low beam: the night mode, else the highest ticked step. */
    private fun firstModeOf(light: LightConfig, beam: Beam): String {
        if (beam == Beam.LOW && Beam.of(light.nightMode) == Beam.LOW) return light.nightMode
        return light.fieldModes.filter { Beam.of(it) == beam }.maxByOrNull { Beam.percentOf(it) ?: 0 }
            ?: (beam.prefix + "100")
    }

    fun reduce(state: BrainState, event: BrainEvent, settings: Settings): BrainState = when (event) {
        is BrainEvent.RideChanged -> onRide(state, event)

        is BrainEvent.ZoneChanged ->
            if (event.zone == state.zone) state
            // A zone change ends every manual intervention (M3). Peloton is no intervention
            // in the lights but a riding situation, so it stays.
            else state.withoutFieldChoices().copy(
                zone = event.zone,
                manual = if (state.manual == ManualMode.PELOTON) ManualMode.PELOTON else ManualMode.AUTO,
            )

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

        BrainEvent.ManualCycle -> afterManualChange(state, state.manual.next(settings.pelotonEnabled))

        is BrainEvent.ManualSet -> afterManualChange(state, event.mode)

        is BrainEvent.ExternalChange ->
            state.copy(pins = state.pins + (event.lightId to Pin(event.mode, PinSource.EXTERNAL)))

        is BrainEvent.BeamToggle -> onBeamToggle(state, event, settings)

        is BrainEvent.FieldCycle -> {
            val light = event.light
            // With beams the cycle runs through the steps of the active beam only.
            val beam = activeBeam(state, light, settings, event.nowMs)
            val ofBeam = light.fieldModes.filter { it == MODE_OFF || Beam.of(it) == beam }
            val modes = if (beam != null && ofBeam.any { it != MODE_OFF }) ofBeam else light.fieldModes
            if (modes.isEmpty()) {
                state
            } else {
                val last = state.fieldTapMs[light.id]
                val window = settings.fieldCycleWindowMs
                val breakOver = window > 0 && (last == null || event.nowMs - last > window)
                val shownNow = decide(state, light, settings, event.nowMs).mode
                val lightIsOn = shownNow != null && shownNow != MODE_OFF
                val lowest = modes.firstOrNull { it != MODE_OFF }
                val pinned = state.pins[light.id]?.mode
                val next = when {
                    // First tap after a break: a light that is on goes off (whether or not
                    // "Aus" is ticked), a light that is off goes to the lowest ticked step.
                    breakOver && lightIsOn -> MODE_OFF
                    breakOver -> lowest
                    // Within the window after such an "off": start again as if it had been off.
                    pinned == MODE_OFF && MODE_OFF !in modes -> lowest
                    // Within the window: next step of the cycle.
                    else -> modes[(modes.indexOf(pinned) + 1) % modes.size]
                }
                if (next == null) {
                    state
                } else {
                    val nextBeam = Beam.of(next)
                    state.copy(
                        pins = state.pins + (light.id to Pin(next, PinSource.FIELD)),
                        fieldTapMs = state.fieldTapMs + (light.id to event.nowMs),
                        beam = if (nextBeam != null) state.beam + (light.id to nextBeam) else state.beam,
                        beamLast = if (nextBeam != null) state.beamLast + (beamKey(light.id, nextBeam) to next) else state.beamLast,
                    )
                }
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
            RidePhase.IDLE -> state.withoutFieldChoices().copy(
                phase = new,
                autoPauseSinceMs = null,
                rideEnded = old != RidePhase.IDLE,
                manual = ManualMode.AUTO,
                radar = RadarState(),
            )
            RidePhase.RECORDING -> {
                val freshRide = old == RidePhase.IDLE
                (if (freshRide) state.withoutFieldChoices() else state).copy(
                    phase = new,
                    autoPauseSinceMs = null,
                    rideEnded = false,
                    // A new ride starts in automatic mode (A1).
                    manual = if (freshRide) ManualMode.AUTO else state.manual,
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

        val peloton = state.manual == ManualMode.PELOTON && settings.pelotonEnabled
        if (light.radarWarning && state.radar.isWarning(nowMs, settings)) {
            return if (peloton) Decision(light.pelotonWarnMode, Reason.RADAR_PELOTON)
            else Decision(light.warnMode, Reason.RADAR)
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
            ManualMode.AUTO, ManualMode.PELOTON -> {
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
