package io.github.opaunke82.lightpilot.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconcilerTest {

    @Test
    fun sendsOnlyOnDifference() {
        var r = Reconciler.step(LinkState(), "SLOW_FLASH", "OFF", connected = true, nowMs = 0)
        assertEquals("SLOW_FLASH", r.send)
        r = Reconciler.step(r.link, "SLOW_FLASH", "SLOW_FLASH", connected = true, nowMs = 300)
        assertNull(r.send)
        assertTrue(r.confirmed)
        r = Reconciler.step(r.link, "SLOW_FLASH", "SLOW_FLASH", connected = true, nowMs = 5_000)
        assertNull(r.send)
    }

    @Test
    fun lostCommandIsRetriedNotTakenAsExternal() {
        var r = Reconciler.step(LinkState(), "FAST_FLASH", "OFF", true, 0)
        assertEquals("FAST_FLASH", r.send)
        // Light still reports OFF: wait within the confirm window ...
        r = Reconciler.step(r.link, "FAST_FLASH", "OFF", true, 1_000)
        assertNull(r.send)
        assertNull(r.externalChange)
        // ... then retry twice.
        r = Reconciler.step(r.link, "FAST_FLASH", "OFF", true, 2_100)
        assertEquals("FAST_FLASH", r.send)
        r = Reconciler.step(r.link, "FAST_FLASH", "OFF", true, 4_200)
        assertEquals("FAST_FLASH", r.send)
        r = Reconciler.step(r.link, "FAST_FLASH", "OFF", true, 6_300)
        assertNull(r.send)
        assertNull(r.externalChange)
    }

    @Test
    fun externalChangeIsReportedAndNotFought() {
        var r = Reconciler.step(LinkState(), "SLOW_FLASH", "OFF", true, 0)
        r = Reconciler.step(r.link, "SLOW_FLASH", "SLOW_FLASH", true, 500)
        // Rider switches the light off in the Control Center.
        r = Reconciler.step(r.link, "SLOW_FLASH", "OFF", true, 10_000)
        assertEquals("OFF", r.externalChange)
        assertNull(r.send)
        // Once the brain has pinned OFF, everything is calm.
        r = Reconciler.step(r.link, "OFF", "OFF", true, 10_500)
        assertNull(r.send)
        assertNull(r.externalChange)
    }

    @Test
    fun ownCommandIsNotExternal() {
        var r = Reconciler.step(LinkState(), "SLOW_FLASH", "SLOW_FLASH", true, 0)
        r = Reconciler.step(r.link, "FAST_FLASH", "SLOW_FLASH", true, 100)
        assertEquals("FAST_FLASH", r.send)
        r = Reconciler.step(r.link, "FAST_FLASH", "FAST_FLASH", true, 400)
        assertNull(r.externalChange)
        assertTrue(r.confirmed)
    }

    @Test
    fun firstReportIsNotExternal() {
        val r = Reconciler.step(LinkState(), null, "STEADY4", true, 0)
        assertNull(r.externalChange)
    }

    @Test
    fun bleWithoutFeedbackSendsOncePerChangeAndAfterReconnect() {
        var r = Reconciler.step(LinkState(), "STEADY_50", null, true, 0)
        assertEquals("STEADY_50", r.send)
        r = Reconciler.step(r.link, "STEADY_50", null, true, 1_000)
        assertNull(r.send)
        r = Reconciler.step(r.link, "STEADY_50", null, false, 2_000)
        assertNull(r.send)
        r = Reconciler.step(r.link, "STEADY_50", null, true, 3_000)
        assertEquals("STEADY_50", r.send)
    }

    @Test
    fun nullDesiredSendsNothing() {
        val r = Reconciler.step(LinkState(), null, "OFF", true, 0)
        assertNull(r.send)
    }
}
