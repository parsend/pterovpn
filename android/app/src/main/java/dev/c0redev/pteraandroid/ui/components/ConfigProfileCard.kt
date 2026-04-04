package dev.c0redev.pteraandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    if (failed || ms == null) return scheme.errorContainer.copy(alpha = 0.45f)
    return when {
        ms < 85L -> scheme.tertiaryContainer.copy(alpha = 0.55f)
        ms < 200L -> scheme.secondaryContainer.copy(alpha = 0.45f)
        else -> scheme.errorContainer.copy(alpha = 0.38f)
    }
}

private fun pingLabelColor(ms: Long?, failed: Boolean, scheme: androidx.compose.material3.ColorScheme): Color {
    if (failed || ms == null) return scheme.onErrorContainer
    return when {
        ms < 85L -> scheme.onTertiaryContainer
        ms < 200L -> scheme.onSecondaryContainer
        else -> scheme.onErrorContainer
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfigProfileCard(
    item: ConfigItemState,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    importLabel: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val hostLine = serverHostFromField(item.config.server)
    val geo = item.geo

    val cardShape = RoundedCornerShape(20.dp)
    val borderMod = if (isActive) {
        Modifier.border(2.dp, scheme.primary.copy(alpha = 0.55f), cardShape)
    } else {
        Modifier
    }
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .then(borderMod),
        shape = cardShape,
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (isActive) 2.dp else 3.dp,
        ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isActive) {
                scheme.primaryContainer.copy(alpha = 0.42f)
            } else {
                scheme.surfaceContainerHigh
            },
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
                if (isActive) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = scheme.primary.copy(alpha = 0.22f),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.config_profile_active),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.primary,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onPrimary,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(primaryLabel)
                        }
                        if (onImport != null && importLabel != null) {
                            FilledTonalButton(
                                onClick = onImport,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(importLabel)
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isActive) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = scheme.primary.copy(alpha = 0.22f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.config_profile_active),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = scheme.primary,
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = onPrimary,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(primaryLabel)
                        }
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
