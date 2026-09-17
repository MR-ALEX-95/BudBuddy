package com.benegedeniz.budsdynamiceq.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Wakes up periodically while the buds are away to project the case's standby
 * self-discharge, using the last level the earbuds reported.
 */
class CaseBatteryAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CaseBatteryAlarm"
        const val ACTION_STANDBY_CHECK = "com.benegedeniz.budsdynamiceq.CASE_STANDBY_CHECK"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STANDBY_CHECK) return

        val store = CaseBatteryStore(context)
        val config = store.config(null)
        if (!config.enabled) return

        val last = store.lastReading() ?: return
        val elapsedHours = (System.currentTimeMillis() - last.timestampMs) / 3_600_000.0
        if (elapsedHours < 0) return

        // Age the last known case level by the configured standby drain.
        val agedLevel = CaseBatteryMath.levelAfterStandby(
            last.caseLevel.toDouble(), config.standbyDrainPerDay, elapsedHours
        )

        val estimate = CaseBatteryMonitor.evaluateAndNotify(
            context = context,
            store = store,
            config = config,
            caseLevel = agedLevel.toInt(),
            budL = last.budL,
            budR = last.budR,
            caseIsCharging = false
        )

        if (config.taskerEnabled) {
            TaskerBridge.broadcast(context, estimate, config, false, "STANDBY")
        }

        Log.d(TAG, "Standby check: aged=$agedLevel reason=${estimate.reason}")

        CaseBatteryMonitor.scheduleStandbyCheck(
            context,
            CaseBatteryMath.nextStandbyCheckDelayMs(estimate.hoursUntilThreshold, config.alertHorizonHours)
        )
    }
}
