// Originally derived from KarooFirefly (https://github.com/derstrassi/karoofirefly),
// Copyright (c) 2026 derstrassi, MIT License. Rewritten for LightPilot (tunnel mode).

package io.github.opaunke82.lightpilot.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.opaunke82.lightpilot.core.Zone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.Locale

/**
 * Tunnel mode: the Karoo's ambient light sensor switches to night in tunnels and dark forest.
 *
 * The sensor (Lite-On LTR-X130A on the Karoo 3) reports only when the value changes, so the
 * last value is held and evaluated every [TICK_MS] instead of on sensor events. That gives
 * fixed times:
 *  - DAY → NIGHT when the light stays below [onBelowLux] for [onDelayMs],
 *  - NIGHT → DAY when it stays above [offAboveLux] for [offDelayMs] (hysteresis between both).
 * Runs only while [setActive] says so (tunnel mode on and lights needed or settings open).
 */
class AmbientLightSensor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val handler = Handler(Looper.getMainLooper())

    val isAvailable: Boolean get() = lightSensor != null

    var onBelowLux = 300
    var offAboveLux = 600
    var onDelayMs = 2_000L
    var offDelayMs = 10_000L

    private val _currentLux = MutableStateFlow(0f)
    val currentLux: StateFlow<Float> = _currentLux

    private val _currentLightZone = MutableStateFlow(Zone.DAY)
    val currentLightZone: StateFlow<Zone> = _currentLightZone

    private val _live = MutableStateFlow("aus")
    /** Live state for the test section, e.g. "212 lx · Tag · dunkel seit 1,2 s → Nacht in 0,8 s". */
    val live: StateFlow<String> = _live

    @Volatile private var lastLux: Float? = null
    private var darkSinceMs: Long? = null
    private var brightSinceMs: Long? = null
    private var active = false

    private val tick = object : Runnable {
        override fun run() {
            evaluate(SystemClock.elapsedRealtime())
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Start or stop listening and evaluating (any thread). Stopping resets the zone to day. */
    fun setActive(on: Boolean) {
        handler.post { applyActive(on) }
    }

    private fun applyActive(on: Boolean) {
        if (on == active) return
        if (on) {
            if (lightSensor == null) {
                Timber.w("AmbientLightSensor: no light sensor on this device")
                _live.value = "kein Lichtsensor vorhanden"
                return
            }
            // On-change sensors deliver the current value right after registering.
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
            handler.post(tick)
            active = true
            Timber.d("AmbientLightSensor: started")
        } else {
            sensorManager.unregisterListener(this)
            handler.removeCallbacks(tick)
            active = false
            lastLux = null
            darkSinceMs = null
            brightSinceMs = null
            _currentLightZone.value = Zone.DAY
            _live.value = "aus"
            Timber.d("AmbientLightSensor: stopped")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values[0]
        lastLux = lux
        _currentLux.value = lux
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun evaluate(now: Long) {
        val lux = lastLux
        if (lux == null) {
            _live.value = "warte auf ersten Sensorwert"
            return
        }
        val zone = _currentLightZone.value
        var detail = ""
        if (zone == Zone.DAY) {
            brightSinceMs = null
            if (lux < onBelowLux) {
                val since = darkSinceMs ?: now.also { darkSinceMs = it }
                val left = onDelayMs - (now - since)
                if (left <= 0) {
                    switchTo(Zone.NIGHT, lux)
                    detail = ""
                } else {
                    detail = " · dunkel seit ${sec(now - since)} → Nacht in ${sec(left)}"
                }
            } else {
                darkSinceMs = null
            }
        } else {
            darkSinceMs = null
            if (lux > offAboveLux) {
                val since = brightSinceMs ?: now.also { brightSinceMs = it }
                val left = offDelayMs - (now - since)
                if (left <= 0) {
                    switchTo(Zone.DAY, lux)
                    detail = ""
                } else {
                    detail = " · hell seit ${sec(now - since)} → Tag in ${sec(left)}"
                }
            } else {
                brightSinceMs = null
            }
        }
        val shown = if (_currentLightZone.value == Zone.NIGHT) "Nacht" else "Tag"
        _live.value = "%.0f lx · %s%s".format(Locale.GERMANY, lux, shown, detail)
    }

    private fun switchTo(zone: Zone, lux: Float) {
        Timber.d("AmbientLightSensor: zone ${_currentLightZone.value} → $zone (%.0f lx)".format(Locale.US, lux))
        _currentLightZone.value = zone
        darkSinceMs = null
        brightSinceMs = null
    }

    private fun sec(ms: Long) = "%.1f s".format(Locale.GERMANY, ms / 1000.0)

    companion object {
        const val TICK_MS = 250L
    }
}
