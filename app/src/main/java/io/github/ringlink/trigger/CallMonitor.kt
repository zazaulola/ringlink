package io.github.ringlink.trigger

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.concurrent.Executor

/**
 * Reports when the phone starts ringing.
 *
 * Uses TelephonyCallback on Android 12+ (PhoneStateListener is deprecated there) and falls back on
 * older releases. Both are fed by the same framework dispatch an Xposed hook would intercept, so a
 * plain app with READ_PHONE_STATE sees incoming calls just as reliably — including self-managed
 * VoIP calls, for which only the caller's number is withheld.
 */
@SuppressLint("MissingPermission")
class CallMonitor(private val context: Context, private val onRinging: () -> Unit) {

    private var callback: Any? = null
    private var ringing = false

    fun start() {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handle(state)
            }
            callback = cb
            runCatching { tm.registerTelephonyCallback(Executor { it.run() }, cb) }
        } else {
            @Suppress("DEPRECATION")
            val cb = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = handle(state)
            }
            callback = cb
            @Suppress("DEPRECATION")
            runCatching { tm.listen(cb, PhoneStateListener.LISTEN_CALL_STATE) }
        }
    }

    fun stop() {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        when (val cb = callback) {
            is TelephonyCallback -> if (Build.VERSION.SDK_INT >= 31) {
                runCatching { tm.unregisterTelephonyCallback(cb) }
            }
            is PhoneStateListener -> @Suppress("DEPRECATION") runCatching {
                tm.listen(cb, PhoneStateListener.LISTEN_NONE)
            }
        }
        callback = null
    }

    /** RINGING repeats while the phone rings, so latch it and reset when the call resolves. */
    private fun handle(state: Int) {
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            if (!ringing) {
                ringing = true
                onRinging()
            }
        } else {
            ringing = false
        }
    }
}
