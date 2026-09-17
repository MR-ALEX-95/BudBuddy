package com.benegedeniz.budsdynamiceq.battery

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure (Android-free) math for case battery projection.
 *
 * Everything here is deterministic and unit-testable on the JVM: no Context,
 * no flows, no SharedPreferences. The Android glue lives in CaseBatteryMonitor.
 */

/** User-tunable configuration, persisted by SettingsRepository. */
data class CaseBatteryConfig(
    val enabled: Boolean = false,
    /** Case level (%) considered "low". Alerts fire at or below this. */
    val thresholdPercent: Int = DEFAULT_THRESHOLD,
    /** Charge transfer efficiency (%). Lower = more case charge burned per bud %. */
    val efficiencyPercent: Int = DEFAULT_EFFICIENCY,
    /**
     * Ideal (lossless) case cost, in case %, of charging BOTH buds from 0% to 100%.
     * Derived from cell capacities: 2 * budMah / caseMah * 100.
     */
    val fullChargeCaseCostPercent: Double = DEFAULT_FULL_CHARGE_COST,
    /** Case self-discharge while idle in a pocket/drawer, in % per day. */
    val standbyDrainPerDay: Double = DEFAULT_STANDBY_DRAIN,
    /** Warn when the case is projected to hit the threshold within this many hours. */
    val alertHorizonHours: Int = DEFAULT_HORIZON_HOURS,
    /** Broadcast state to Tasker / other automation apps. */
    val taskerEnabled: Boolean = false
) {
    /** Effective case cost of a full 0->100 charge of both buds, losses included. */
    val effectiveFullCycleCost: Double
        get() = fullChargeCaseCostPercent * (100.0 / efficiencyPercent.coerceIn(1, 100))

    companion object {
        const val DEFAULT_THRESHOLD = 20
        const val DEFAULT_EFFICIENCY = 85
        const val DEFAULT_FULL_CHARGE_COST = 20.6
        const val DEFAULT_STANDBY_DRAIN = 1.0
        const val DEFAULT_HORIZON_HOURS = 72
    }
}

/** Why an alert should be shown (or not). */
enum class CaseAlertReason { NONE, ALREADY_BELOW, PROJECTED_BELOW, STANDBY_ETA }

data class CaseBatteryEstimate(
    /** Case level reported by the buds, or -1 when unknown. */
    val caseNow: Int,
    val budL: Int,
    val budR: Int,
    /** Case % that topping both buds up to 100% is expected to consume. */
    val chargeCostPercent: Double,
    /** Case % expected to remain once both buds are full. */
    val projectedCasePercent: Double,
    /** Hours from now until the case reaches the threshold, standby drain only. */
    val hoursUntilThreshold: Double,
    val reason: CaseAlertReason,
    val valid: Boolean
) {
    val shouldAlert: Boolean get() = reason != CaseAlertReason.NONE
    val projectedCaseRounded: Int get() = projectedCasePercent.roundToInt()
    val daysUntilThreshold: Double get() = hoursUntilThreshold / 24.0
}

object CaseBatteryMath {

    /**
     * Estimated ideal full-cycle case cost (%) per model, from published cell capacities.
     * Keyed by BudsModel.name so this file stays Android-free.
     * Values for unreleased/unknown models fall back to the 53 mAh bud / 515 mAh case pair.
     */
    private val FULL_CYCLE_COST_BY_MODEL: Map<String, Double> = mapOf(
        "BUDS_4_PRO" to 20.6,   // assumed 53 mAh buds / 515 mAh case - calibrate to be sure
        "BUDS_4" to 20.6,       // assumed
        "BUDS_3_PRO" to 20.6,   // 53 / 515
        "BUDS_3" to 18.6,       // 48 / 515
        "BUDS_3_FE" to 20.6,    // assumed
        "BUDS_2_PRO" to 23.7,   // 61 / 515
        "BUDS_2" to 25.8,       // 61 / 472
        "BUDS_FE" to 25.1       // 60 / 479
    )

    fun defaultFullCycleCost(modelName: String?): Double =
        FULL_CYCLE_COST_BY_MODEL[modelName] ?: CaseBatteryConfig.DEFAULT_FULL_CHARGE_COST

    /** True when a battery reading is a real value rather than the -1 "unknown" sentinel. */
    fun isValidLevel(level: Int): Boolean = level in 0..100

    /**
     * Fraction (0..1) of a full two-bud charge still missing.
     * A single unknown bud is treated as "as empty as its twin" rather than as 0%,
     * so a one-bud-in-case reading does not halve the estimate.
     */
    fun budDeficitFraction(budL: Int, budR: Int): Double {
        val l = if (isValidLevel(budL)) budL else budR
        val r = if (isValidLevel(budR)) budR else budL
        if (!isValidLevel(l) || !isValidLevel(r)) return 0.0
        return (((100 - l) + (100 - r)) / 200.0).coerceIn(0.0, 1.0)
    }

    /** Case % that will be spent bringing both buds up to 100%. */
    fun chargeCost(budL: Int, budR: Int, config: CaseBatteryConfig): Double =
        config.effectiveFullCycleCost * budDeficitFraction(budL, budR)

    /** Case level after standby self-discharge for [elapsedHours]. */
    fun levelAfterStandby(level: Double, drainPerDay: Double, elapsedHours: Double): Double =
        (level - drainPerDay * (elapsedHours / 24.0)).coerceIn(0.0, 100.0)

