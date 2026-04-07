package dev.c0redev.pteraandroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.c0redev.pteraandroid.R
import dev.c0redev.pteraandroid.domain.model.Config
import dev.c0redev.pteraandroid.domain.model.ProtectionOptions
import dev.c0redev.pteraandroid.theme.PteraSpacing
import dev.c0redev.pteraandroid.ui.ConfigItemState
import dev.c0redev.pteraandroid.ui.ConnectionViewModel
import dev.c0redev.pteraandroid.ui.components.ConfigProfileCard
import dev.c0redev.pteraandroid.ui.components.StyledTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigsScreen(vm: ConnectionViewModel, padding: PaddingValues) {
    val localItems = vm.localConfigs.collectAsState().value
    val cloudItems = vm.cloudConfigs.collectAsState().value
    val cloudLoading = vm.cloudLoading.collectAsState().value
    val localRefreshing = vm.localRefreshing.collectAsState().value
    val localInitialLoad = vm.localConfigsInitialLoad.collectAsState().value
    val localShowWait = (localInitialLoad || localRefreshing) && localItems.isEmpty()
    val cloudProgress = vm.cloudRefreshProgress.collectAsState().value
    val connectingName = vm.connectingProfileName.collectAsState().value
    val conn = vm.connection.collectAsState().value
    val activeProfileName = vm.activeProfileName.collectAsState().value
    var tabIndex by remember { mutableIntStateOf(0) }

    var editorOpen by remember { mutableStateOf(false) }
    var editorOldName by remember { mutableStateOf<String?>(null) }
    var editorCfg by remember { mutableStateOf<Config?>(null) }
    var importTarget by remember { mutableStateOf<ConfigItemState?>(null) }
    var deleteConfirmName by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = PteraSpacing.screenHorizontal, vertical = PteraSpacing.screenVertical),
    ) {
        Text(
            text = stringResource(R.string.configs_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.configs_screen_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    editorOldName = null
                    editorCfg = null
                    editorOpen = true
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.configs_add))
            }
            if (conn.connected) {
                FilledTonalButton(
                    onClick = { vm.disconnect() },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.home_disconnect))
                }
            }
        }

        ConfigSourceSegment(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 4.dp),
            selectedIndex = tabIndex,
            onSelect = { tabIndex = it },
            localLabel = stringResource(R.string.configs_tab_local),
            cloudLabel = stringResource(R.string.configs_tab_cloud),
        )

        when (tabIndex) {
            0 -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PteraSpacing.sectionGap),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.refreshLocalConfigs() },
                            enabled = !localRefreshing,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.configs_local_refresh))
                        }
                        if (localRefreshing) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                stringResource(R.string.configs_local_refreshing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (localShowWait) {
                    item {
                        Text(
                            stringResource(R.string.configs_local_wait),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(3, key = { "local_skel_$it" }) {
                        LocalProfileSkeleton()
                    }
                }
                if (localItems.isEmpty() && !localShowWait) {
                    item {
                        Text(
                            text = stringResource(R.string.configs_empty_local),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                }
                items(localItems, key = { it.name }) { it ->
                    val profileActive = conn.connected && activeProfileName == it.name
                    ConfigProfileCard(
                        item = it,
                        isActive = profileActive,
                        primaryBusy = connectingName == it.name,
                        primaryBusyLabel = stringResource(R.string.config_connecting),
                        primaryLabel = stringResource(R.string.configs_connect),
                        onPrimary = { vm.connect(it.name, it.config) },
                        onEdit = {
                            editorOldName = it.name
                            editorCfg = it.config
                            editorOpen = true
                        },
                        onDelete = { deleteConfirmName = it.name },
                    )
                }
            }
            1 -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(PteraSpacing.sectionGap),
            ) {
                item {
                    Button(
                        onClick = { vm.refreshCloudConfigs(true) },
                        enabled = !cloudLoading,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.configs_cloud_refresh))
                    }
                }
                if (cloudLoading) {
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (cloudProgress > 0f) {
                                LinearProgressIndicator(
                                    progress = { cloudProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Text(
                                stringResource(R.string.configs_cloud_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (!cloudLoading && cloudItems.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.configs_empty_cloud),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                items(cloudItems, key = { it.name }) { item ->
                    val profileActive = conn.connected && activeProfileName == item.name
                    ConfigProfileCard(
                        item = item,
                        isActive = profileActive,
                        primaryBusy = connectingName == item.name,
                        primaryBusyLabel = stringResource(R.string.config_connecting),
                        primaryLabel = stringResource(R.string.configs_cloud_connect),
                        onPrimary = {
                            vm.connect(
                                item.name,
                                item.config,
                                applyCloudDefaults = true,
                                cloudServerMode = item.serverMode,
                                cloudProbeIpv6 = item.ipv6Support,
                            )
                        },
                        onEdit = null,
                        onDelete = null,
                        onImport = { importTarget = item },
                        importLabel = stringResource(R.string.configs_import_local),
                    )
                }
            }
        }
    }

    val deleteName = deleteConfirmName
    if (deleteName != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmName = null },
            title = { Text(stringResource(R.string.configs_delete_title)) },
            text = { Text(stringResource(R.string.configs_delete_message, deleteName)) },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteLocalConfig(deleteName)
                        deleteConfirmName = null
                    },
                ) {
                    Text(stringResource(R.string.configs_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmName = null }) {
                    Text(stringResource(R.string.configs_import_cancel))
                }
            },
        )
    }

    val importTargetVal = importTarget
    if (importTargetVal != null) {
        var importName by remember(importTargetVal.name) { mutableStateOf(importTargetVal.name) }
        AlertDialog(
            onDismissRequest = { importTarget = null },
            title = { Text(stringResource(R.string.configs_import_title)) },
            confirmButton = {
                Button(
                    onClick = {
                        vm.importCloudAsLocal(importName, importTargetVal)
                        importTarget = null
                        tabIndex = 0
                    },
                ) { Text(stringResource(R.string.configs_import_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { importTarget = null }) {
                    Text(stringResource(R.string.configs_import_cancel))
                }
            },
            text = {
                StyledTextField(
                    value = importName,
                    onValueChange = { importName = it },
                    label = stringResource(R.string.configs_import_name_label),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    if (editorOpen) {
        ConfigEditorDialog(
            oldName = editorOldName,
            initialConfig = editorCfg,
            onDismiss = { editorOpen = false },
            onSave = { newName, newCfg ->
                editorOpen = false
                val old = editorOldName
                if (old != null && old != newName) vm.deleteLocalConfig(old)
                vm.upsertLocalConfig(newName, newCfg)
            },
        )
    }
}

@Composable
private fun ConfigSourceSegment(
    modifier: Modifier = Modifier,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    localLabel: String,
    cloudLabel: String,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerLow,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier
                .padding(4.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(0 to localLabel, 1 to cloudLabel).forEach { (idx, label) ->
                val sel = selectedIndex == idx
                val interaction = remember(idx) { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (sel) scheme.primaryContainer.copy(alpha = 0.72f) else Color.Transparent,
                        )
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onSelect(idx) },
                        )
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (sel) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigEditorDialog(
    oldName: String?,
    initialConfig: Config?,
    onDismiss: () -> Unit,
    onSave: (String, Config) -> Unit,
) {
    var name by remember { mutableStateOf(oldName ?: "default") }
    var connection by remember { mutableStateOf("") }
    var routes by remember { mutableStateOf("") }
    var exclude by remember { mutableStateOf("") }
    var tunCIDR6 by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("auto") }
    var quicServer by remember { mutableStateOf("") }
    var quicServerName by remember { mutableStateOf("") }
    var quicSkipVerify by remember { mutableStateOf("") }
    var quicCertPin by remember { mutableStateOf("") }
    var quicCaCert by remember { mutableStateOf("") }
    var traceLog by remember { mutableStateOf(false) }
    var protEnabled by remember { mutableStateOf(false) }
    var pObf by remember { mutableStateOf("") }
    var pJunkCount by remember { mutableStateOf("0") }
    var pJunkMin by remember { mutableStateOf("0") }
    var pJunkMax by remember { mutableStateOf("0") }
    var pPadS1 by remember { mutableStateOf("0") }
    var pPadS2 by remember { mutableStateOf("0") }
    var pPadS3 by remember { mutableStateOf("0") }
    var pPadS4 by remember { mutableStateOf("0") }
    var pPreCheck by remember { mutableStateOf(false) }
    var pMagicSplit by remember { mutableStateOf("") }
    var pJunkStyle by remember { mutableStateOf("") }
    var pFlush by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialConfig, oldName) {
        err = null
        name = oldName ?: "default"
        if (initialConfig != null) {
            connection = "${initialConfig.server}:${initialConfig.token}"
            routes = initialConfig.routes ?: ""
            exclude = initialConfig.exclude ?: ""
            tunCIDR6 = initialConfig.tunCIDR6 ?: ""
            transport = initialConfig.transport ?: "auto"
            quicServer = initialConfig.quicServer ?: ""
            quicServerName = initialConfig.quicServerName ?: ""
            quicSkipVerify = initialConfig.quicSkipVerify?.toString() ?: ""
            quicCertPin = initialConfig.quicCertPinSHA256 ?: ""
            quicCaCert = initialConfig.quicCaCert ?: ""
            traceLog = initialConfig.quicTraceLog == true
            val pr = initialConfig.protection
            protEnabled = pr != null
            if (pr != null) {
                pObf = pr.obfuscation ?: ""
                pJunkCount = pr.junkCount.toString()
                pJunkMin = pr.junkMin.toString()
                pJunkMax = pr.junkMax.toString()
                pPadS1 = pr.padS1.toString()
                pPadS2 = pr.padS2.toString()
                pPadS3 = pr.padS3.toString()
                pPadS4 = pr.padS4.toString()
                pPreCheck = pr.preCheck
                pMagicSplit = pr.magicSplit ?: ""
                pJunkStyle = pr.junkStyle ?: ""
                pFlush = pr.flushPolicy ?: ""
            } else {
                pObf = ""
                pJunkCount = "0"
                pJunkMin = "0"
                pJunkMax = "0"
                pPadS1 = "0"
                pPadS2 = "0"
                pPadS3 = "0"
                pPadS4 = "0"
                pPreCheck = false
                pMagicSplit = ""
                pJunkStyle = ""
                pFlush = ""
            }
        } else {
            connection = ""
            routes = ""
            exclude = ""
            tunCIDR6 = ""
            transport = "auto"
            quicServer = ""
            quicServerName = ""
            quicSkipVerify = ""
            quicCertPin = ""
            quicCaCert = ""
            traceLog = false
            protEnabled = false
            pObf = ""
            pJunkCount = "0"
            pJunkMin = "0"
            pJunkMax = "0"
            pPadS1 = "0"
            pPadS2 = "0"
            pPadS3 = "0"
            pPadS4 = "0"
            pPreCheck = false
            pMagicSplit = ""
            pJunkStyle = ""
            pFlush = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    err = null
                    val parsed = Config.parseConnection(connection)
                    if (parsed == null) {
                        err = "connection: host:port:key"
                        return@Button
                    }
                    val (server, token) = parsed
                    val safeName = Config.sanitizeName(name)

                    val tr = transport.trim().lowercase()
                    val transportOut = when (tr) {
                        "", "auto" -> null
                        "tcp" -> "tcp"
                        "quic" -> "quic"
                        else -> null
                    }

                    val skip = quicSkipVerify.trim().lowercase()
                    val quicSkipOut = when {
                        skip.isBlank() -> null
                        skip == "true" -> true
                        skip == "false" -> false
                        else -> null
                    }

                    if (transportOut == null && transport.trim().isNotBlank() && tr != "auto") {
                        err = "transport: tcp/quic/auto"
                        return@Button
                    }

                    if (quicSkipOut == null && quicSkipVerify.trim().isNotBlank()) {
                        err = "quic skipVerify: true/false/empty"
                        return@Button
                    }

                    val pin = quicCertPin.replace(":", "").trim().takeIf { it.isNotBlank() }
                    val ca = quicCaCert.trim().takeIf { it.isNotBlank() }
                    val routesOut = routes.trim().takeIf { it.isNotBlank() }
                    val excludeOut = exclude.trim().takeIf { it.isNotBlank() }
                    val tun6Out = tunCIDR6.trim().takeIf { it.isNotBlank() }

                    val quicSrvOut = quicServer.trim().takeIf { it.isNotBlank() }
                    val quicSrvFinal = if (transportOut == "tcp") null else quicSrvOut
                    val prot = if (!protEnabled) {
                        null
                    } else {
                        ProtectionOptions(
                            obfuscation = pObf.trim().takeIf { it.isNotEmpty() },
                            junkCount = pJunkCount.toIntOrNull() ?: 0,
                            junkMin = pJunkMin.toIntOrNull() ?: 0,
                            junkMax = pJunkMax.toIntOrNull() ?: 0,
                            padS1 = pPadS1.toIntOrNull() ?: 0,
                            padS2 = pPadS2.toIntOrNull() ?: 0,
                            padS3 = pPadS3.toIntOrNull() ?: 0,
                            padS4 = pPadS4.toIntOrNull() ?: 0,
                            preCheck = pPreCheck,
                            magicSplit = pMagicSplit.trim().takeIf { it.isNotEmpty() },
                            junkStyle = pJunkStyle.trim().takeIf { it.isNotEmpty() },
                            flushPolicy = pFlush.trim().takeIf { it.isNotEmpty() },
                        )
                    }
                    val cfg = Config(
                        server = server,
                        token = token,
                        routes = routesOut,
                        exclude = excludeOut,
                        tunCIDR6 = tun6Out,
                        transport = transportOut,
                        quicServer = quicSrvFinal,
                        quicServerName = quicServerName.trim().takeIf { it.isNotBlank() },
                        quicSkipVerify = quicSkipOut,
                        quicCertPinSHA256 = pin,
                        quicCaCert = ca,
                        quicTraceLog = if (traceLog) true else null,
                        protection = prot,
                    )

                    onSave(safeName, cfg)
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Редактор конфига") },
        text = {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StyledTextField(value = name, onValueChange = { name = it }, label = "Имя", modifier = Modifier.fillMaxWidth())
                StyledTextField(value = connection, onValueChange = { connection = it }, label = "host:port:key", modifier = Modifier.fillMaxWidth())
                StyledTextField(value = routes, onValueChange = { routes = it }, label = "routes (cidrs)", modifier = Modifier.fillMaxWidth())
                StyledTextField(value = exclude, onValueChange = { exclude = it }, label = "exclude (cidrs)", modifier = Modifier.fillMaxWidth())
                StyledTextField(value = tunCIDR6, onValueChange = { tunCIDR6 = it }, label = "tunCIDR6", modifier = Modifier.fillMaxWidth())
                StyledTextField(value = transport, onValueChange = { transport = it }, label = "transport tcp/quic/auto", modifier = Modifier.fillMaxWidth())
                StyledTextField(value = quicServer, onValueChange = { quicServer = it }, label = "quic host:port", modifier = Modifier.fillMaxWidth())
                StyledTextField(value = quicServerName, onValueChange = { quicServerName = it }, label = "quic SNI", modifier = Modifier.fillMaxWidth())
                StyledTextField(value = quicSkipVerify, onValueChange = { quicSkipVerify = it }, label = "quic skipVerify true/false/пусто", modifier = Modifier.fillMaxWidth())
                StyledTextField(value = quicCertPin, onValueChange = { quicCertPin = it }, label = "quic pin sha256 hex", modifier = Modifier.fillMaxWidth())
                StyledTextField(value = quicCaCert, onValueChange = { quicCaCert = it }, label = "quic CA PEM путь", modifier = Modifier.fillMaxWidth())

                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = traceLog, onCheckedChange = { traceLog = it })
                    Text("quicTraceLog", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }

                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = protEnabled, onCheckedChange = { protEnabled = it })
                    Text("protection в конфиге", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (protEnabled) {
                    StyledTextField(value = pObf, onValueChange = { pObf = it }, label = "prot obfuscation", modifier = Modifier.fillMaxWidth())
                    StyledTextField(value = pJunkCount, onValueChange = { pJunkCount = it }, label = "prot junkCount", modifier = Modifier.fillMaxWidth())
                    StyledTextField(value = pJunkMin, onValueChange = { pJunkMin = it }, label = "prot junkMin", modifier = Modifier.fillMaxWidth())
                    StyledTextField(value = pJunkMax, onValueChange = { pJunkMax = it }, label = "prot junkMax", modifier = Modifier.fillMaxWidth())
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = pPreCheck, onCheckedChange = { pPreCheck = it })
                        Text("prot preCheck", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    StyledTextField(value = pPadS1, onValueChange = { pPadS1 = it }, label = "prot padS1", modifier = Modifier.fillMaxWidth())
                    StyledTextField(value = pPadS2, onValueChange = { pPadS2 = it }, label = "prot padS2", modifier = Modifier.fillMaxWidth())
                    StyledTextField(value = pPadS3, onValueChange = { pPadS3 = it }, label = "prot padS3", modifier = Modifier.fillMaxWidth())
                    StyledTextField(value = pPadS4, onValueChange = { pPadS4 = it }, label = "prot padS4", modifier = Modifier.fillMaxWidth())
                    StyledTextField(value = pMagicSplit, onValueChange = { pMagicSplit = it }, label = "prot magicSplit", modifier = Modifier.fillMaxWidth())
                    StyledTextField(value = pJunkStyle, onValueChange = { pJunkStyle = it }, label = "prot junkStyle", modifier = Modifier.fillMaxWidth())
                    StyledTextField(value = pFlush, onValueChange = { pFlush = it }, label = "prot flushPolicy", modifier = Modifier.fillMaxWidth())
                }

                if (err != null) {
                    Text(
                        text = err ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    )
}

@Composable
private fun LocalProfileSkeleton() {
    val bar = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.42f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(bar),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .height(13.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(bar),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bar),
                )
                Box(
                    Modifier
                        .width(52.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bar),
                )
            }
        }
    }
}

