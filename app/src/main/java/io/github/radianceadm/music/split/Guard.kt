package io.github.radianceadm.music.split

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log

/**
 * Splitting works the phone hard: it pauses when the battery is low and the
 * phone isn't charging, or when the phone is hot, and goes on by itself once
 * it's charging or has cooled down.
 */
object Guard {
    /** Pauses below this (not charging); goes on again from [BATTERY_ON] or when charging. */
    const val BATTERY_LOW = 20
    const val BATTERY_ON = 25

    /** Why the phone needs a rest now, or null. [resting]: already paused (it waits for a little more). */
    fun reason(context: Context, resting: Boolean): String? {
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.let { 100 * it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (!charging && level < (if (resting) BATTERY_ON else BATTERY_LOW)) return "Paused: the battery is at $level %. It goes on when the phone charges"
        // Android's own heat levels: it pauses at "severe", goes on at "moderate" or cooler
        val heat = context.getSystemService(PowerManager::class.java).currentThermalStatus
        if (heat >= (if (resting) PowerManager.THERMAL_STATUS_MODERATE + 1 else PowerManager.THERMAL_STATUS_SEVERE)) return "Paused: the phone is hot. It goes on once it cools down"
        return null
    }

    /** Waits (telling [say] why) while the phone needs a rest; back as soon as it doesn't, or the split is stopped. */
    fun rest(context: Context, say: (String) -> Unit, cancelled: () -> Boolean) {
        var why = reason(context, false) ?: return
        Log.i("radiance-split", why)
        while (!cancelled()) {
            say(why)
            Thread.sleep(5_000)
            why = reason(context, true) ?: break
        }
        Log.i("radiance-split", "going on")
    }
}
