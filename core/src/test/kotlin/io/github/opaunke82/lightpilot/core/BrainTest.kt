package io.github.opaunke82.lightpilot.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrainTest {

    private val settings = Settings()
    private val varia = LightConfig(
        id = "varia",
        role = Role.REAR,
        dayMode = MODE_OFF,
        nightMode = "SLOW_FLASH",
        warnMode = "FAST_FLASH",
        radarWarning = true,
        saverNightMode = "STEADY5",
    )
    private val evo = LightConfig(
        id = "evo",
        role = Role.FRONT,
        dayMode = MODE_OFF,
        nightMode = "STEADY_50",
        radarWarning = false,
        saverNightMode = "STEADY_10",
        fieldModes = listOf("STEADY_25", "STEADY_50", "STEADY_100"),
    )

    private fun run(vararg events: BrainEvent, from: BrainState = BrainState()): BrainState =
        events.fold(from) { s, e -> Brain.reduce(s, e, settings) }

    private fun mode(state: BrainState, light: LightConfig, now: Long) =
        Brain.decide(state, light, settings, now).mode

    @Test
    fun noRideMeansNoControl() {
        assertNull(mode(BrainState(), varia, 0))
    }

    @Test
    fun dayRideKeepsRearOffAndFlashesOnApproach() {
        var s = run(BrainEvent.RideChanged(RidePhase.RECORDING, 0), BrainEvent.RadarSample(0, 0))
        assertEquals(MODE_OFF, mode(s, varia, 100))

        s = run(BrainEvent.RadarSample(1, 1_000), from = s)
        assertEquals("FAST_FLASH", mode(s, varia, 1_000))

        // Vehicle gone: warning holds for 3 s, then back to the day mode.
        s = run(BrainEvent.RadarSample(0, 2_000), from = s)
        assertEquals("FAST_FLASH", mode(s, varia, 2_000))
        assertEquals("FAST_FLASH", mode(s, varia, 3_900))
        s = run(BrainEvent.RadarSample(0, 4_100), from = s)
        assertEquals(MODE_OFF, mode(s, varia, 4_100))
    }

    @Test
    fun newVehicleDuringHoldRestartsHold() {
        var s = run(
            BrainEvent.RideChanged(RidePhase.RECORDING, 0),
            BrainEvent.RadarSample(1, 0),
            BrainEvent.RadarSample(0, 1_000),
            BrainEvent.RadarSample(2, 2_500),
            BrainEvent.RadarSample(0, 3_000),
        )
        // Hold counts from the last threat at 2.5 s, not from the first one.
        assertEquals("FAST_FLASH", mode(s, varia, 5_400))
        s = run(BrainEvent.RadarSample(0, 5_600), from = s)
        assertEquals(MODE_OFF, mode(s, varia, 5_600))
    }

    @Test
    fun frontLightIgnoresRadar() {
        val s = run(BrainEvent.RideChanged(RidePhase.RECORDING, 0), BrainEvent.RadarSample(2, 0))
        assertEquals(MODE_OFF, mode(s, evo, 0))
    }

    @Test
    fun watchdogEndsStuckWarning() {
        // Radar stream stops while it still reported a vehicle.
        val s = run(BrainEvent.RideChanged(RidePhase.RECORDING, 0), BrainEvent.RadarSample(1, 1_000))
        assertEquals("FAST_FLASH", mode(s, varia, 5_000))
        // 5 s watchdog plus nothing new: threat is stale, and the hold from 1 s has passed.
        assertEquals(MODE_OFF, mode(s, varia, 6_100))
    }

    @Test
    fun nightUsesNightModeAndRadarStillWarns() {
        var s = run(BrainEvent.RideChanged(RidePhase.RECORDING, 0), BrainEvent.ZoneChanged(Zone.NIGHT))
        assertEquals("SLOW_FLASH", mode(s, varia, 0))
        assertEquals("STEADY_50", mode(s, evo, 0))
        s = run(BrainEvent.RadarSample(1, 10), from = s)
        assertEquals("FAST_FLASH", mode(s, varia, 10))
    }

    @Test
    fun externalOffIsKeptAfterWarning() {
        // The LightONKaroo bug: light switched off in the Control Center, then a car passes.
        var s = run(
            BrainEvent.RideChanged(RidePhase.RECORDING, 0),
            BrainEvent.ZoneChanged(Zone.NIGHT),
            BrainEvent.ExternalChange("varia", MODE_OFF),
        )
        assertEquals(MODE_OFF, mode(s, varia, 0))
        s = run(BrainEvent.RadarSample(1, 1_000), from = s)
        assertEquals("FAST_FLASH", mode(s, varia, 1_000))
        s = run(BrainEvent.RadarSample(0, 2_000), from = s)
        // After the hold the light goes back to OFF, not to the night mode.
        assertEquals(MODE_OFF, mode(s, varia, 5_100))
    }

    @Test
    fun zoneChangeEndsManualAndPins() {
        var s = run(
            BrainEvent.RideChanged(RidePhase.RECORDING, 0),
            BrainEvent.ManualSet(ManualMode.OFF),
            BrainEvent.ExternalChange("evo", "STEADY_100"),
        )
        assertEquals(ManualMode.OFF, s.manual)
        s = run(BrainEvent.ZoneChanged(Zone.NIGHT), from = s)
        assertEquals(ManualMode.AUTO, s.manual)
        assertEquals("STEADY_50", mode(s, evo, 0))
    }

    @Test
    fun manualOffStillWarnsHardOffDoesNot() {
        var s = run(
            BrainEvent.RideChanged(RidePhase.RECORDING, 0),
            BrainEvent.ZoneChanged(Zone.NIGHT),
            BrainEvent.ManualSet(ManualMode.OFF),
            BrainEvent.RadarSample(1, 0),
        )
        assertEquals("FAST_FLASH", mode(s, varia, 0))
        s = run(BrainEvent.ManualSet(ManualMode.HARD_OFF), from = s)
        assertEquals(MODE_OFF, mode(s, varia, 0))
    }

    @Test
    fun manualCycleOrder() {
        var s = BrainState()
        val seen = mutableListOf<ManualMode>()
        repeat(5) {
            s = run(BrainEvent.ManualCycle, from = s)
            seen += s.manual
        }
        assertEquals(
            listOf(ManualMode.PELOTON, ManualMode.ON, ManualMode.OFF, ManualMode.HARD_OFF, ManualMode.AUTO),
            seen,
        )
    }

    @Test
    fun manualCycleSkipsPelotonWhenSwitchedOff() {
        val off = settings.copy(pelotonEnabled = false)
        var s = BrainState()
        val seen = mutableListOf<ManualMode>()
        repeat(4) {
            s = Brain.reduce(s, BrainEvent.ManualCycle, off)
            seen += s.manual
        }
        assertEquals(listOf(ManualMode.ON, ManualMode.OFF, ManualMode.HARD_OFF, ManualMode.AUTO), seen)
    }

    @Test
    fun pelotonWarnsSteadyAndKeepsAutomatic() {
        var s = run(
            BrainEvent.RideChanged(RidePhase.RECORDING, 0),
            BrainEvent.ManualSet(ManualMode.PELOTON),
            BrainEvent.RadarSample(0, 0),
        )
        // By day the rear stays off as in automatic mode.
        assertEquals(MODE_OFF, mode(s, varia, 100))
        // Vehicle: steady instead of flashing, for the same hold time.
        s = run(BrainEvent.RadarSample(1, 1_000), from = s)
        assertEquals("STEADY4", mode(s, varia, 1_000))
        s = run(BrainEvent.RadarSample(0, 2_000), from = s)
        assertEquals("STEADY4", mode(s, varia, 3_900))
        s = run(BrainEvent.RadarSample(0, 4_100), from = s)
        assertEquals(MODE_OFF, mode(s, varia, 4_100))
        // Night: normal night mode, peloton survives the zone change.
        s = run(BrainEvent.ZoneChanged(Zone.NIGHT), from = s)
        assertEquals(ManualMode.PELOTON, s.manual)
        assertEquals("SLOW_FLASH", mode(s, varia, 5_000))
        assertEquals("STEADY_50", mode(s, evo, 5_000))
    }

    @Test
    fun pelotonSwitchedOffActsLikeAutomatic() {
        val off = settings.copy(pelotonEnabled = false)
        val s = listOf(
            BrainEvent.RideChanged(RidePhase.RECORDING, 0),
            BrainEvent.ManualSet(ManualMode.PELOTON),
            BrainEvent.RadarSample(1, 1_000),
        ).fold(BrainState()) { acc, e -> Brain.reduce(acc, e, off) }
        assertEquals("FAST_FLASH", Brain.decide(s, varia, off, 1_000).mode)
    }

    @Test
    fun pausesStillWinInPeloton() {
        val s = run(
            BrainEvent.RideChanged(RidePhase.RECORDING, 0),
            BrainEvent.ManualSet(ManualMode.PELOTON),
            BrainEvent.RadarSample(1, 1_000),
            BrainEvent.RideChanged(RidePhase.PAUSED_MANUAL, 1_000),
        )
        assertEquals(MODE_OFF, mode(s, varia, 1_000))
    }

    @Test
    fun manualOnByDayUsesNightMode() {
        val s = run(BrainEvent.RideChanged(RidePhase.RECORDING, 0), BrainEvent.ManualSet(ManualMode.ON))
        assertEquals("SLOW_FLASH", mode(s, varia, 0))
    }

    @Test
    fun pauseButtonSwitchesOffAndSilencesRadar() {
        val s = run(
            BrainEvent.RideChanged(RidePhase.RECORDING, 0),
            BrainEvent.ZoneChanged(Zone.NIGHT),
            BrainEvent.RideChanged(RidePhase.PAUSED_MANUAL, 1_000),
            BrainEvent.RadarSample(2, 1_500),
        )
        assertEquals(MODE_OFF, mode(s, varia, 1_500))
        assertEquals(MODE_OFF, mode(s, evo, 1_500))
    }

    @Test
    fun shortAutoPauseKeepsLightsAndRadar() {
        val s = run(
            BrainEvent.RideChanged(RidePhase.RECORDING, 0),
            BrainEvent.ZoneChanged(Zone.NIGHT),
            BrainEvent.RideChanged(RidePhase.PAUSED_AUTO, 10_000),
        )
        assertEquals("SLOW_FLASH", mode(s, varia, 60_000))
        val warned = run(BrainEvent.RadarSample(1, 60_000), from = s)
        assertEquals("FAST_FLASH", mode(warned, varia, 60_000))
        // After 2 min of auto-pause everything is off.
        assertEquals(MODE_OFF, mode(s, varia, 130_000))
        assertEquals(MODE_OFF, mode(warned, varia, 130_000))
    }

    @Test
    fun resumeAfterPauseRestoresMode() {
        val s = run(
            BrainEvent.RideChanged(RidePhase.RECORDING, 0),
            BrainEvent.ZoneChanged(Zone.NIGHT),
            BrainEvent.RideChanged(RidePhase.PAUSED_MANUAL, 1_000),
            BrainEvent.RideChanged(RidePhase.RECORDING, 2_000),
        )
        assertEquals("SLOW_FLASH", mode(s, varia, 2_000))
    }

    @Test
    fun rideEndSwitchesOffOnceThenReleases() {
        var s = run(BrainEvent.RideChanged(RidePhase.RECORDING, 0), BrainEvent.RideChanged(RidePhase.IDLE, 1_000))
        assertEquals(MODE_OFF, mode(s, varia, 1_000))
        s = run(BrainEvent.RideChanged(RidePhase.RECORDING, 2_000), from = s)
        assertEquals(false, s.rideEnded)
    }

    @Test
    fun batterySaverOnlyBelowThreshold() {
        var s = run(BrainEvent.RideChanged(RidePhase.RECORDING, 0), BrainEvent.ZoneChanged(Zone.NIGHT))
        s = run(BrainEvent.Battery("varia", 50), from = s)
        assertEquals("SLOW_FLASH", mode(s, varia, 0))
        s = run(BrainEvent.Battery("varia", 20), from = s)
        assertEquals("STEADY5", mode(s, varia, 0))
        assertEquals(Reason.ZONE_SAVER, Brain.decide(s, varia, settings, 0).reason)
        // Saver switched off in the settings.
        val off = Settings(batterySaverEnabled = false)
        assertEquals("SLOW_FLASH", Brain.decide(s, varia, off, 0).mode)
    }

    @Test
    fun saverNeverSwitchesOn() {
        val s = run(BrainEvent.RideChanged(RidePhase.RECORDING, 0), BrainEvent.Battery("varia", 5))
        assertEquals(MODE_OFF, mode(s, varia, 0))
    }

    @Test
    fun frontFieldCyclesBrightness() {
        var s = run(BrainEvent.RideChanged(RidePhase.RECORDING, 0), BrainEvent.ZoneChanged(Zone.NIGHT))
        s = run(BrainEvent.FieldCycle(evo), from = s)
        assertEquals("STEADY_25", mode(s, evo, 0))
        s = run(BrainEvent.FieldCycle(evo), BrainEvent.FieldCycle(evo), BrainEvent.FieldCycle(evo), from = s)
        assertEquals("STEADY_25", mode(s, evo, 0))
        // The rear light is not affected.
        assertEquals("SLOW_FLASH", mode(s, varia, 0))
    }
}
