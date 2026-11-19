package io.github.opaunke82.lightpilot.log

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last log lines in memory (D2) and writes them to a file on request.
 * Everything logged through Timber ends up here, so the radar levels, the sent
 * commands and the reported modes can be read after a test ride.
 */
object EventLog {
    private const val MAX_LINES = 5_000
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY)

    @Synchronized
    fun add(tag: String?, message: String) {
        val line = "${format.format(Date())} ${tag ?: "-"}: $message"
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(line)
        version++
    }

    /** Counts added lines, so the log window copies only when something changed. */
    @Volatile var version = 0L
        private set

    /** Newest [max] lines, newest first (log window). */
    @Synchronized
    fun snapshot(max: Int): List<String> = lines.takeLast(max).reversed()

    @Synchronized
    fun clear() {
        version++
        lines.clear()
    }

    /**
     * Writes the log to the app's external files folder, readable with
     * `adb pull /sdcard/Android/data/<package>/files/` on the computer.
     */
    @Synchronized
    fun export(context: Context): File? = try {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val name = "lightpilot-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date()) + ".txt"
        File(dir, name).apply { writeText(lines.joinToString("\n")) }
    } catch (e: Exception) {
        Log.e("LightPilot", "Log export failed", e)
        null
    }

    /** Timber tree that feeds this log. */
    class Tree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < Log.DEBUG) return
            // The SDK logs every consumer registration; the device list is polled every
            // 30 s, which pushed the useful lines off the status page.
            if (message.startsWith("KarooSystem: addConsumer") || message.startsWith("KarooSystem: removeConsumer")) return
            add(tag, if (t == null) message else "$message (${t.javaClass.simpleName}: ${t.message})")
        }
    }
}
