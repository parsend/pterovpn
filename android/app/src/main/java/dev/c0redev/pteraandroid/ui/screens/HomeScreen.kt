package dev.c0redev.pteraandroid.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.c0redev.pteraandroid.BuildConfig
import dev.c0redev.pteraandroid.R
import dev.c0redev.pteraandroid.theme.PteraSpacing
import dev.c0redev.pteraandroid.ui.ConnectionViewModel
import dev.c0redev.pteraandroid.ui.components.SectionCard

private data class GuideEntry(
    val title: String,
    val hint: String,
    val steps: List<String>,
    val route: String,
    val icon: ImageVector,
)

@Composable
fun HomeScreen(
    vm: ConnectionViewModel,
    padding: PaddingValues,
    onNavigateToTab: (String) -> Unit = {},
) {
    val conn = vm.connection.collectAsState().value
    val settings = vm.clientSettings.collectAsState().value
    val logs = vm.logs.collectAsState().value
    val local = vm.localConfigs.collectAsState().value
    val cloud = vm.cloudConfigs.collectAsState().value
    val haptic = LocalHapticFeedback.current
    var prevReady by remember { mutableStateOf(false) }

    LaunchedEffect(conn.ready) {
        if (conn.ready && !prevReady) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        prevReady = conn.ready
    }

    val pulse by animateFloatAsState(
        targetValue = if (conn.connected) 1.02f else 1f,
        animationSpec = tween(280),
        label = "connPulse",
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = PteraSpacing.screenHorizontal, vertical = PteraSpacing.screenVertical),
        verticalArrangement = Arrangement.spacedBy(PteraSpacing.sectionGap),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.home_version_fmt, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            HomeGuideCard(onNavigateToTab = onNavigateToTab)
        }

        item {
            SectionCard(modifier = Modifier.scale(pulse)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = if (conn.connected) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (conn.connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = stringResource(R.string.home_status_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    StatusRow(label = stringResource(R.string.home_mode), value = conn.mode ?: settings.mode)
                    StatusRow(
                        label = stringResource(R.string.home_connected),
                        value = if (conn.connected) stringResource(R.string.home_yes) else stringResource(R.string.home_no),
                    )
                    StatusRow(
                        label = stringResource(R.string.home_ready),
                        value = if (conn.ready) stringResource(R.string.home_yes) else stringResource(R.string.home_no),
                    )

                    val err = conn.error
                    if (!err.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.home_error_fmt, err),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.refreshLocalConfigs()
                                vm.refreshCloudConfigs(true)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.home_refresh_cd),
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(R.string.home_refresh), modifier = Modifier.padding(start = 8.dp))
                        }
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.disconnect()
                            },
                            enabled = conn.connected,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = stringResource(R.string.home_disconnect_cd),
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(R.string.home_disconnect), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (!conn.connected) {
                        Text(
                            text = stringResource(R.string.disabled_not_connected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.home_summary),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    InfoRow(label = stringResource(R.string.home_local_configs), value = local.size.toString())
                    InfoRow(label = stringResource(R.string.home_cloud_configs), value = cloud.size.toString())
                    InfoRow(label = stringResource(R.string.home_logs_buffer), value = logs.size.toString())
                }
            }
        }

        if (logs.isNotEmpty()) {
            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.home_recent_logs),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        logs.takeLast(5).forEach { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun HomeGuideCard(onNavigateToTab: (String) -> Unit) {
    val entries = listOf(
        GuideEntry(
            title = stringResource(R.string.nav_configs),
            hint = stringResource(R.string.home_guide_configs_hint),
            steps = stringArrayResource(R.array.guide_configs_steps).toList(),
            route = "configs",
            icon = Icons.Outlined.Dns,
        ),
        GuideEntry(
            title = stringResource(R.string.nav_protection),
            hint = stringResource(R.string.home_guide_protection_hint),
            steps = stringArrayResource(R.array.guide_protection_steps).toList(),
            route = "protection",
            icon = Icons.Outlined.Security,
        ),
        GuideEntry(
            title = stringResource(R.string.nav_logs),
            hint = stringResource(R.string.home_guide_logs_hint),
            steps = stringArrayResource(R.array.guide_logs_steps).toList(),
            route = "logs",
            icon = Icons.AutoMirrored.Outlined.ReceiptLong,
        ),
        GuideEntry(
            title = stringResource(R.string.nav_settings),
            hint = stringResource(R.string.home_guide_settings_hint),
            steps = stringArrayResource(R.array.guide_settings_steps).toList(),
            route = "settings",
            icon = Icons.Outlined.Settings,
        ),
    )
    var openRoute by remember { mutableStateOf<String?>(null) }

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.home_guide_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.home_guide_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            entries.forEachIndexed { index, e ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                val expanded = openRoute == e.route
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openRoute = if (expanded) null else e.route
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = e.icon,
                            contentDescription = e.title,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = e.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = e.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column(
                            modifier = Modifier.padding(start = 34.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            e.steps.forEachIndexed { i, step ->
                                Text(
                                    text = "${i + 1}. $step",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    onNavigateToTab(e.route)
                                    openRoute = null
                                },
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(stringResource(R.string.home_guide_open))
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.home_guide_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
