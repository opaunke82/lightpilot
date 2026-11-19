package io.github.opaunke82.lightpilot.core

/** Mode name that switches a light off. Same string for ANT+ (Karoo enum name) and BLE. */
const val MODE_OFF = "OFF"

enum class Zone { DAY, NIGHT }

enum class Role { FRONT, REAR }

/** Ride state as reported by the Karoo, with the two pause kinds kept apart. */
enum class RidePhase { IDLE, RECORDING, PAUSED_AUTO, PAUSED_MANUAL }

/**
 * Global manual setting, cycled from the data field.
 * AUTO -> PELOTON -> ON -> OFF -> HARD_OFF -> AUTO (PELOTON only if offered).
 * PELOTON is automatic mode, except that the radar warning uses a steady mode
 * instead of flashing (riders behind are not dazzled by a flashing light).
 * HARD_OFF is the only setting that also suppresses the radar warning.
 */
enum class ManualMode {
    AUTO, PELOTON, ON, OFF, HARD_OFF;

    fun next(pelotonOffered: Boolean = true): ManualMode {
        val n = entries[(ordinal + 1) % entries.size]
        return if (n == PELOTON && !pelotonOffered) n.next(pelotonOffered) else n
    }
}

/** Where a per-light pinned mode came from. */
enum class PinSource {
    /** Mode was changed outside the app (Karoo Control Center, button on the light). */
    EXTERNAL,

    /** Mode was picked on the per-light data field (e.g. front light brightness). */
    FIELD,
}

data class Pin(val mode: String, val source: PinSource)

/**
 * Configuration of one light. Mode strings are protocol specific:
 * ANT+ uses Karoo enum names (OFF, STEADY4, STEADY5, SLOW_FLASH, FAST_FLASH),
 * Magicshine BLE uses e.g. STEADY_25.
 */
data class LightConfig(
    val id: String,
    val role: Role,
    val dayMode: String = MODE_OFF,
    val nightMode: String = MODE_OFF,
    /** Mode while the radar warns. Only used if [radarWarning] is true. */
    val warnMode: String = "FAST_FLASH",
    /** Mode while the radar warns in peloton mode: steady instead of flashing. */
    val pelotonWarnMode: String = "STEADY4",
    val radarWarning: Boolean = role == Role.REAR,
    /** Replacement modes when the battery is low and the saver is on. null = keep normal mode. */
    val saverDayMode: String? = null,
    val saverNightMode: String? = null,
    /** Modes the per-light data field cycles through (front brightness). Empty = no cycling. */
    val fieldModes: List<String> = emptyList(),
) {
    /** Mode used for manual "on": the night mode, falling back to the day mode. */
    val onMode: String
        get() = when {
            nightMode != MODE_OFF -> nightMode
            dayMode != MODE_OFF -> dayMode
            else -> MODE_OFF
        }
}

data class Settings(
    /** Lowest radar threat level that counts as "vehicle approaching". */
    val radarMinThreatLevel: Int = 1,
    /** Time the warning stays on after the last vehicle is gone. */
    val radarHoldMs: Long = 3_000,
    /** Without radar data for this long, the warning ends. */
    val radarWatchdogMs: Long = 5_000,
    /** Auto-pause longer than this switches the lights off. Manual pause switches off at once. */
    val autoPauseOffDelayMs: Long = 120_000,
    val batterySaverEnabled: Boolean = true,
    val batterySaverThresholdPct: Int = 20,
    /** Switch all lights off when the ride ends. */
    val offAfterRide: Boolean = true,
    /** Peloton mode is offered on the data field. Off: it is skipped and acts like automatic. */
    val pelotonEnabled: Boolean = true,
    /**
     * Front field: taps within this time step through the cycle. The first tap after a longer
     * break switches a light that is on off, and a light that is off on at the lowest ticked
     * step. 0 = no break rule, every tap is the next step.
     */
    val fieldCycleWindowMs: Long = 5_000,
)

/** Why a light gets its mode. Shown on the status page and written to the log. */
enum class Reason {
    NO_RIDE,
    RIDE_ENDED,
    MANUAL_PAUSE,
    HARD_OFF,
    LONG_AUTO_PAUSE,
    RADAR,
    RADAR_PELOTON,
    PINNED_EXTERNAL,
    PINNED_FIELD,
    MANUAL_ON,
    MANUAL_OFF,
    ZONE,
    ZONE_SAVER,
}

/** Desired mode for one light. [mode] null means: do not control this light now. */
data class Decision(val mode: String?, val reason: Reason)
