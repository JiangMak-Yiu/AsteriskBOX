// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0
//
// Speed test screen ported from Bettbox (GPL-3.0)
// https://github.com/appshubcc/Bettbox lib/pages/speed_test.dart

package features.toolbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.LocalIsWideScreen
import app.LocalNavigator
import app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ui.components.AsteriskScaffold
import ui.components.AsteriskTopAppBar
import ui.icons.AsteriskIcons as Icons
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.layout.singleColumnPageWidth

/** 工具箱 → 网络测速。走当前代理链路，指标口径与 Bettbox 的 Cloudflare 测速一致。 */
@Composable
fun SpeedTestPage(padding: PaddingValues) {
    val navigator = LocalNavigator.current
    val isWideScreen = LocalIsWideScreen.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(SpeedTestState()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val running = job?.isActive == true

    fun start() {
        if (running) return
        job = scope.launch {
            state = SpeedTestState(phase = SpeedTestPhase.Latency)
            runCatching { runSpeedTest { latest -> state = latest } }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    state = state.copy(
                        phase = SpeedTestPhase.Failed,
                        failedReason = error.message.orEmpty(),
                        instantMbps = 0.0,
                    )
                }
        }
    }

    AsteriskScaffold(
        topBar = {
            AsteriskTopAppBar(
                title = { Text(stringResource(R.string.speed_test_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val contentPadding = pageListPadding(
            pageContentPaddingWithCutout(
                innerPadding = innerPadding,
                outerPadding = padding,
                isWideScreen = isWideScreen,
            ),
        )
        LazyColumn(
            modifier = Modifier.singleColumnPageWidth(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "speed_test_primary") {
                SpeedTestPrimaryCard(
                    state = state,
                    running = running,
                    onStart = ::start,
                    onStop = { job?.cancel() },
                )
            }
            item(key = "speed_test_metrics") {
                SpeedTestMetricGrid(state)
            }
            if (state.exitIp.isNotBlank()) {
                item(key = "speed_test_exit") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.speed_test_exit),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = listOf(state.exitIp, state.exitLocation, state.colo)
                                    .filter(String::isNotBlank)
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            item(key = "speed_test_hint") {
                Text(
                    text = stringResource(R.string.speed_test_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SpeedTestPrimaryCard(
    state: SpeedTestState,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val headline = when (state.phase) {
        SpeedTestPhase.Done -> state.downloadMbps
        SpeedTestPhase.Download, SpeedTestPhase.Upload -> state.instantMbps
        else -> 0.0
    }
    val phaseLabel = stringResource(
        when (state.phase) {
            SpeedTestPhase.Latency -> R.string.speed_test_phase_latency
            SpeedTestPhase.Download -> R.string.speed_test_phase_download
            SpeedTestPhase.Upload -> R.string.speed_test_phase_upload
            SpeedTestPhase.Done -> R.string.speed_test_phase_done
            SpeedTestPhase.Failed -> R.string.speed_test_phase_failed
            SpeedTestPhase.Idle -> R.string.speed_test_idle
        }
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatMbps(headline),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = " Mbps",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (state.phase == SpeedTestPhase.Failed) {
                Text(
                    text = state.failedReason.ifBlank { stringResource(R.string.speed_test_phase_failed) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (running) {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (running) {
                    TextButton(onClick = onStop) {
                        Text(stringResource(R.string.speed_test_stop))
                    }
                } else {
                    TextButton(onClick = onStart) {
                        Icon(Icons.Rounded.Speed, contentDescription = null)
                        Text(
                            text = stringResource(R.string.speed_test_start),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedTestMetricGrid(state: SpeedTestState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeedTestMetric(
                title = stringResource(R.string.speed_test_latency),
                value = if (state.latencyMs > 0) "${formatMs(state.latencyMs)} ms" else "--",
                modifier = Modifier.weight(1f),
            )
            SpeedTestMetric(
                title = stringResource(R.string.speed_test_jitter),
                value = if (state.jitterMs > 0) "${formatMs(state.jitterMs)} ms" else "--",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeedTestMetric(
                title = stringResource(R.string.speed_test_download),
                value = if (state.downloadMbps > 0) "${formatMbps(state.downloadMbps)} Mbps" else "--",
                modifier = Modifier.weight(1f),
            )
            SpeedTestMetric(
                title = stringResource(R.string.speed_test_upload),
                value = if (state.uploadMbps > 0) "${formatMbps(state.uploadMbps)} Mbps" else "--",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SpeedTestMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun formatMbps(value: Double): String =
    if (value >= 100) "%.0f".format(value) else if (value >= 10) "%.1f".format(value) else "%.2f".format(value)

private fun formatMs(value: Double): String =
    if (value >= 100) "%.0f".format(value) else "%.1f".format(value)
