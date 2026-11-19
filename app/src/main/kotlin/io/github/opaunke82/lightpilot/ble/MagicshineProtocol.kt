// Derived from KarooFirefly (https://github.com/derstrassi/karoofirefly),
// Copyright (c) 2026 derstrassi, MIT License. Adapted for LightPilot.

package io.github.opaunke82.lightpilot.ble

import io.github.opaunke82.lightpilot.data.LightModeOption
import io.github.opaunke82.lightpilot.data.LightModeProvider

object MagicshineProtocol {

    const val SERVICE_UUID = "0000FFE1-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "0000FFE0-0000-1000-8000-00805f9b34fb"

    val SUPPORTED_NAME_PREFIXES = setOf("M1-", "M2-", "M3-")

    const val MODE_STEADY = 1
    const val MODE_SLOW_FLASH = 2
    const val MODE_FAST_FLASH = 3
    const val MODE_SOS = 4

    private const val FLAG_SAVE: Byte = 0xBB.toByte()

    fun buildBrightCommand(
        presetIndex: Int,
        channel: Int,
        model: Int,
        bright: Int,
    ): ByteArray {
        val content = ByteArray(14)
        content[0] = presetIndex.toByte()
        val offset = channel * 3 + 1
        content[offset] = 0x01
        content[offset + 1] = model.toByte()
        content[offset + 2] = bright.toByte()
        content[13] = FLAG_SAVE
        return buildFrame(0xA2.toByte(), 0x01, content)
    }

    fun buildOffCommand(channel: Int = 0): ByteArray {
        val content = ByteArray(14)
        content[0] = 0x01
        val offset = channel * 3 + 1
        content[offset] = 0x01
        content[offset + 1] = 0x01
        content[offset + 2] = 0x00
        content[13] = FLAG_SAVE
        return buildFrame(0xA2.toByte(), 0x01, content)
    }

    fun buildModule2Frame(modeCode: Int, value: Int): ByteArray {
        val checksum = value xor (modeCode + 0x04)
        val hex = "DE14A2010200010A01%02X%02X000000000000BB%02XED".format(modeCode, value, checksum)
        return hexStringToBytes(hex)
    }

    val MODULE2_OFF = hexStringToBytes("DE14A20101010100000000000000000000BB0DED")

    /**
     * Two-group lamps (EVO 1700): one command carries both light groups, each as
     * (on, mode, brightness %). Group 1 = low beam, group 2 = high beam. [slot] is the
     * position in the Magicshine app's list (low beam 01, high beam 03), as the app sends it.
     * See docs/magicshine-evo1700-protocol.md.
     */
    fun buildGroupCommand(slot: Int, low: Pair<Int, Int>?, high: Pair<Int, Int>?): ByteArray {
        val content = ByteArray(14)
        content[0] = slot.toByte()
        low?.let { (mode, bright) ->
            content[1] = 0x01; content[2] = mode.toByte(); content[3] = bright.coerceIn(0, 100).toByte()
        }
        high?.let { (mode, bright) ->
            content[4] = 0x01; content[5] = mode.toByte(); content[6] = bright.coerceIn(0, 100).toByte()
        }
        content[13] = FLAG_SAVE
        return buildFrame(0xA2.toByte(), 0x01, content)
    }

    /** Mode codes of the A2 command (02 = SOS, 03 = flash, seen in the app on 19.11.2026). */
    const val M2_STEADY = 0x01
    const val M2_SOS = 0x02
    const val M2_FLASH = 0x03

    private fun buildFrame(type: Byte, status: Byte, content: ByteArray): ByteArray {
        val totalLen = 6 + content.size
        val frame = ByteArray(totalLen)
        frame[0] = 0xDE.toByte()
        frame[1] = totalLen.toByte()
        frame[2] = type
        frame[3] = status
        System.arraycopy(content, 0, frame, 4, content.size)

        var cs: Byte = frame[1]
        for (i in 2 until totalLen - 2) {
            cs = (cs.toInt() xor frame[i].toInt()).toByte()
        }
        frame[totalLen - 2] = cs
        frame[totalLen - 1] = 0xED.toByte()
        return frame
    }

    fun buildQuery(type: Byte): ByteArray = buildFrame(type, 0x00, byteArrayOf())

