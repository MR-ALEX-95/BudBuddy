package com.benegedeniz.budsdynamiceq.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.benegedeniz.budsdynamiceq.R
import com.benegedeniz.budsdynamiceq.battery.CaseBatteryMath
import com.benegedeniz.budsdynamiceq.battery.CaseBatteryStore
import com.benegedeniz.budsdynamiceq.di.ServiceLocator
import kotlin.math.roundToInt

/**
 * Settings for the case battery threshold, the charge efficiency used to project
 * how much of the case will be left, and the Tasker export.
 */
@Composable
fun CaseBatterySettingsCard(modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val store = remember(context) { CaseBatteryStore(context) }
    val deviceState = remember(context) { ServiceLocator.provideDeviceStateRepository(context) }

    val connectedModel by deviceState.connectedModel.collectAsState()
    val modelOverride by deviceState.modelOverride.collectAsState()
    val caseLevel by deviceState.batteryCase.collectAsState()
    val budL by deviceState.batteryL.collectAsState()
    val budR by deviceState.batteryR.collectAsState()
    val modelName = (modelOverride ?: connectedModel).name

    var isExpanded by remember { mutableStateOf(false) }
    var config by remember(modelName) { mutableStateOf(store.config(modelName)) }
    var learned by remember { mutableStateOf(store.learnedCost()) }

    val estimate = remember(config, caseLevel, budL, budR) {
        CaseBatteryMath.estimate(caseLevel, budL, budR, config)
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isExpanded = !isExpanded
                    }
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryChargingFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.case_battery_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // Master switch
                    ToggleRow(
                        title = stringResource(R.string.case_battery_enable),
                        subtitle = stringResource(R.string.case_battery_enable_desc),
                        checked = config.enabled
                    ) { value ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        store.setEnabled(value)
                        config = config.copy(enabled = value)
                    }

                    // Live preview
                    Text(
                        text = stringResource(R.string.case_preview_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (estimate.valid) {
                            stringResource(
                                R.string.case_preview_body,
                                estimate.caseNow,
                                estimate.projectedCaseRounded,
                                estimate.chargeCostPercent.roundToInt()
                            )
                        } else {
                            stringResource(R.string.case_preview_unknown)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Threshold
                    SliderRow(
                        title = stringResource(R.string.case_threshold),
                        subtitle = stringResource(R.string.case_threshold_desc, config.thresholdPercent),
                        value = config.thresholdPercent.toFloat(),
                        range = 5f..80f,
                        steps = 14 // 5..80 in steps of 5
                    ) { value ->
                        val v = value.roundToInt()
                        store.setThreshold(v)
                        config = config.copy(thresholdPercent = v)
                    }

                    // Efficiency
                    SliderRow(
                        title = stringResource(R.string.case_efficiency),
                        subtitle = stringResource(R.string.case_efficiency_desc, config.efficiencyPercent),
                        value = config.efficiencyPercent.toFloat(),
                        range = 50f..100f,
                        steps = 9 // 50..100 in steps of 5
                    ) { value ->
                        val v = value.roundToInt()
                        store.setEfficiency(v)
                        config = config.copy(efficiencyPercent = v)
                    }

                    // Cost per full charge, with the measured value when we have one
                    Text(
                        text = stringResource(R.string.case_full_cycle_cost),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.case_full_cycle_cost_desc,
                            String.format("%.1f", config.effectiveFullCycleCost)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    learned?.let { measured ->
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val ideal = CaseBatteryMath.idealCostFromMeasured(measured, config.efficiencyPercent)
                                store.setFullCycleCost(ideal)
                                config = config.copy(fullChargeCaseCostPercent = ideal)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.case_full_cycle_measured,
                                    String.format("%.1f", measured)
                                )
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            store.setFullCycleCost(null)
                            learned = null
                            config = store.config(modelName)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(text = stringResource(R.string.case_full_cycle_reset))
                    }

                    // Standby drain
                    SliderRow(
                        title = stringResource(R.string.case_standby_drain),
                        subtitle = stringResource(
                            R.string.case_standby_drain_desc,
                            String.format("%.1f", config.standbyDrainPerDay)
                        ),
                        value = config.standbyDrainPerDay.toFloat(),
                        range = 0f..5f,
                        steps = 19 // 0..5 in steps of 0.25
                    ) { value ->
                        val v = (value * 4f).roundToInt() / 4.0
                        store.setStandbyDrain(v)
                        config = config.copy(standbyDrainPerDay = v)
                    }

                    // Alert horizon
                    SliderRow(
                        title = stringResource(R.string.case_alert_horizon),
                        subtitle = stringResource(R.string.case_alert_horizon_desc, config.alertHorizonHours),
                        value = config.alertHorizonHours.toFloat(),
                        range = 6f..168f,
                        steps = 26 // 6..168 in steps of 6
                    ) { value ->
                        val v = (value / 6f).roundToInt() * 6
                        store.setHorizonHours(v)
                        config = config.copy(alertHorizonHours = v)
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                    )

                    ToggleRow(
                        title = stringResource(R.string.case_tasker_export),
                        subtitle = stringResource(R.string.case_tasker_export_desc),
                        checked = config.taskerEnabled
                    ) { value ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        store.setTaskerEnabled(value)
                        config = config.copy(taskerEnabled = value)
                    }

                    Text(
                        text = stringResource(R.string.case_tasker_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}
