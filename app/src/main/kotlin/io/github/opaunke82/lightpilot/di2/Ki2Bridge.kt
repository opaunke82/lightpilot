package io.github.opaunke82.lightpilot.di2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Listens to the Shimano Di2 hood buttons through Ki2 (github.com/valterc/ki2, MIT).
 *
 * Ki2 keeps the only connection to the Di2 wireless unit; its exported service
 * com.valterc.ki2.services.Ki2Service lets other apps register for switch events
 * (D-Fly channel 1-4, single click / double click / hold). LightPilot only listens.
 * The interface is called by hand (no AIDL files): transaction codes follow the method
 * order in Ki2's IKi2Service.aidl, the parcels the layout of DeviceId and SwitchEvent
 * (three ints each). Checked against Ki2 as of November 2026.
 */
class Ki2Bridge(
    private val context: Context,
    private val onButton: (channel: Int, press: Di2Press) -> Unit,
) {
    enum class State { OFF, NOT_INSTALLED, CONNECTING, CONNECTED, INCOMPATIBLE }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state

    private val _lastButton = MutableStateFlow<String?>(null)
    /** Last button seen, e.g. "Kanal 2 · Doppelt" (also while no action is assigned). */
    val lastButton: StateFlow<String?> = _lastButton

    private var service: IBinder? = null
    private var bound = false

    private val callback = object : Binder() {
        init {
            attachInterface(null, CALLBACK_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(CALLBACK_DESCRIPTOR)
                return true
            }
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            try {
                data.enforceInterface(CALLBACK_DESCRIPTOR)
            } catch (e: SecurityException) {
                Timber.w("$TAG: unexpected callback, Ki2 interface changed?")
                _state.value = State.INCOMPATIBLE
                return false
            }
            // DeviceId (three ints), then SwitchEvent: channel, command, repeat.
            if (data.readInt() != 0) {
                data.readInt(); data.readInt(); data.readInt()
            }
            if (data.readInt() != 0) {
                val channel = data.readInt()
                val command = data.readInt()
                val repeat = data.readInt()
                handle(channel, command, repeat)
            }
            reply?.writeNoException()
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder
            if (binder == null) return
            if (call(binder, TX_REGISTER_SWITCH_LISTENER)) {
                _state.value = State.CONNECTED
                Timber.d("$TAG: connected to Ki2, listening to Di2 buttons")
            } else {
                _state.value = State.INCOMPATIBLE
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _state.value = State.CONNECTING
            Timber.d("$TAG: Ki2 service lost, Android reconnects when it is back")
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    private fun start() {
        if (bound) return
        if (!isKi2Installed()) {
            _state.value = State.NOT_INSTALLED
            Timber.d("$TAG: Ki2 not installed")
            return
        }
        val intent = Intent().setComponent(ComponentName(KI2_PACKAGE, KI2_SERVICE))
        bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Timber.w(e, "$TAG: bind refused")
            false
        }
        _state.value = if (bound) State.CONNECTING else State.NOT_INSTALLED
    }

    private fun stop() {
        if (bound) {
            service?.let { call(it, TX_UNREGISTER_SWITCH_LISTENER) }
            runCatching { context.unbindService(connection) }
        }
        bound = false
        service = null
        _state.value = State.OFF
    }

    private fun isKi2Installed(): Boolean = try {
        context.packageManager.getPackageInfo(KI2_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun call(binder: IBinder, code: Int): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeStrongBinder(callback)
            binder.transact(code, data, reply, 0)
            reply.readException()
            true
        } catch (e: Exception) {
            Timber.w(e, "$TAG: call $code to Ki2 failed")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun handle(channel: Int, command: Int, repeat: Int) {
        val press = when (command) {
            CMD_SINGLE_CLICK -> Di2Press.SINGLE
            CMD_DOUBLE_CLICK -> Di2Press.DOUBLE
            CMD_LONG_PRESS_DOWN -> Di2Press.HOLD
            else -> return // hold continues / release, no switch
        }
        if (channel !in 1..4) return
        _lastButton.value = "Kanal $channel · ${press.label}"
        Timber.d("$TAG: Di2 button channel $channel ${press.name} (repeat $repeat)")
        onButton(channel, press)
    }

    companion object {
        private const val TAG = "Ki2Bridge"
        const val KI2_PACKAGE = "com.valterc.ki2"
        private const val KI2_SERVICE = "com.valterc.ki2.services.Ki2Service"
        private const val SERVICE_DESCRIPTOR = "com.valterc.ki2.services.IKi2Service"
        private const val CALLBACK_DESCRIPTOR = "com.valterc.ki2.services.callbacks.ISwitchCallback"

        // Position of the methods in IKi2Service.aidl (0-based) + FIRST_CALL_TRANSACTION.
        private const val TX_REGISTER_SWITCH_LISTENER = IBinder.FIRST_CALL_TRANSACTION + 12
        private const val TX_UNREGISTER_SWITCH_LISTENER = IBinder.FIRST_CALL_TRANSACTION + 13

        // Ki2 SwitchCommand numbers.
        private const val CMD_SINGLE_CLICK = 16
        private const val CMD_LONG_PRESS_DOWN = 32
        private const val CMD_DOUBLE_CLICK = 64
    }
}

enum class Di2Press(val label: String) {
    SINGLE("Einfach"),
    DOUBLE("Doppelt"),
    HOLD("Halten"),
}

/** What a Di2 button can do: the same as LightPilot's bonus actions. */
enum class Di2Action(val label: String) {
    NONE("Keine"),
    MANUAL_CYCLE("Licht: Automatik / Peloton / An / Aus / Hart aus"),
    FRONT_CYCLE("Frontlicht: nächste Helligkeit"),
    BEAM_TOGGLE("Frontlicht: Abblend / Fernlicht"),
}

fun di2Key(channel: Int, press: Di2Press) = "$channel:${press.name}"
