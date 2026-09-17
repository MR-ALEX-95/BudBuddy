package com.benegedeniz.budsdynamiceq.battery

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.benegedeniz.budsdynamiceq.MainActivity
import com.benegedeniz.budsdynamiceq.R
import com.benegedeniz.budsdynamiceq.bluetooth.BudsController
import com.benegedeniz.budsdynamiceq.data.model.PlacementState
import com.benegedeniz.budsdynamiceq.util.LanguageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Watches case/bud battery levels and warns when the case will not have enough
 * charge left after topping the earbuds up, or when it will decay to the
 * threshold on its own while sitting in standby.
 *
 * The case level is only reported by the earbuds while at least one bud is in the
 * case (see BudsPacketParser), so a fresh evaluation naturally happens when you
 * put a bud back or open the case.
 */
class CaseBatteryMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val budsController: BudsController
) {

    companion object {
        private const val TAG = "CaseBatteryMonitor"
        const val CHANNEL_ID = "case_battery_channel"
        const val NOTIFICATION_ID = 42

        /** Re-evaluate standby decay even if the alarm is missed, on next service start. */
        fun scheduleStandbyCheck(context: Context, delayMs: Long) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, CaseBatteryAlarmReceiver::class.java).apply {
                action = CaseBatteryAlarmReceiver.ACTION_STANDBY_CHECK
            }
            val pending = PendingIntent.getBroadcast(
                context, 77, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val triggerAt = System.currentTimeMillis() + delayMs
            try {
                // Inexact + allow-while-idle: no SCHEDULE_EXACT_ALARM permission needed.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not schedule standby check", e)
            }
        }

        fun cancelStandbyCheck(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, CaseBatteryAlarmReceiver::class.java).apply {
                action = CaseBatteryAlarmReceiver.ACTION_STANDBY_CHECK
            }
            val pending = PendingIntent.getBroadcast(
                context, 77, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            alarmManager.cancel(pending)
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val localized = LanguageUtils.setLocale(context)
            val channel = NotificationChannel(
                CHANNEL_ID,
                localized.getString(R.string.case_battery_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = localized.getString(R.string.case_battery_channel_desc)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        /**
         * Shared evaluation path used both by the live monitor and by the standby alarm.
         * Returns the estimate so callers can reschedule.
         */
        fun evaluateAndNotify(
            context: Context,
            store: CaseBatteryStore,
            config: CaseBatteryConfig,
            caseLevel: Int,
            budL: Int,
            budR: Int,
            caseIsCharging: Boolean
        ): CaseBatteryEstimate {
            val estimate = CaseBatteryMath.estimate(caseLevel, budL, budR, config, caseIsCharging)
            if (!estimate.valid || !config.enabled) return estimate

            val last = store.lastAlert()
            val now = System.currentTimeMillis()

            if (!estimate.shouldAlert) {
                store.clearLastAlert()
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                return estimate
            }

            val notify = CaseBatteryMath.shouldNotify(
                reason = estimate.reason,
                level = estimate.projectedCaseRounded,
                lastReason = last?.reason,
                lastLevel = last?.level ?: -1,
                lastTimestampMs = last?.timestampMs ?: 0L,
                nowMs = now
            )
            if (!notify) return estimate

            showNotification(context, config, estimate)
            store.saveLastAlert(estimate.reason.name, estimate.projectedCaseRounded, now)
            return estimate
        }

        private fun showNotification(
            context: Context,
            config: CaseBatteryConfig,
            estimate: CaseBatteryEstimate
        ) {
            createChannel(context)
            val localized = LanguageUtils.setLocale(context)

            val (days, hours) = CaseBatteryMath.splitHours(estimate.hoursUntilThreshold)
            val etaText = if (days > 0) {
                localized.getString(R.string.case_eta_days_hours, days, hours)
            } else {
                localized.getString(R.string.case_eta_hours, hours)
            }

            val title: String
            val body: String
            when (estimate.reason) {
                CaseAlertReason.ALREADY_BELOW -> {
                    title = localized.getString(R.string.case_alert_low_title, estimate.caseNow)
                    body = localized.getString(R.string.case_alert_low_body, config.thresholdPercent)
                }
                CaseAlertReason.PROJECTED_BELOW -> {
                    title = localized.getString(
                        R.string.case_alert_projected_title, estimate.projectedCaseRounded
                    )
                    body = localized.getString(
                        R.string.case_alert_projected_body,
                        estimate.caseNow,
                        estimate.chargeCostPercent.roundToInt(),
                        config.thresholdPercent
                    )
                }
                CaseAlertReason.STANDBY_ETA -> {
                    title = localized.getString(R.string.case_alert_standby_title, config.thresholdPercent)
                    body = localized.getString(
                        R.string.case_alert_standby_body, etaText, estimate.projectedCaseRounded
                    )
                }
                CaseAlertReason.NONE -> return
            }

            val contentIntent = PendingIntent.getActivity(
                context, 5, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()

            try {
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission missing", e)
            }
        }
    }

    private val store = CaseBatteryStore(context)

    fun start() {
        createChannel(context)

        scope.launch {
            val placementFlow = combine(
                budsController.placementL,
                budsController.placementR,
                budsController.chargingCase
            ) { pL, pR, caseCharging -> Triple(pL, pR, caseCharging) }

            combine(
                budsController.batteryCase,
                budsController.batteryL,
                budsController.batteryR,
                placementFlow,
                budsController.effectiveModel
            ) { caseLevel, bL, bR, placement, model ->
                Reading(caseLevel, bL, bR, placement.first, placement.second, placement.third, model.name)
            }.collect { reading -> onReading(reading) }
        }
    }

    private data class Reading(
        val caseLevel: Int,
        val budL: Int,
        val budR: Int,
        val placementL: PlacementState,
        val placementR: PlacementState,
        val caseCharging: Boolean,
        val modelName: String
    )

    private fun onReading(reading: Reading) {
        val storedConfig = store.config(reading.modelName)
        // Prefer a measured cost over the capacity-table guess once we have one.
        val learned = store.learnedCost()
        val config = if (learned != null) {
            storedConfig.copy(
                fullChargeCaseCostPercent =
                    CaseBatteryMath.idealCostFromMeasured(learned, storedConfig.efficiencyPercent)
            )
        } else storedConfig

        if (!CaseBatteryMath.isValidLevel(reading.caseLevel)) {
            // Buds are out of the case: the case level is unknown, so close any episode.
            store.clearEpisode()
            return
        }

        updateCalibration(reading, config)
        store.saveLastReading(reading.caseLevel, reading.budL, reading.budR)

        val estimate = evaluateAndNotify(
            context = context,
            store = store,
            config = config,
            caseLevel = reading.caseLevel,
            budL = reading.budL,
            budR = reading.budR,
            caseIsCharging = reading.caseCharging
        )

        if (config.taskerEnabled) {
            TaskerBridge.broadcast(context, estimate, config, reading.caseCharging, reading.modelName)
        }

        if (config.enabled && !reading.caseCharging) {
            scheduleStandbyCheck(
                context,
                CaseBatteryMath.nextStandbyCheckDelayMs(estimate.hoursUntilThreshold, config.alertHorizonHours)
            )
        } else {
            cancelStandbyCheck(context)
        }
    }

    /**
     * Learn the real case cost by watching a charge episode: buds gain %, case loses %.
     * Only counts while the case is not plugged in, otherwise the case tops itself up.
     */
    private fun updateCalibration(reading: Reading, config: CaseBatteryConfig) {
        if (reading.caseCharging) {
            store.clearEpisode()
            return
        }
        if (!CaseBatteryMath.isValidLevel(reading.budL) || !CaseBatteryMath.isValidLevel(reading.budR)) return

        val inCase = { p: PlacementState -> p == PlacementState.CASE || p == PlacementState.CLOSED_CASE }
        if (!inCase(reading.placementL) || !inCase(reading.placementR)) {
            store.clearEpisode()
            return
        }

        val budAvg = (reading.budL + reading.budR) / 2.0
        val start = store.episodeStart()
        if (start == null) {
            store.saveEpisodeStart(reading.caseLevel.toDouble(), budAvg)
            return
        }

        val (startCase, startBudAvg) = start
        val budGain = budAvg - startBudAvg
        val caseDrop = startCase - reading.caseLevel

        if (budGain < 0) {
            // Buds were swapped or discharged: restart the episode.
            store.saveEpisodeStart(reading.caseLevel.toDouble(), budAvg)
            return
        }

        val measured = CaseBatteryMath.measuredFullCycleCost(budGain, caseDrop) ?: return
        val blended = CaseBatteryMath.blendCalibration(store.learnedCost(), measured)
        store.saveLearnedCost(blended)
        store.saveEpisodeStart(reading.caseLevel.toDouble(), budAvg)
        Log.d(TAG, "Calibrated case cost: measured=$measured blended=$blended (efficiency=${config.efficiencyPercent})")
    }
}
