package io.github.ringlink

import android.util.Log

/** Single logging tag so the whole app can be followed with `adb logcat -s RingLink`. */
object L {
    private const val TAG = "RingLink"

    fun i(msg: String) = Log.i(TAG, msg)
    fun d(msg: String) = Log.d(TAG, msg)
    fun w(msg: String) = Log.w(TAG, msg)
    fun e(msg: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, msg) else Log.e(TAG, msg, t)
    }
}
