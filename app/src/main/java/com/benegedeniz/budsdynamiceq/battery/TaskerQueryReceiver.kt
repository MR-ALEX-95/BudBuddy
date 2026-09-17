package com.benegedeniz.budsdynamiceq.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.benegedeniz.budsdynamiceq.di.ServiceLocator

/**
 * Answers an on-demand query from Tasker with a BATTERY_UPDATE broadcast.
 * Uses live values when the service is running, otherwise the last stored reading
 * aged by the configured standby drain.
 */
class TaskerQueryReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TaskerQueryReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TaskerBridge.ACTION_REQUEST_BATTERY) return

        val store = CaseBatteryStore(context)
        val deviceState = ServiceLocator.provideDeviceStateRepository(context)
        val modelName = (deviceState.modelOverride.value ?: deviceState.connectedModel.value).name
        val config = store.config(modelName)

        if (!config.taskerEnabled) {
            Log.d(TAG, "Tasker export is disabled in settings; ignoring query.")
            return
        }

        val liveCase = deviceState.batteryCase.value
        val caseCharging = deviceState.chargingCase.value

        val estimate = if (CaseBatteryMath.isValidLevel(liveCase)) {
            CaseBatteryMath.estimate(
                caseNow = liveCase,
                budL = deviceState.batteryL.value,
                budR = deviceState.batteryR.value,
                config = config,
                caseIsCharging = caseCharging
            )
        } else {
            val last = store.lastReading()
            if (last == null) {
                CaseBatteryMath.estimate(-1, -1, -1, config)
            } else {
                val elapsedHours = (System.currentTimeMillis() - last.timestampMs) / 3_600_000.0
                val aged = CaseBatteryMath.levelAfterStandby(
                    last.caseLevel.toDouble(), config.standbyDrainPerDay, elapsedHours
                )
                CaseBatteryMath.estimate(aged.toInt(), last.budL, last.budR, config)
            }
        }

        TaskerBridge.broadcast(context, estimate, config, caseCharging, modelName)
    }
}
