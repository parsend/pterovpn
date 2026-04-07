package dev.c0redev.pteraandroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.c0redev.pteraandroid.BuildConfig
import dev.c0redev.pteraandroid.R
import dev.c0redev.pteraandroid.domain.model.ClientSettings
import dev.c0redev.pteraandroid.quick.QuickConnectPrefs
import dev.c0redev.pteraandroid.ui.ConnectionViewModel
import dev.c0redev.pteraandroid.ui.UpdateUiState
import dev.c0redev.pteraandroid.ui.components.SectionCard
import dev.c0redev.pteraandroid.ui.components.StyledTextField

@Composable
fun SettingsScreen(vm: ConnectionViewModel, padding: PaddingValues) {
    val localNames = vm.localConfigs.collectAsState().value.map { it.name }
    val s = vm.clientSettings.collectAsState().value
    val upd by vm.updateStatus.collectAsState()
    val updateUi by vm.updateUi.collectAsState()
    val remoteTag by vm.remoteReleaseTag.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshRemoteReleaseTag()
    }
    var mode by remember { mutableStateOf(s.mode) }
    var proxyListen by remember { mutableStateOf(s.proxyListen) }
    var systemProxy by remember { mutableStateOf(s.systemProxy) }
    var ipv6Tunnel by remember { mutableStateOf(s.ipv6Tunnel) }
    var dualTun by remember { mutableStateOf(s.dualTun) }
    var transportPref by remember { mutableStateOf(ClientSettings.normalizedTransportPreference(s.transportPreference)) }

    LaunchedEffect(s) {
        mode = s.mode
        proxyListen = s.proxyListen
        systemProxy = s.systemProxy
        ipv6Tunnel = s.ipv6Tunnel
        dualTun = s.dualTun
        transportPref = ClientSettings.normalizedTransportPreference(s.transportPreference)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Режим подключения",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { mode = "tun" },
                        enabled = mode != "tun",
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("TUN")
                    }
                    FilledTonalButton(
                        onClick = { mode = "proxy" },
                        enabled = mode != "proxy",
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Proxy")
                    }
                }

                if (mode == "proxy") {
                    StyledTextField(
                        value = proxyListen,
                        onValueChange = { proxyListen = it },
                        label = "Proxy listen",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "System proxy",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Не применяется на Android",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = systemProxy,
                            onCheckedChange = { systemProxy = it },
                            enabled = mode == "proxy",
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }

                if (mode == "tun") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "IPv6 в TUN",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Если выкл — только IPv4",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = ipv6Tunnel,
                            onCheckedChange = { ipv6Tunnel = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }

                    Text(
                        text = "Транспорт к серверу",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Transport при коннекте, поверх того что в JSON/cloud",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { transportPref = ClientSettings.TRANSPORT_AUTO },
                            enabled = transportPref != ClientSettings.TRANSPORT_AUTO,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Авто")
                        }
                        FilledTonalButton(
                            onClick = { transportPref = ClientSettings.TRANSPORT_TCP },
                            enabled = transportPref != ClientSettings.TRANSPORT_TCP,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("TCP")
                        }
                        FilledTonalButton(
                            onClick = { transportPref = ClientSettings.TRANSPORT_QUIC },
                            enabled = transportPref != ClientSettings.TRANSPORT_QUIC,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("QUIC")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dual tun-tcp",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "если сервер в quic/tcp, то юзается сразу 2 транспорта, потому что потому",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = dualTun,
                            onCheckedChange = { dualTun = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            vm.saveClientSettings(
                                ClientSettings(
                                    mode = mode,
                                    systemProxy = systemProxy,
                                    proxyListen = proxyListen,
                                    ipv6Tunnel = ipv6Tunnel,
                                    dualTun = dualTun,
                                    transportPreference = transportPref,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Сохранить", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.quick_tiles_section),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.quick_tiles_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QuickTileSlotRows(localNames = localNames)
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Обновления",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Сборка ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!remoteTag.isNullOrBlank()) {
                    Text(
                        text = "Релиз на GitHub: $remoteTag",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = { vm.checkForUpdateAndInstall() },
                    enabled = updateUi is UpdateUiState.Idle,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Update,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Проверить обновления", modifier = Modifier.padding(start = 8.dp))
                }

                when (val u = updateUi) {
                    is UpdateUiState.Checking -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.update_checking),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateUiState.Downloading -> {
                        val p = u.progress
                        if (p != null) {
                            LinearProgressIndicator(
                                progress = { p.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            stringResource(R.string.update_downloading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {}
                }

                if (!upd.isNullOrBlank()) {
                    Text(
                        text = upd!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Credits",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Основной разработчик - c0redev (maxkrya)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun QuickTileSlotRows(localNames: List<String>) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val s0 = remember(tick, ctx) { QuickConnectPrefs.getSlotName(ctx, 0) }
    val s1 = remember(tick, ctx) { QuickConnectPrefs.getSlotName(ctx, 1) }
    val s2 = remember(tick, ctx) { QuickConnectPrefs.getSlotName(ctx, 2) }
    fun setSlot(slot: Int, name: String?) {
        QuickConnectPrefs.setSlotName(ctx, slot, name)
        tick++
    }
    QuickSlotRow(
        title = stringResource(R.string.quick_slot_1),
        current = s0,
        names = localNames,
        onSelect = { setSlot(0, it) },
    )
    QuickSlotRow(
        title = stringResource(R.string.quick_slot_2),
        current = s1,
        names = localNames,
        onSelect = { setSlot(1, it) },
    )
    QuickSlotRow(
        title = stringResource(R.string.quick_slot_3),
        current = s2,
        names = localNames,
        onSelect = { setSlot(2, it) },
    )
}

@Composable
private fun QuickSlotRow(
    title: String,
    current: String?,
    names: List<String>,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                text = current ?: stringResource(R.string.quick_slot_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.quick_slot_pick))
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            onSelect(null)
                            open = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.quick_slot_none))
                    }
                    names.forEach { n ->
                        TextButton(
                            onClick = {
                                onSelect(n)
                                open = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(n)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.configs_import_cancel))
                }
            },
        )
    }
}
