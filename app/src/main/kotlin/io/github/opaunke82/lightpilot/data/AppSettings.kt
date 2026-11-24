package io.github.opaunke82.lightpilot.data

import io.github.opaunke82.lightpilot.LightPilotExtension
import io.github.opaunke82.lightpilot.ant.LightMode
import io.github.opaunke82.lightpilot.ble.MagicshineDeviceConfig
import io.github.opaunke82.lightpilot.ble.MagicshineDeviceModeProvider
import io.github.opaunke82.lightpilot.ble.magicshineModeName
import io.github.opaunke82.lightpilot.core.Beam
import io.github.opaunke82.lightpilot.core.LightConfig
import io.github.opaunke82.lightpilot.core.MODE_OFF
import io.github.opaunke82.lightpilot.core.Role
import io.github.opaunke82.lightpilot.core.Settings
import io.github.opaunke82.lightpilot.core.Zone
import kotlinx.serialization.Serializable

/** The copied KarooFirefly sensor classes speak of DayTimeZone; it is the core Zone. */
typealias DayTimeZone = Zone

@Serializable
enum class LightProtocol { ANT_PLUS, BLE }

@Serializable
enum class LightRole {
    FRONT, REAR;

    fun toCore(): Role = if (this == FRONT) Role.FRONT else Role.REAR
}

/** Settings of one light as stored on the device. */
@Serializable
data class LightSetup(
    val deviceId: String,
    val deviceName: String,
    val protocol: LightProtocol,
    val role: LightRole,
    val dayMode: String = MODE_OFF,
    val nightMode: String = MODE_OFF,
    val warnMode: String = LightMode.FAST_FLASH.karooName,
    /** Radar warning in peloton mode: steady instead of flashing. */
    val pelotonWarnMode: String = LightMode.STEADY_HIGH.karooName,
    val radarWarning: Boolean = false,
    val saverDayMode: String? = null,
    val saverNightMode: String? = null,
    val fieldModes: List<String> = emptyList(),
) {
    fun toCore() = LightConfig(
        id = deviceId,
        role = role.toCore(),
        dayMode = dayMode,
        nightMode = nightMode,
        warnMode = warnMode,
        pelotonWarnMode = pelotonWarnMode,
        radarWarning = radarWarning && role == LightRole.REAR,
        saverDayMode = saverDayMode,
        saverNightMode = saverNightMode,
        fieldModes = fieldModes,
    )

    companion object {
        /** Defaults from the requirements protocol (T3, T4, W2, N4, N5, N7). */
        fun defaultsFor(deviceId: String, deviceName: String, protocol: LightProtocol, role: LightRole): LightSetup =
            when {
                role == LightRole.REAR && protocol == LightProtocol.ANT_PLUS -> LightSetup(
                    deviceId, deviceName, protocol, role,
                    dayMode = MODE_OFF,
                    nightMode = LightMode.SLOW_FLASH.karooName,
                    warnMode = LightMode.FAST_FLASH.karooName,
                    radarWarning = true,
                    saverNightMode = LightMode.STEADY_LOW.karooName,
                )
                protocol == LightProtocol.BLE && isTwoGroupMagicshine(deviceName) -> LightSetup(
                    deviceId, deviceName, protocol, role,
                    dayMode = MODE_OFF,
                    nightMode = "LOW_60",
                    warnMode = "FLASH_100",
                    pelotonWarnMode = "LOW_100",
                    radarWarning = false,
                    saverNightMode = "LOW_30",
                    fieldModes = DEFAULT_BEAM_FIELD_MODES,
                )
                protocol == LightProtocol.BLE -> LightSetup(
                    deviceId, deviceName, protocol, role,
                    dayMode = MODE_OFF,
                    nightMode = "STEADY_50",
                    warnMode = "FLASH_100",
                    pelotonWarnMode = "STEADY_100",
                    radarWarning = false,
                    saverNightMode = "STEADY_10",
                    fieldModes = listOf("STEADY_25", "STEADY_50", "STEADY_100", MODE_OFF),
                )
                else -> LightSetup(
                    deviceId, deviceName, protocol, role,
                    dayMode = MODE_OFF,
                    nightMode = LightMode.STEADY_HIGH.karooName,
                    radarWarning = role == LightRole.REAR,
                )
            }
    }
}

@Serializable
data class AppSettings(
    val lights: List<LightSetup> = emptyList(),
    val useTimeBased: Boolean = true,
    /** Minutes added to sunrise for the start of the day zone (negative = earlier). */
    val dawnOffsetMinutes: Int = 0,
    /** Minutes added to sunset for the start of the night zone (negative = earlier, T1). */
    val duskOffsetMinutes: Int = -30,
    /** Tunnel mode (ambient light sensor). Off by default; new keys since v0.3.1. */
    val tunnelEnabled: Boolean = false,
    val tunnelOnBelowLux: Int = 300,
    val tunnelOffAboveLux: Int = 600,
    val tunnelOnDelaySeconds: Int = 2,
    val tunnelOffDelaySeconds: Int = 10,
    val radarMinThreatLevel: Int = 1,
    val radarHoldSeconds: Int = 3,
    val radarWatchdogSeconds: Int = 5,
    val autoPauseOffMinutes: Int = 2,
    val batterySaverEnabled: Boolean = true,
    val batterySaverThresholdPct: Int = 20,
    val offAfterRide: Boolean = true,
    val zoneNotifications: Boolean = true,
    /** Offer the peloton mode on the data field. */
    val pelotonEnabled: Boolean = true,
    /** Front field: taps within this time step on, after a longer break the first tap is "off". 0 = off. */
    val frontCycleWindowSeconds: Int = 5,
    /** Shimano Di2 hood buttons through Ki2. Off by default. */
    val di2Enabled: Boolean = false,
    /** "<channel>:<SINGLE|DOUBLE|HOLD>" -> Di2Action name. Missing = no action. */
    val di2Actions: Map<String, String> = emptyMap(),
) {
    fun toCore() = Settings(
        radarMinThreatLevel = radarMinThreatLevel.coerceIn(1, 3),
        radarHoldMs = radarHoldSeconds.coerceIn(0, 10) * 1_000L,
        radarWatchdogMs = radarWatchdogSeconds.coerceIn(2, 30) * 1_000L,
        autoPauseOffDelayMs = autoPauseOffMinutes.coerceIn(0, 5) * 60_000L,
        batterySaverEnabled = batterySaverEnabled,
        batterySaverThresholdPct = batterySaverThresholdPct.coerceIn(5, 50),
        offAfterRide = offAfterRide,
        pelotonEnabled = pelotonEnabled,
        fieldCycleWindowMs = frontCycleWindowSeconds.coerceIn(0, 10) * 1_000L,
    )

    fun withLight(setup: LightSetup?, deviceId: String): AppSettings =
        copy(lights = lights.filter { it.deviceId != deviceId } + listOfNotNull(setup))
}

