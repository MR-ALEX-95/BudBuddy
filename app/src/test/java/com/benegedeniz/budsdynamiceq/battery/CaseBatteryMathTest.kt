package com.benegedeniz.budsdynamiceq.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseBatteryMathTest {

    private val config = CaseBatteryConfig(
        enabled = true,
        thresholdPercent = 30,
        efficiencyPercent = 85,
        fullChargeCaseCostPercent = 20.6,
        standbyDrainPerDay = 1.0,
        alertHorizonHours = 72
    )

    @Test
    fun `efficiency scales the full cycle cost`() {
        assertEquals(24.235, config.effectiveFullCycleCost, 0.01)
        assertEquals(20.6, config.copy(efficiencyPercent = 100).effectiveFullCycleCost, 0.001)
    }

    @Test
    fun `efficiency of zero does not divide by zero`() {
        assertTrue(config.copy(efficiencyPercent = 0).effectiveFullCycleCost.isFinite())
    }

    @Test
    fun `deficit fraction covers both buds`() {
        assertEquals(1.0, CaseBatteryMath.budDeficitFraction(0, 0), 0.0001)
        assertEquals(0.0, CaseBatteryMath.budDeficitFraction(100, 100), 0.0001)
        assertEquals(0.4, CaseBatteryMath.budDeficitFraction(80, 40), 0.0001)
    }

    @Test
    fun `a single known bud mirrors its twin`() {
        assertEquals(0.6, CaseBatteryMath.budDeficitFraction(-1, 40), 0.0001)
        assertEquals(0.0, CaseBatteryMath.budDeficitFraction(-1, -1), 0.0001)
    }

    @Test
    fun `case that cannot cover the earbuds raises a projected alert`() {
        val estimate = CaseBatteryMath.estimate(45, 30, 35, config)
        assertTrue(estimate.valid)
        assertEquals(16.36, estimate.chargeCostPercent, 0.02)
        assertEquals(28.64, estimate.projectedCasePercent, 0.02)
        assertEquals(CaseAlertReason.PROJECTED_BELOW, estimate.reason)
    }

    @Test
    fun `healthy case stays quiet`() {
        val estimate = CaseBatteryMath.estimate(95, 60, 60, config)
        assertEquals(CaseAlertReason.NONE, estimate.reason)
        assertFalse(estimate.shouldAlert)
    }

    @Test
    fun `case already under the threshold alerts immediately`() {
        assertEquals(CaseAlertReason.ALREADY_BELOW, CaseBatteryMath.estimate(25, 90, 90, config).reason)
    }

    @Test
    fun `standby decay produces an eta alert inside the horizon`() {
        val estimate = CaseBatteryMath.estimate(33, 100, 100, config)
        assertEquals(72.0, estimate.hoursUntilThreshold, 0.01)
        assertEquals(CaseAlertReason.STANDBY_ETA, estimate.reason)
    }

    @Test
    fun `no standby drain means no eta alert`() {
        val estimate = CaseBatteryMath.estimate(50, 100, 100, config.copy(standbyDrainPerDay = 0.0))
        assertTrue(estimate.hoursUntilThreshold.isInfinite())
        assertEquals(CaseAlertReason.NONE, estimate.reason)
    }

    @Test
    fun `unknown case level is invalid and silent`() {
        val estimate = CaseBatteryMath.estimate(-1, 50, 50, config)
        assertFalse(estimate.valid)
        assertFalse(estimate.shouldAlert)
    }

    @Test
    fun `a charging case never alerts`() {
        assertFalse(CaseBatteryMath.estimate(10, 10, 10, config, caseIsCharging = true).shouldAlert)
    }

    @Test
    fun `projection stays within bounds`() {
        assertTrue(CaseBatteryMath.estimate(3, 0, 0, config).projectedCasePercent >= 0.0)
        assertTrue(CaseBatteryMath.estimate(100, 100, 100, config).projectedCasePercent <= 100.0)
    }

    @Test
    fun `standby decay is linear and floors at zero`() {
        assertEquals(48.0, CaseBatteryMath.levelAfterStandby(50.0, 1.0, 48.0), 0.0001)
        assertEquals(0.0, CaseBatteryMath.levelAfterStandby(1.0, 5.0, 480.0), 0.0001)
    }

    @Test
    fun `calibration derives the effective cost from one episode`() {
        assertEquals(23.333, CaseBatteryMath.measuredFullCycleCost(60.0, 14.0)!!, 0.01)
        assertNull(CaseBatteryMath.measuredFullCycleCost(5.0, 1.0))
        assertNull(CaseBatteryMath.measuredFullCycleCost(60.0, 0.0))
        assertNull(CaseBatteryMath.measuredFullCycleCost(20.0, 30.0))
    }

    @Test
    fun `measured cost round trips through the stored ideal cost`() {
        val ideal = CaseBatteryMath.idealCostFromMeasured(23.333, 85)
        val restored = CaseBatteryConfig(fullChargeCaseCostPercent = ideal, efficiencyPercent = 85)
        assertEquals(23.333, restored.effectiveFullCycleCost, 0.01)
    }

    @Test
    fun `calibration blending is an ewma`() {
        assertEquals(24.0, CaseBatteryMath.blendCalibration(null, 24.0), 0.0001)
        assertEquals(24.0, CaseBatteryMath.blendCalibration(20.0, 30.0, 0.4), 0.0001)
    }

    @Test
    fun `notifications are deduplicated`() {
        val now = 1_000_000_000L
        assertTrue(CaseBatteryMath.shouldNotify(CaseAlertReason.PROJECTED_BELOW, 40, null, -1, 0, now))
        assertFalse(CaseBatteryMath.shouldNotify(CaseAlertReason.NONE, 40, null, -1, 0, now))
        assertFalse(
            CaseBatteryMath.shouldNotify(
                CaseAlertReason.PROJECTED_BELOW, 40, "PROJECTED_BELOW", 40, now - 1000, now
            )
        )
        assertTrue(
            CaseBatteryMath.shouldNotify(
                CaseAlertReason.PROJECTED_BELOW, 40, "PROJECTED_BELOW", 40,
                now - CaseBatteryMath.DEFAULT_COOLDOWN_MS, now
            )
        )
        assertTrue(
            CaseBatteryMath.shouldNotify(
                CaseAlertReason.ALREADY_BELOW, 40, "PROJECTED_BELOW", 40, now - 1000, now
            )
        )
        assertTrue(
            CaseBatteryMath.shouldNotify(
                CaseAlertReason.PROJECTED_BELOW, 35, "PROJECTED_BELOW", 40, now - 1000, now
            )
        )
    }

    @Test
    fun `standby wake up is clamped between one and twenty four hours`() {
        val hour = 3_600_000L
        assertEquals(24 * hour, CaseBatteryMath.nextStandbyCheckDelayMs(500.0, 72))
        assertEquals(hour, CaseBatteryMath.nextStandbyCheckDelayMs(73.0, 72))
        assertEquals(8 * hour, CaseBatteryMath.nextStandbyCheckDelayMs(80.0, 72))
        assertEquals(24 * hour, CaseBatteryMath.nextStandbyCheckDelayMs(Double.POSITIVE_INFINITY, 72))
    }

    @Test
    fun `hour formatting splits into days and hours`() {
        assertEquals(Pair(2, 2), CaseBatteryMath.splitHours(50.0))
        assertEquals(Pair(1, 0), CaseBatteryMath.splitHours(23.6))
    }

    @Test
    fun `charge cost never decreases as the earbuds get emptier`() {
        var previous = -1.0
        for (level in 100 downTo 0 step 5) {
            val cost = CaseBatteryMath.chargeCost(level, level, config)
            assertTrue(cost >= previous - 1e-9)
            previous = cost
        }
    }
}
