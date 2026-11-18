// Derived from KarooFirefly (https://github.com/derstrassi/karoofirefly),
// Copyright (c) 2026 derstrassi, MIT License. Adapted for LightPilot.

package io.github.opaunke82.lightpilot.engine

import ca.rmen.sunrisesunset.SunriseSunset
import io.github.opaunke82.lightpilot.data.DayTimeZone
import timber.log.Timber
import java.util.Calendar
import java.util.TimeZone

/**
 * Determines the current DayTimeZone based on GPS location and time.
 * Uses ca.rmen:lib-sunrise-sunset for local sunrise/sunset calculation.
 */
class TimeBasedController {

    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var lastSunrise: Calendar? = null
    private var lastSunset: Calendar? = null

    /** Where the position for the sun times comes from (shown in the app). */
    enum class Source { GPS, LAST_KNOWN, ESTIMATED, NONE }

    @Volatile var source: Source = Source.NONE
        private set

    /** Offset in minutes to extend dusk/dawn zone before/after sun events. */
    var dawnOffsetMinutes: Int = 30
    var duskOffsetMinutes: Int = 30

    /**
     * Update with new GPS coordinates. Recalculates sunrise/sunset.
     */
    fun onLocationUpdate(latitude: Double, longitude: Double) {
        lastLatitude = latitude
        lastLongitude = longitude
        source = Source.GPS
        recalculate()
    }

    /**
     * LightPilot: position to use until the first GPS fix (indoors, at the start of a ride).
     * Without it the zone would stay DAY, even at night.
     */
    fun setFallbackLocation(latitude: Double, longitude: Double, estimated: Boolean) {
        if (source == Source.GPS) return
        lastLatitude = latitude
        lastLongitude = longitude
        source = if (estimated) Source.ESTIMATED else Source.LAST_KNOWN
        recalculate()
    }

    private fun recalculate() {
        val lat = lastLatitude ?: return
        val lng = lastLongitude ?: return

        val now = Calendar.getInstance()
        val result = SunriseSunset.getSunriseSunset(
            now,
            lat,
            lng,
        )
        lastSunrise = result[0]
        lastSunset = result[1]

        Timber.d(
            "Sunrise/sunset calculated: sunrise=%s, sunset=%s",
            lastSunrise?.time,
            lastSunset?.time,
        )
    }

    fun getCurrentZone(): DayTimeZone {
        // LightPilot: the sun times belong to one date; recalculate after midnight.
        val today = Calendar.getInstance()
        val calculated = lastSunrise
        if (calculated != null && calculated.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR)) {
            recalculate()
        }
        val sunrise = lastSunrise ?: return DayTimeZone.DAY
        val sunset = lastSunset ?: return DayTimeZone.DAY
        val now = Calendar.getInstance()

        val dayStart = (sunrise.clone() as Calendar).apply {
            add(Calendar.MINUTE, dawnOffsetMinutes)
        }
        val nightStart = (sunset.clone() as Calendar).apply {
            add(Calendar.MINUTE, duskOffsetMinutes)
        }

        return when {
            now.before(dayStart) -> DayTimeZone.NIGHT
            now.before(nightStart) -> DayTimeZone.DAY
            else -> DayTimeZone.NIGHT
        }
    }

    fun hasSunData(): Boolean = lastSunrise != null && lastSunset != null

    fun getSunriseTime(): Calendar? = lastSunrise
    fun getSunsetTime(): Calendar? = lastSunset
}