data class LightModeOption(
    val id: String,
    val displayName: String,
)

interface LightModeProvider {
    fun availableModes(): List<LightModeOption>
}

/** German names for the ANT+ modes, checked on the Varia RTL515 (17.11.2026). */
private fun antDisplayName(mode: LightMode): String = when (mode) {
    LightMode.OFF -> "Aus"
    LightMode.STEADY_HIGH -> "Dauerlicht hell"
    LightMode.STEADY_LOW -> "Dauerlicht gedimmt"
    LightMode.SLOW_FLASH -> "Langsam blinken (Night Flash)"
    LightMode.FAST_FLASH -> "Schnell blinken (Day Flash)"
    else -> mode.displayName
}

object AntPlusModeProvider : LightModeProvider {
    override fun availableModes(): List<LightModeOption> =
        LightMode.FALLBACK_MODES.map { LightModeOption(it.karooName, antDisplayName(it)) }
}

class DynamicAntPlusModeProvider(private val karooNames: Set<String>) : LightModeProvider {
    override fun availableModes(): List<LightModeOption> {
        val modes = karooNames.mapNotNull { name ->
            LightMode.fromKarooName(name)?.let { LightModeOption(it.karooName, antDisplayName(it)) }
        }.sortedBy { LightMode.fromKarooName(it.id)?.modeNumber ?: Int.MAX_VALUE }
        return if (modes.any { it.id == MODE_OFF }) modes else listOf(LightModeOption(MODE_OFF, "Aus")) + modes
    }
}

fun modeProviderFor(protocol: LightProtocol, deviceId: String? = null): LightModeProvider {
    val ext = LightPilotExtension.getInstance()
    return when (protocol) {
        LightProtocol.ANT_PLUS -> {
            val supported = deviceId?.let { ext?.lightControl?.supportedModes?.value?.get(it) }
            if (supported != null) DynamicAntPlusModeProvider(supported) else AntPlusModeProvider
        }
        LightProtocol.BLE -> {
            val config = deviceId?.let { ext?.magicshineController?.getDeviceConfig(it) }
            MagicshineDeviceModeProvider(config ?: MagicshineDeviceConfig.forDevice("M2"))
        }
    }
}

fun modeDisplayName(protocol: LightProtocol, deviceId: String?, modeId: String?): String {
    if (modeId == null) return "–"
    return modeProviderFor(protocol, deviceId).availableModes().find { it.id == modeId }?.displayName
        ?: if (protocol == LightProtocol.BLE) magicshineModeName(modeId) else modeId
}

/** Magicshine lamps with two light groups (low and high beam) are named "M2-…". */
fun isTwoGroupMagicshine(deviceName: String): Boolean = deviceName.getOrNull(1) != '1'

/** Front field default for lamps with low and high beam. */
val DEFAULT_BEAM_FIELD_MODES = listOf("LOW_30", "LOW_60", "LOW_100", "HIGH_50", "HIGH_100", MODE_OFF)

fun LightSetup.hasBeams(): Boolean =
    (fieldModes + listOf(dayMode, nightMode)).any { Beam.of(it) != null }

/**
 * Up to v0.1.x a two-group Magicshine was set with "STEADY_x", which switched its high beam.
 * From v0.2.0 the modes name the group; old settings start in low beam (user's wish:
 * never start with high beam). The field steps become the new default list.
 */
fun AppSettings.migrated(): AppSettings {
    var changed = false
    val lights = lights.map { l ->
        if (l.protocol != LightProtocol.BLE || !isTwoGroupMagicshine(l.deviceName)) return@map l
        fun m(mode: String) = if (mode.startsWith("STEADY_")) "LOW_" + mode.removePrefix("STEADY_") else mode
        val fieldModes = if (l.fieldModes.any { it.startsWith("STEADY_") }) {
            DEFAULT_BEAM_FIELD_MODES.filter { it != MODE_OFF || MODE_OFF in l.fieldModes }
        } else {
            l.fieldModes
        }
        val n = l.copy(
            dayMode = m(l.dayMode),
            nightMode = m(l.nightMode),
            warnMode = m(l.warnMode),
            pelotonWarnMode = m(l.pelotonWarnMode),
            saverDayMode = l.saverDayMode?.let { m(it) },
            saverNightMode = l.saverNightMode?.let { m(it) },
            fieldModes = fieldModes,
        )
        if (n != l) changed = true
        n
    }
    return if (changed) copy(lights = lights) else this
}
