package com.benegedeniz.budsdynamiceq.battery

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.math.roundToInt

/**
 * Exposes battery state to Tasker (or any automation app) as a plain broadcast.
 *
 * Tasker setup:
 *   Profile -> Event -> System -> Intent Received -> Action:
 *     com.benegedeniz.budsdynamiceq.BATTERY_UPDATE
 *   The extras arrive as %case_level, %case_projected, %hours_to_threshold, ...
 *
 * To pull the current values on demand, send:
 *   Tasker -> Action -> System -> Send Intent
 *     Action:  com.benegedeniz.budsdynamiceq.REQUEST_BATTERY
 *     Package: com.benegedeniz.budsdynamiceq
 *     Target:  Broadcast Receiver
 * The app answers with a BATTERY_UPDATE broadcast.
 */
object TaskerBridge {

    private const val TAG = "TaskerBridge"

    const val ACTION_BATTERY_UPDATE = "com.benegedeniz.budsdynamiceq.BATTERY_UPDATE"
    const val ACTION_REQUEST_BATTERY = "com.benegedeniz.budsdynamiceq.REQUEST_BATTERY"

    const val EXTRA_CASE_LEVEL = "case_level"
    const val EXTRA_CASE_PROJECTED = "case_projected"
    const val EXTRA_CASE_COST = "case_charge_cost"
    const val EXTRA_BUD_L = "bud_l"
    const val EXTRA_BUD_R = "bud_r"
    const val EXTRA_CASE_CHARGING = "case_charging"
    const val EXTRA_THRESHOLD = "threshold"
    const val EXTRA_BELOW_THRESHOLD = "below_threshold"
    const val EXTRA_ALERT_REASON = "alert_reason"
    const val EXTRA_HOURS_TO_THRESHOLD = "hours_to_threshold"
    const val EXTRA_DAYS_TO_THRESHOLD = "days_to_threshold"
    const val EXTRA_MODEL = "model"
    const val EXTRA_VALID = "valid"
    const val EXTRA_TIMESTAMP = "timestamp"
    const val EXTRA_JSON = "json"

    fun buildIntent(
        estimate: CaseBatteryEstimate,
        config: CaseBatteryConfig,
        caseIsCharging: Boolean,
        modelName: String
    ): Intent {
        val hours = if (estimate.hoursUntilThreshold.isFinite()) estimate.hoursUntilThreshold else -1.0
        val days = if (hours >= 0) hours / 24.0 else -1.0
        val projected = if (estimate.valid) estimate.projectedCaseRounded else -1

        return Intent(ACTION_BATTERY_UPDATE).apply {
            putExtra(EXTRA_CASE_LEVEL, estimate.caseNow)
            putExtra(EXTRA_CASE_PROJECTED, projected)
            putExtra(EXTRA_CASE_COST, estimate.chargeCostPercent.roundToInt())
            putExtra(EXTRA_BUD_L, estimate.budL)
            putExtra(EXTRA_BUD_R, estimate.budR)
            putExtra(EXTRA_CASE_CHARGING, caseIsCharging)
            putExtra(EXTRA_THRESHOLD, config.thresholdPercent)
            putExtra(EXTRA_BELOW_THRESHOLD, estimate.shouldAlert)
            putExtra(EXTRA_ALERT_REASON, estimate.reason.name)
            putExtra(EXTRA_HOURS_TO_THRESHOLD, (hours * 10).roundToInt() / 10.0)
            putExtra(EXTRA_DAYS_TO_THRESHOLD, (days * 100).roundToInt() / 100.0)
            putExtra(EXTRA_MODEL, modelName)
            putExtra(EXTRA_VALID, estimate.valid)
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
            putExtra(EXTRA_JSON, toJson(estimate, config, caseIsCharging, modelName))
        }
    }

    fun broadcast(
        context: Context,
        estimate: CaseBatteryEstimate,
        config: CaseBatteryConfig,
        caseIsCharging: Boolean,
        modelName: String
    ) {
        try {
            context.sendBroadcast(buildIntent(estimate, config, caseIsCharging, modelName))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast battery state", e)
        }
    }

    /** Small hand-rolled JSON so automation apps can parse one field instead of many extras. */
    fun toJson(
        estimate: CaseBatteryEstimate,
        config: CaseBatteryConfig,
        caseIsCharging: Boolean,
        modelName: String
    ): String {
        val hours = if (estimate.hoursUntilThreshold.isFinite()) estimate.hoursUntilThreshold else -1.0
        return buildString {
            append("{")
            append("\"case_level\":").append(estimate.caseNow).append(",")
            append("\"case_projected\":").append(if (estimate.valid) estimate.projectedCaseRounded else -1).append(",")
            append("\"case_charge_cost\":").append(estimate.chargeCostPercent.roundToInt()).append(",")
            append("\"bud_l\":").append(estimate.budL).append(",")
            append("\"bud_r\":").append(estimate.budR).append(",")
            append("\"case_charging\":").append(caseIsCharging).append(",")
            append("\"threshold\":").append(config.thresholdPercent).append(",")
            append("\"below_threshold\":").append(estimate.shouldAlert).append(",")
            append("\"alert_reason\":\"").append(estimate.reason.name).append("\",")
            append("\"hours_to_threshold\":").append((hours * 10).roundToInt() / 10.0).append(",")
            append("\"model\":\"").append(modelName).append("\",")
            append("\"valid\":").append(estimate.valid)
            append("}")
        }
    }
}
