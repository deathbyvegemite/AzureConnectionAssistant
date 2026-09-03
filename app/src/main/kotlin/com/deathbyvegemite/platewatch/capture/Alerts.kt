package com.deathbyvegemite.platewatch.capture

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Non-visual feedback, because the person holding this phone is driving and must not
 * be looking at it.
 */
class Alerts(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** A quiet tick to confirm a plate went into the log. */
    fun logged() {
        vibrate(30)
        tone(ToneGenerator.TONE_PROP_BEEP, 90)
    }

    /** Unmistakably different: this plate is on the watchlist. */
    fun watchlistHit() {
        vibrate(140, repeats = 2)
        tone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
    }

    private fun vibrate(millis: Long, repeats: Int = 1) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = LongArray(repeats * 2) { if (it % 2 == 0) 60L else millis }
                v.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(millis)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Vibration unavailable", e)
        }
    }

    private fun tone(type: Int, durationMs: Int) {
        try {
            // Created per use and released immediately: a long-lived ToneGenerator
            // holds an AudioTrack open for the whole drive.
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME).apply {
                startTone(type, durationMs)
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ runCatching { release() } }, (durationMs + 150).toLong())
            }
        } catch (e: Exception) {
            Log.d(TAG, "Tone unavailable", e)
        }
    }

    private companion object {
        const val TAG = "Alerts"
        const val VOLUME = 70
    }
}
