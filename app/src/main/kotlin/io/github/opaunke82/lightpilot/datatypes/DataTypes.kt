// Tap handling via PendingIntent follows LightONKaroo
// (https://github.com/JaJaJim/LightONKaroo), MIT License.

package io.github.opaunke82.lightpilot.datatypes

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import io.github.opaunke82.lightpilot.LightPilotExtension
import io.github.opaunke82.lightpilot.R
import io.github.opaunke82.lightpilot.core.ManualMode
import io.github.opaunke82.lightpilot.core.RidePhase
import io.github.opaunke82.lightpilot.core.Zone
import io.github.opaunke82.lightpilot.data.LightRole
import io.github.opaunke82.lightpilot.batteryText
import io.github.opaunke82.lightpilot.engine.EngineStatus
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

private const val ACTION_MANUAL_CYCLE = "io.github.opaunke82.lightpilot.MANUAL_CYCLE"
private const val ACTION_FRONT_CYCLE = "io.github.opaunke82.lightpilot.FRONT_CYCLE"

private val RED = Color.parseColor("#B71C1C")
private val GREY = Color.parseColor("#9E9E9E")

fun manualLabel(mode: ManualMode): String = when (mode) {
    ManualMode.AUTO -> "Automatik"
    ManualMode.ON -> "An"
    ManualMode.OFF -> "Aus"
    ManualMode.HARD_OFF -> "Hart aus"
}

fun zoneLabel(zone: Zone): String = if (zone == Zone.NIGHT) "Nacht" else "Tag"

private fun tapIntent(context: Context, action: String, requestCode: Int): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        requestCode,
        // Explicit intent: works with the non-exported receiver on every Android version.
        Intent(context, TapReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** Main field (M1, K1): manual setting, day/night, radar, rear light. Tap cycles the manual setting. */
class StatusDataType(private val ext: LightPilotExtension) :
    DataTypeImpl(LightPilotExtension.EXTENSION_ID, "status") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            ext.engine.status.map { it.manual.ordinal.toDouble() }.distinctUntilChanged().collect { value ->
                emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = mapOf("manual" to value))))
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            ext.engine.status.collect { status -> emitter.updateView(render(context, status)) }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun render(context: Context, s: EngineStatus): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.field_view)
        val rear = s.lights.firstOrNull { it.setup.role == LightRole.REAR }
        val idle = s.phase == RidePhase.IDLE && !s.simulation
        val radar = when {
            s.radarWarning -> "FAHRZEUG"
            !s.radarSeen -> "kein Radar"
            s.radarStale -> "Radar still"
            else -> "Radar frei"
        }
        val rearText = when {
            rear == null -> "kein Rücklicht"
            !rear.connected -> "Rück getrennt"
            else -> "Rück: ${shortModeName(rear.desired)}"
        }
        val battery = rear?.let { batteryText(it.setup.protocol, it.batteryPct) }
        // Title: day/night and radar. Main line: the manual setting, i.e. what a tap changes,
        // so every tap is visible even if the light itself stays the same (e.g. by day).
        views.setTextViewText(R.id.field_title, if (idle) "LightPilot" else "${zoneLabel(s.zone)} · $radar")
        views.setTextViewText(R.id.field_main, if (idle) "keine Fahrt" else manualLabel(s.manual))
        views.setTextViewText(R.id.field_sub, listOfNotNull(rearText, battery).joinToString(" · "))
        views.setInt(R.id.field_root, "setBackgroundColor", if (s.radarWarning) RED else Color.TRANSPARENT)
        views.setTextColor(R.id.field_sub, if (s.radarWarning) Color.WHITE else GREY)
        views.setTextColor(R.id.field_title, if (s.radarWarning) Color.WHITE else GREY)
        // Taps only during a ride, so the Karoo page editor keeps all gestures otherwise.
        if (ext.isRideActive || s.simulation) {
            views.setOnClickPendingIntent(R.id.field_root, tapIntent(context, ACTION_MANUAL_CYCLE, 0))
        }
        return views
    }
}

/** Short mode names for the small data fields. */
fun shortModeName(mode: String?): String = when {
    mode == null -> "–"
    mode == "OFF" -> "aus"
    mode == "STEADY4" -> "hell"
    mode == "STEADY5" -> "gedimmt"
    mode == "SLOW_FLASH" -> "Night Flash"
    mode == "FAST_FLASH" -> "Day Flash"
    mode.startsWith("STEADY_") -> mode.removePrefix("STEADY_") + " %"
    mode.startsWith("FLASH_") -> "Blinken " + mode.removePrefix("FLASH_") + " %"
    else -> mode
}

/** Front field (N5): mode of the front light. Tap cycles its brightness. */
class FrontLightDataType(private val ext: LightPilotExtension) :
    DataTypeImpl(LightPilotExtension.EXTENSION_ID, "front") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            ext.engine.status.map { s -> s.lights.count { it.setup.role == LightRole.FRONT }.toDouble() }
                .distinctUntilChanged()
                .collect { value ->
                    emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId = dataTypeId, values = mapOf("front" to value))))
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            ext.engine.status.collect { s ->
                val views = RemoteViews(context.packageName, R.layout.field_view)
                val front = s.lights.firstOrNull { it.setup.role == LightRole.FRONT }
                views.setTextViewText(R.id.field_title, "Frontlicht")
                if (front == null) {
                    views.setTextViewText(R.id.field_main, "nicht eingerichtet")
                    views.setTextViewText(R.id.field_sub, "")
                } else {
                    views.setTextViewText(R.id.field_main, shortModeName(front.desired))
                    val state = if (front.connected) "Tippen: nächste Stufe" else "getrennt"
                    val battery = batteryText(front.setup.protocol, front.batteryPct)
                    views.setTextViewText(R.id.field_sub, listOfNotNull(state, battery).joinToString(" · "))
                }
                views.setTextColor(R.id.field_sub, GREY)
                if (ext.isRideActive || s.simulation) {
                    views.setOnClickPendingIntent(R.id.field_root, tapIntent(context, ACTION_FRONT_CYCLE, 1))
                }
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }
}

class TapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("TapReceiver: ${intent.action}")
        val engine = LightPilotExtension.getInstance()?.engine ?: return
        when (intent.action) {
            ACTION_MANUAL_CYCLE -> engine.manualCycle()
            ACTION_FRONT_CYCLE -> engine.frontCycle()
        }
        LightPilotExtension.getInstance()?.tapFeedback()
    }
}