    fun hexStringToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}

enum class MagicshineModuleType { M1, M2 }

data class MagicshineDeviceConfig(
    val moduleType: MagicshineModuleType,
    val modes: List<LightModeOption>,
) {
    fun buildCommand(modeId: String): ByteArray? {
        if (modeId == "OFF") return when (moduleType) {
            MagicshineModuleType.M1 -> MagicshineProtocol.buildOffCommand(1)
            MagicshineModuleType.M2 -> MagicshineProtocol.MODULE2_OFF
        }
        val parts = modeId.split("_", limit = 2)
        if (parts.size != 2) return null
        val bright = parts[1].toIntOrNull() ?: return null

        return when (moduleType) {
            MagicshineModuleType.M1 -> {
                val model = when (parts[0]) {
                    "STEADY", "LOW", "HIGH" -> MagicshineProtocol.MODE_STEADY
                    "FLASH" -> MagicshineProtocol.MODE_FAST_FLASH
                    "SOS" -> MagicshineProtocol.MODE_SOS
                    else -> return null
                }
                MagicshineProtocol.buildBrightCommand(1, 1, model, bright)
            }
            MagicshineModuleType.M2 -> when (parts[0]) {
                // Low beam is group 1, high beam group 2. "STEADY" (before v0.2.0) is low beam.
                "LOW", "STEADY" -> MagicshineProtocol.buildGroupCommand(1, MagicshineProtocol.M2_STEADY to bright, null)
                "HIGH" -> MagicshineProtocol.buildGroupCommand(3, null, MagicshineProtocol.M2_STEADY to bright)
                "FLASH" -> MagicshineProtocol.buildGroupCommand(1, MagicshineProtocol.M2_FLASH to bright, null)
                "SOS" -> MagicshineProtocol.buildGroupCommand(1, MagicshineProtocol.M2_SOS to bright, null)
                else -> null
            }
        }
    }

    companion object {
        private val BRIGHTNESS_STEPS = listOf(10, 25, 50, 100)
        val TEN_STEPS = (10..100 step 10).toList()

        fun forDevice(bleName: String): MagicshineDeviceConfig {
            // The digit after "M" is the device's lamp group count; only single-group
            // devices use the M1 layout. See Magicshine app, FrontBikeLightActivity:580.
            val lampGroups = bleName.getOrNull(1)?.digitToIntOrNull()
            val moduleType = if (lampGroups == 1) {
                MagicshineModuleType.M1
            } else {
                MagicshineModuleType.M2
            }

            val modes = mutableListOf(LightModeOption("OFF", "Aus"))
            if (moduleType == MagicshineModuleType.M2) {
                for (b in TEN_STEPS) modes.add(LightModeOption("LOW_$b", magicshineModeName("LOW_$b")))
                for (b in TEN_STEPS) modes.add(LightModeOption("HIGH_$b", magicshineModeName("HIGH_$b")))
            } else {
                for (b in BRIGHTNESS_STEPS) modes.add(LightModeOption("STEADY_$b", magicshineModeName("STEADY_$b")))
            }
            for (b in BRIGHTNESS_STEPS) {
                modes.add(LightModeOption("FLASH_$b", magicshineModeName("FLASH_$b")))
            }
            for (b in BRIGHTNESS_STEPS) {
                modes.add(LightModeOption("SOS_$b", magicshineModeName("SOS_$b")))
            }

            return MagicshineDeviceConfig(moduleType = moduleType, modes = modes)
        }
    }
}

class MagicshineDeviceModeProvider(private val config: MagicshineDeviceConfig) : LightModeProvider {
    override fun availableModes(): List<LightModeOption> = config.modes
}

/** German name of a Magicshine mode, also for values set with the slider (e.g. LOW_35). */
fun magicshineModeName(id: String): String {
    if (id == "OFF") return "Aus"
    val parts = id.split("_", limit = 2)
    val pct = parts.getOrNull(1)?.toIntOrNull() ?: return id
    return when (parts[0]) {
        "LOW" -> "Abblend $pct %"
        "HIGH" -> "Fernlicht $pct %"
        "STEADY" -> "Dauerlicht $pct %"
        "FLASH" -> "Blinken $pct %"
        "SOS" -> "SOS $pct %"
        else -> id
    }
}
