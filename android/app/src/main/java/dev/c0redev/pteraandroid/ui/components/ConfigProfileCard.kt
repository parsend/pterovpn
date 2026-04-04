package dev.c0redev.pteraandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.c0redev.pteraandroid.R
import dev.c0redev.pteraandroid.data.servergeo.serverHostFromField
import dev.c0redev.pteraandroid.ui.ConfigItemState

private fun pingTint(ms: Long?, failed: Boolean, scheme: androidx.compose.material3.ColorScheme): Color {
    if (failed || ms == null) return scheme.errorContainer.copy(alpha = 0.55f)
    return when {
        ms < 85L -> Color(0xFF1B5E20).copy(alpha = 0.35f)
        ms < 200L -> Color(0xFFE65100).copy(alpha = 0.35f)
        else -> Color(0xFFB71C1C).copy(alpha = 0.35f)
    }
}

private fun pingLabelColor(ms: Long?, failed: Boolean, scheme: androidx.compose.material3.ColorScheme): Color {
    if (failed || ms == null) return scheme.onErrorContainer
    return when {
        ms < 85L -> Color(0xFFC8E6C9)
        ms < 200L -> Color(0xFFFFE0B2)
        else -> Color(0xFFFFCDD2)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfigProfileCard(
    item: ConfigItemState,
    modifier: Modifier = Modifier,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val hostLine = serverHostFromField(item.config.server)
    val geo = item.geo

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = scheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.primaryContainer.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = geo?.flagEmoji ?: "🌐",
                        fontSize = 28.sp,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Lan,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                        Text(
                            text = hostLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (geo != null) {
                        Text(
                            text = "${geo.countryName} · ${geo.asnLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.secondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = pingTint(item.pingMs, item.pingFailed, scheme),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = pingLabelColor(item.pingMs, item.pingFailed, scheme),
                        )
                        Text(
                            text = if (item.pingFailed || item.pingMs == null) {
                                stringResource(R.string.config_card_ping_na)
                            } else {
                                stringResource(R.string.config_card_ping_ms, item.pingMs)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = pingLabelColor(item.pingMs, item.pingFailed, scheme),
                        )
                    }
                }
                AssistChipCompat(
                    text = stringResource(
                        if (item.probeOk) R.string.config_chip_probe_ok else R.string.config_chip_probe_fail,
                    ),
                    ok = item.probeOk,
                )
                AssistChipCompat(
                    text = stringResource(
                        if (item.ipv6Support) R.string.config_chip_ipv6_y else R.string.config_chip_ipv6_n,
                    ),
                    ok = item.ipv6Support,
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = scheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = item.config.transportSummary(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (item.serverMode.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = scheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = item.serverMode,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (onEdit == null && onDelete == null) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(primaryLabel)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onPrimary,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(primaryLabel)
                    }
                    if (onEdit != null) {
                        FilledTonalButton(
                            onClick = onEdit,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.config_cd_edit))
                        }
                    }
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text(
                                stringResource(R.string.config_delete),
                                color = scheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistChipCompat(text: String, ok: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (ok) scheme.tertiaryContainer.copy(alpha = 0.55f) else scheme.errorContainer.copy(alpha = 0.35f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (ok) scheme.onTertiaryContainer else scheme.onErrorContainer,
        )
    }
}
