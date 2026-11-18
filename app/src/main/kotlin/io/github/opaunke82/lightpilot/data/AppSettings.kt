package io.github.opaunke82.lightpilot.data

import io.github.opaunke82.lightpilot.LightPilotExtension
import io.github.opaunke82.lightpilot.ant.LightMode
import io.github.opaunke82.lightpilot.ble.MagicshineDeviceConfig
import io.github.opaunke82.lightpilot.ble.MagicshineDeviceModeProvider
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
                protocol == LightProtocol.BLE -> LightSetup(
                    deviceId, deviceName, protocol, role,
                    dayMode = MODE_OFF,
                    nightMode = "STEADY_50",
                    warnMode = "FLASH_100",
                    radarWarning = false,
                    saverNightMode = "STEADY_10",
                    fieldModes = listOf("STEADY_25", "STEADY_50", "STEADY_100"),
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
    val useAmbientLight: Boolean = false,
    val ambientNightThresholdLux: Int = 50,
    val radarMinThreatLevel: Int = 1,
    val radarHoldSeconds: Int = 3,
    val radarWatchdogSeconds: Int = 5,
    val autoPauseOffMinutes: Int = 2,
    val batterySaverEnabled: Boolean = true,
    val batterySaverThresholdPct: Int = 20,
    val offAfterRide: Boolean = true,
    val zoneNotifications: Boolean = true,
) {
    fun toCore() = Settings(
        radarMinThreatLevel = radarMinThreatLevel.coerceIn(1, 3),
        radarHoldMs = radarHoldSeconds.coerceIn(0, 10) * 1_000L,
        radarWatchdogMs = radarWatchdogSeconds.coerceIn(2, 30) * 1_000L,
        autoPauseOffDelayMs = autoPauseOffMinutes.coerceIn(0, 5) * 60_000L,
        batterySaverEnabled = batterySaverEnabled,
        batterySaverThresholdPct = batterySaverThresholdPct.coerceIn(5, 50),
        offAfterRide = offAfterRide,
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
    return modeProviderFor(protocol, deviceId).availableModes().find { it.id == modeId }?.displayName ?: modeId
}
