package com.benegedeniz.budsdynamiceq.battery

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for the case battery feature. Uses the same "BudsPrefs" file as the
 * rest of the app so the settings screen can read it with its existing pattern.
 */
class CaseBatteryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "BudsPrefs"

        const val KEY_ENABLED = "case_alerts_enabled"
        const val KEY_THRESHOLD = "case_threshold_percent"
        const val KEY_EFFICIENCY = "case_efficiency_percent"
        const val KEY_FULL_CYCLE_COST = "case_full_cycle_cost"
        const val KEY_STANDBY_DRAIN = "case_standby_drain_per_day"
        const val KEY_HORIZON_HOURS = "case_alert_horizon_hours"
        const val KEY_TASKER = "case_tasker_enabled"

        const val KEY_LEARNED_COST = "case_learned_effective_cost"
        const val KEY_LAST_LEVEL = "case_last_level"
        const val KEY_LAST_TS = "case_last_timestamp"
        const val KEY_LAST_BUD_L = "case_last_bud_l"
        const val KEY_LAST_BUD_R = "case_last_bud_r"

        private const val KEY_EPISODE_CASE = "case_episode_case_level"
        private const val KEY_EPISODE_BUD_AVG = "case_episode_bud_avg"

        private const val KEY_ALERT_REASON = "case_alert_last_reason"
        private const val KEY_ALERT_TS = "case_alert_last_ts"
        private const val KEY_ALERT_LEVEL = "case_alert_last_level"

        /** Floats are stored as strings to dodge Float/Double precision surprises in prefs. */
        private fun SharedPreferences.getDoubleOrNull(key: String): Double? =
            getString(key, null)?.toDoubleOrNull()
    }

    fun config(modelName: String?): CaseBatteryConfig {
        val stored = prefs.getString(KEY_FULL_CYCLE_COST, null)?.toDoubleOrNull()
        return CaseBatteryConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            thresholdPercent = prefs.getInt(KEY_THRESHOLD, CaseBatteryConfig.DEFAULT_THRESHOLD),
            efficiencyPercent = prefs.getInt(KEY_EFFICIENCY, CaseBatteryConfig.DEFAULT_EFFICIENCY),
            fullChargeCaseCostPercent = stored ?: CaseBatteryMath.defaultFullCycleCost(modelName),
            standbyDrainPerDay = prefs.getString(KEY_STANDBY_DRAIN, null)?.toDoubleOrNull()
                ?: CaseBatteryConfig.DEFAULT_STANDBY_DRAIN,
            alertHorizonHours = prefs.getInt(KEY_HORIZON_HOURS, CaseBatteryConfig.DEFAULT_HORIZON_HOURS),
            taskerEnabled = prefs.getBoolean(KEY_TASKER, false)
        )
    }

    fun setEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    fun setThreshold(value: Int) = prefs.edit().putInt(KEY_THRESHOLD, value.coerceIn(1, 99)).apply()
    fun setEfficiency(value: Int) = prefs.edit().putInt(KEY_EFFICIENCY, value.coerceIn(40, 100)).apply()
    fun setStandbyDrain(value: Double) =
        prefs.edit().putString(KEY_STANDBY_DRAIN, value.coerceIn(0.0, 20.0).toString()).apply()
    fun setHorizonHours(value: Int) = prefs.edit().putInt(KEY_HORIZON_HOURS, value.coerceIn(1, 720)).apply()
    fun setTaskerEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_TASKER, value).apply()

    fun setFullCycleCost(value: Double?) {
        if (value == null) prefs.edit().remove(KEY_FULL_CYCLE_COST).apply()
        else prefs.edit().putString(KEY_FULL_CYCLE_COST, value.coerceIn(2.0, 90.0).toString()).apply()
    }

    /** Measured effective cost learned from real charge episodes, or null if never measured. */
    fun learnedCost(): Double? = prefs.getString(KEY_LEARNED_COST, null)?.toDoubleOrNull()

    fun saveLearnedCost(value: Double) =
        prefs.edit().putString(KEY_LEARNED_COST, value.toString()).apply()

    // ---- last known reading, used by the standby alarm and by Tasker queries ----

    data class LastReading(val caseLevel: Int, val budL: Int, val budR: Int, val timestampMs: Long)

    fun lastReading(): LastReading? {
        val level = prefs.getInt(KEY_LAST_LEVEL, -1)
        val ts = prefs.getLong(KEY_LAST_TS, 0L)
        if (!CaseBatteryMath.isValidLevel(level) || ts <= 0L) return null
        return LastReading(level, prefs.getInt(KEY_LAST_BUD_L, -1), prefs.getInt(KEY_LAST_BUD_R, -1), ts)
    }

    fun saveLastReading(caseLevel: Int, budL: Int, budR: Int, now: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putInt(KEY_LAST_LEVEL, caseLevel)
            .putInt(KEY_LAST_BUD_L, budL)
            .putInt(KEY_LAST_BUD_R, budR)
            .putLong(KEY_LAST_TS, now)
            .apply()
    }

    // ---- calibration episode tracking ----

    fun episodeStart(): Pair<Double, Double>? {
        val case = prefs.getDoubleOrNull(KEY_EPISODE_CASE) ?: return null
        val budAvg = prefs.getDoubleOrNull(KEY_EPISODE_BUD_AVG) ?: return null
        return Pair(case, budAvg)
    }

    fun saveEpisodeStart(caseLevel: Double, budAvg: Double) {
        prefs.edit()
            .putString(KEY_EPISODE_CASE, caseLevel.toString())
            .putString(KEY_EPISODE_BUD_AVG, budAvg.toString())
            .apply()
    }

    fun clearEpisode() =
        prefs.edit().remove(KEY_EPISODE_CASE).remove(KEY_EPISODE_BUD_AVG).apply()

    // ---- alert de-duplication ----

    data class LastAlert(val reason: String, val level: Int, val timestampMs: Long)

    fun lastAlert(): LastAlert? {
        val reason = prefs.getString(KEY_ALERT_REASON, null) ?: return null
        return LastAlert(reason, prefs.getInt(KEY_ALERT_LEVEL, -1), prefs.getLong(KEY_ALERT_TS, 0L))
    }

    fun saveLastAlert(reason: String, level: Int, now: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_ALERT_REASON, reason)
            .putInt(KEY_ALERT_LEVEL, level)
            .putLong(KEY_ALERT_TS, now)
            .apply()
    }

    fun clearLastAlert() =
        prefs.edit().remove(KEY_ALERT_REASON).remove(KEY_ALERT_TS).remove(KEY_ALERT_LEVEL).apply()
}
