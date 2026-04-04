package dev.c0redev.pteraandroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.c0redev.pteraandroid.R
import dev.c0redev.pteraandroid.domain.model.ProtectionOptions
import dev.c0redev.pteraandroid.domain.model.ProtectionPresets
import dev.c0redev.pteraandroid.ui.ConnectionViewModel
import dev.c0redev.pteraandroid.ui.components.SectionCard
import dev.c0redev.pteraandroid.ui.components.StyledTextField

@Composable
fun ProtectionScreen(vm: ConnectionViewModel, padding: PaddingValues) {
    val current = vm.globalProtection.collectAsState().value
    val metrics = vm.metrics.collectAsState().value.records
    var obf by remember { mutableStateOf("") }
    var junkCount by remember { mutableStateOf("0") }
    var junkMin by remember { mutableStateOf("0") }
    var junkMax by remember { mutableStateOf("0") }
    var padS1 by remember { mutableStateOf("0") }
    var padS2 by remember { mutableStateOf("0") }
    var padS3 by remember { mutableStateOf("0") }
    var padS4 by remember { mutableStateOf("0") }
    var preCheck by remember { mutableStateOf(false) }
    var magicSplit by remember { mutableStateOf("") }
    var junkStyle by remember { mutableStateOf("") }
    var flushPolicy by remember { mutableStateOf("") }

    LaunchedEffect(current) {
        obf = current?.obfuscation ?: ""
        junkCount = (current?.junkCount ?: 0).toString()
        junkMin = (current?.junkMin ?: 0).toString()
        junkMax = (current?.junkMax ?: 0).toString()
        padS1 = (current?.padS1 ?: 0).toString()
        padS2 = (current?.padS2 ?: 0).toString()
        padS3 = (current?.padS3 ?: 0).toString()
        padS4 = (current?.padS4 ?: 0).toString()
        preCheck = current?.preCheck ?: false
        magicSplit = current?.magicSplit ?: ""
        junkStyle = current?.junkStyle ?: ""
        flushPolicy = current?.flushPolicy ?: ""
    }

    val applyPreset = { p: ProtectionOptions ->
        obf = p.obfuscation ?: ""
        junkCount = p.junkCount.toString()
        junkMin = p.junkMin.toString()
        junkMax = p.junkMax.toString()
        padS1 = p.padS1.toString()
        padS2 = p.padS2.toString()
        padS3 = p.padS3.toString()
        padS4 = p.padS4.toString()
        preCheck = p.preCheck
        magicSplit = p.magicSplit ?: ""
        junkStyle = p.junkStyle ?: ""
        flushPolicy = p.flushPolicy ?: ""
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.protection_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Аналитика",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    if (metrics.isEmpty()) {
                        Text(
                            text = "Нет данных",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        metrics.asReversed().forEach { r ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "${r.configName}  •  ${r.server}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                )
                                Text(
                                    text = "hs=${r.handshakeOk}  •  err=${r.errorType ?: '-'}  •  dns=${r.dnsOkBefore}/${r.dnsOkAfter ?: '-'}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Авто-стратегия",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    Text(
                        text = "Пресеты заполняют поля. «По метрикам» — по последним сессиям.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { applyPreset(ProtectionPresets.balanced()) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Баланс", style = MaterialTheme.typography.labelLarge)
                        }
                        FilledTonalButton(
                            onClick = { applyPreset(ProtectionPresets.strict()) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Усиленная", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    FilledTonalButton(
                        onClick = { applyPreset(ProtectionPresets.suggestFromMetrics(metrics)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Авто по метрикам сессий")
                    }
                }
            }
        }
        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Глобальные настройки",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    StyledTextField(
                        value = obf,
                        onValueChange = { obf = it },
                        label = "obfuscation",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StyledTextField(
                            value = junkCount,
                            onValueChange = { junkCount = it },
                            label = "junkCount",
                            modifier = Modifier.weight(1f),
                        )
                        StyledTextField(
                            value = junkMin,
                            onValueChange = { junkMin = it },
                            label = "junkMin",
                            modifier = Modifier.weight(1f),
                        )
                        StyledTextField(
                            value = junkMax,
                            onValueChange = { junkMax = it },
                            label = "junkMax",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "preCheck",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        )
                        Switch(
                            checked = preCheck,
                            onCheckedChange = { preCheck = it },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StyledTextField(
                            value = padS1,
                            onValueChange = { padS1 = it },
                            label = "padS1",
                            modifier = Modifier.weight(1f),
                        )
                        StyledTextField(
                            value = padS2,
                            onValueChange = { padS2 = it },
                            label = "padS2",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StyledTextField(
                            value = padS3,
                            onValueChange = { padS3 = it },
                            label = "padS3",
                            modifier = Modifier.weight(1f),
                        )
                        StyledTextField(
                            value = padS4,
                            onValueChange = { padS4 = it },
                            label = "padS4",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    StyledTextField(
                        value = magicSplit,
                        onValueChange = { magicSplit = it },
                        label = "magicSplit",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StyledTextField(
                        value = junkStyle,
                        onValueChange = { junkStyle = it },
                        label = "junkStyle",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StyledTextField(
                        value = flushPolicy,
                        onValueChange = { flushPolicy = it },
                        label = "flushPolicy",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                vm.saveGlobalProtection(ProtectionOptions(
                                    obfuscation = obf.takeIf { it.isNotBlank() },
                                    junkCount = junkCount.toIntOrNull() ?: 0,
                                    junkMin = junkMin.toIntOrNull() ?: 0,
                                    junkMax = junkMax.toIntOrNull() ?: 0,
                                    padS1 = padS1.toIntOrNull() ?: 0,
                                    padS2 = padS2.toIntOrNull() ?: 0,
                                    padS3 = padS3.toIntOrNull() ?: 0,
                                    padS4 = padS4.toIntOrNull() ?: 0,
                                    preCheck = preCheck,
                                    magicSplit = magicSplit.takeIf { it.isNotBlank() },
                                    junkStyle = junkStyle.takeIf { it.isNotBlank() },
                                    flushPolicy = flushPolicy.takeIf { it.isNotBlank() },
                                ))
                            },
                            modifier = Modifier.weight(1f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        ) {
                            Text("Сохранить")
                        }
                        androidx.compose.material3.FilledTonalButton(
                            onClick = { vm.saveGlobalProtection(null) },
                            modifier = Modifier.weight(1f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        ) {
                            Text("Очистить")
                        }
                    }
                }
            }
        }
    }
}
