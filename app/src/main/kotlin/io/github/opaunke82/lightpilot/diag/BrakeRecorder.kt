package io.github.opaunke82.lightpilot.diag

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Measurement build for a possible brake light: records the Karoo's acceleration sensor,
 * the speed and the light commands with one time base into a CSV file.
 *
 * Lines (time t in ms since boot, SystemClock.elapsedRealtime):
 *   A,t,ax,ay,az        accelerometer (m/s², includes gravity)
 *   L,t,ax,ay,az        linear acceleration (gravity removed by Android), if available
 *   S,t,speed           speed from the Karoo (m/s)
 *   Q,t,device,mode     light command queued by LightPilot
 *   C,t,device,mode     light command handed to the Karoo light service
 *   R,t,device,mode     mode reported back by the light
 *   B,t,text            beep / marker (latency test)
 */
class BrakeRecorder private constructor(context: Context) : SensorEventListener {

    val file: File
    @Volatile var samples = 0L
        private set
    val startedAtMs = SystemClock.elapsedRealtime()

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val writerExecutor = Executors.newSingleThreadScheduledExecutor()
    private val sensorThread = HandlerThread("lightpilot-brake").apply { start() }
    private val writer: BufferedWriter

    init {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val name = "lightpilot-brake-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date()) + ".csv"
        file = File(dir, name)
        writer = file.bufferedWriter()
        val all = sensorManager.getSensorList(Sensor.TYPE_ALL)
        line("# LightPilot brake measurement, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        line("# clocks elapsedRealtimeNanos=${SystemClock.elapsedRealtimeNanos()} nanoTime=${System.nanoTime()}")
        all.forEach { line("# sensor type=${it.type} name=${it.name} vendor=${it.vendor} maxRange=${it.maximumRange} resolution=${it.resolution} minDelayUs=${it.minDelay}") }
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        Timber.d("$TAG: ${all.size} sensors, accelerometer=${accel?.name}, linear=${linear?.name}")
        val handler = Handler(sensorThread.looper)
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        linear?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        if (accel == null) line("# no accelerometer available to apps")
        writerExecutor.scheduleWithFixedDelay({ runCatching { writer.flush() } }, 1, 1, TimeUnit.SECONDS)
    }

    val hasAccelerometer: Boolean =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    override fun onSensorChanged(event: SensorEvent) {
        val kind = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> "A"
            Sensor.TYPE_LINEAR_ACCELERATION -> "L"
            else -> return
        }
        // Sensor time stamps are elapsedRealtimeNanos on current Android; keep both to check.
        val tMs = event.timestamp / 1_000_000.0
        samples++
        val v = event.values
        line("$kind,${"%.1f".format(Locale.US, tMs)},${v[0]},${v[1]},${v[2]}")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun mark(kind: String, vararg fields: Any?) {
        line(kind + "," + SystemClock.elapsedRealtime() + fields.joinToString("") { ",$it" })
    }

    private fun line(text: String) {
        if (writerExecutor.isShutdown) return
        writerExecutor.execute { runCatching { writer.write(text); writer.newLine() } }
    }

    private fun close() {
        sensorManager.unregisterListener(this)
        sensorThread.quitSafely()
        writerExecutor.execute { runCatching { writer.flush(); writer.close() } }
        writerExecutor.shutdown()
        runCatching { writerExecutor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    companion object {
        private const val TAG = "BrakeRecorder"

        /** The running recording, null if none. Light code marks commands here. */
        @Volatile var active: BrakeRecorder? = null
            private set

        @Synchronized
        fun start(context: Context): BrakeRecorder {
            active?.let { return it }
            return BrakeRecorder(context.applicationContext).also {
                active = it
                Timber.d("$TAG: recording to ${it.file.name}")
            }
        }

        @Synchronized
        fun stop(): File? {
            val r = active ?: return null
            active = null
            r.close()
            Timber.d("$TAG: stopped, ${r.samples} sensor samples in ${r.file.name}")
            return r.file
        }
    }
}