    /**
     * Hours of standby before [level] decays to [threshold].
     * Returns 0 when already at/below, and [Double.POSITIVE_INFINITY] when drain is zero.
     */
    fun hoursUntilThreshold(level: Double, threshold: Int, drainPerDay: Double): Double {
        if (level <= threshold) return 0.0
        if (drainPerDay <= 0.0) return Double.POSITIVE_INFINITY
        return (level - threshold) / drainPerDay * 24.0
    }

    /**
     * The main entry point.
     *
     * @param caseNow case level reported by the buds (-1 when unknown)
     * @param budL/budR bud levels (-1 when unknown)
     * @param caseIsCharging true when the case itself is plugged in; suppresses alerts
     */
    fun estimate(
        caseNow: Int,
        budL: Int,
        budR: Int,
        config: CaseBatteryConfig,
        caseIsCharging: Boolean = false
    ): CaseBatteryEstimate {
        if (!isValidLevel(caseNow)) {
            return CaseBatteryEstimate(
                caseNow = caseNow, budL = budL, budR = budR,
                chargeCostPercent = 0.0, projectedCasePercent = -1.0,
                hoursUntilThreshold = Double.POSITIVE_INFINITY,
                reason = CaseAlertReason.NONE, valid = false
            )
        }

        val cost = chargeCost(budL, budR, config)
        val projected = (caseNow - cost).coerceIn(0.0, 100.0)
        val eta = hoursUntilThreshold(projected, config.thresholdPercent, config.standbyDrainPerDay)

        val reason = when {
            caseIsCharging -> CaseAlertReason.NONE
            caseNow <= config.thresholdPercent -> CaseAlertReason.ALREADY_BELOW
            projected <= config.thresholdPercent -> CaseAlertReason.PROJECTED_BELOW
            eta <= config.alertHorizonHours -> CaseAlertReason.STANDBY_ETA
            else -> CaseAlertReason.NONE
        }

        return CaseBatteryEstimate(
            caseNow = caseNow, budL = budL, budR = budR,
            chargeCostPercent = cost, projectedCasePercent = projected,
            hoursUntilThreshold = eta, reason = reason, valid = true
        )
    }

    /**
     * Calibration: derive the measured *effective* full-cycle cost from one observed
     * charge episode, e.g. buds went 40% -> 100% (avg gain 60) while the case dropped 13%.
     * Returns null when the episode is too small or physically implausible.
     */
    fun measuredFullCycleCost(avgBudGainPercent: Double, caseDropPercent: Double): Double? {
        if (avgBudGainPercent < 15.0) return null      // too small to be meaningful
        if (caseDropPercent <= 0.0) return null        // case was plugged in, or rounding noise
        val cost = caseDropPercent / (avgBudGainPercent / 100.0)
        if (cost < 2.0 || cost > 90.0) return null     // implausible; discard
        return cost
    }

    /** Convert a measured effective cost back into the stored ideal cost for the given efficiency. */
    fun idealCostFromMeasured(measuredEffectiveCost: Double, efficiencyPercent: Int): Double =
        measuredEffectiveCost * (efficiencyPercent.coerceIn(1, 100) / 100.0)

    /** Exponentially weighted average used to smooth successive calibration samples. */
    fun blendCalibration(previous: Double?, sample: Double, weight: Double = 0.4): Double =
        if (previous == null) sample else previous * (1 - weight) + sample * weight

    /**
     * Decide whether an alert is worth showing again, given the previous one.
     * Re-alerts on a new reason, on a meaningful further drop, or after a cooldown.
     */
    fun shouldNotify(
        reason: CaseAlertReason,
        level: Int,
        lastReason: String?,
        lastLevel: Int,
        lastTimestampMs: Long,
        nowMs: Long,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
        minDropToRepeat: Int = 5
    ): Boolean {
        if (reason == CaseAlertReason.NONE) return false
        if (lastReason == null) return true
        if (lastReason != reason.name) return true
        if (isValidLevel(lastLevel) && isValidLevel(level) && lastLevel - level >= minDropToRepeat) return true
        return nowMs - lastTimestampMs >= cooldownMs
    }

    const val DEFAULT_COOLDOWN_MS: Long = 6 * 60 * 60 * 1000L

    /**
     * Delay until the next standby re-check: wake shortly before the case is expected
     * to cross the threshold, but never sooner than 1 h or later than 24 h.
     */
    fun nextStandbyCheckDelayMs(
        hoursUntilThreshold: Double,
        horizonHours: Int,
        minHours: Double = 1.0,
        maxHours: Double = 24.0
    ): Long {
        val target = if (!hoursUntilThreshold.isFinite()) maxHours
        else (hoursUntilThreshold - horizonHours).coerceIn(minHours, maxHours)
        return (target * 60.0 * 60.0 * 1000.0).toLong()
    }

    /** "2 d 4 h" style formatting, locale-free; the UI layer adds translated units. */
    fun splitHours(hours: Double): Pair<Int, Int> {
        val clamped = max(0.0, min(hours, 9999.0))
        val days = (clamped / 24.0).toInt()
        val rem = (clamped - days * 24).roundToInt()
        return if (rem == 24) Pair(days + 1, 0) else Pair(days, rem)
    }
}
