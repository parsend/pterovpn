package dev.c0redev.pteraandroid.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.Cloud
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.c0redev.pteraandroid.BuildConfig
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Ptera VPN",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "by c0redev (maxkrya)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            HomeGuideCard(onNavigateToTab = onNavigateToTab)
        }

        item {
            SectionCard {
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
                            text = "Статус подключения",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    StatusRow(label = "Режим", value = conn.mode ?: settings.mode)
                    StatusRow(label = "Подключено", value = if (conn.connected) "Да" else "Нет")
                    StatusRow(label = "Готов", value = if (conn.ready) "Да" else "Нет")

                    if (!conn.error.isNullOrBlank()) {
                        Text(
                            text = "Ошибка: ${conn.error}",
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
                            onClick = { vm.refreshLocalConfigs(); vm.refreshCloudConfigs(true) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("Обновить", modifier = Modifier.padding(start = 8.dp))
                        }
                        Button(
                            onClick = { vm.disconnect() },
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
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("Отключить", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }

        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Сводка",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    InfoRow(label = "Локальные конфиги", value = local.size.toString())
                    InfoRow(label = "Cloud конфиги", value = cloud.size.toString())
                    InfoRow(label = "Логов в буфере", value = logs.size.toString())
                }
            }
        }

        if (logs.isNotEmpty()) {
            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Последние логи",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        logs.takeLast(5).forEach { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
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
    val entries = remember {
        listOf(
            GuideEntry(
                title = "Configs",
                hint = "Локальные профили, кнопка Connect.",
                steps = listOf(
                    "Внизу нажми вкладку Configs.",
                    "Добавить, в диалоге server:token, сохрани.",
                    "На карточке Connect. Режим TUN даст запрос VPN.",
                    "Edit или Delete на карточке.",
                ),
                route = "configs",
                icon = Icons.Outlined.Dns,
            ),
            GuideEntry(
                title = "Cloud",
                hint = "Список из cloud-config.",
                steps = listOf(
                    "Внизу Cloud.",
                    "Обновить cloud, дождись карточек.",
                    "На карточке Подключить.",
                ),
                route = "cloud",
                icon = Icons.Outlined.Cloud,
            ),
            GuideEntry(
                title = "Protect",
                hint = "Глобальный protection.",
                steps = listOf(
                    "Внизу Protect.",
                    "Баланс, Усиленная или Авто по метрикам сессий.",
                    "Поля obfuscation, junk, pad при необходимости.",
                    "Сохранить или Очистить внизу.",
                ),
                route = "protection",
                icon = Icons.Outlined.Security,
            ),
            GuideEntry(
                title = "Logs",
                hint = "Ядро, тег в квадратных скобках.",
                steps = listOf(
                    "Внизу Logs.",
                    "Пусто пока не было Connect. Сначала Configs или Cloud.",
                    "Листай, смотри ERR, DPI, DROP.",
                ),
                route = "logs",
                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
            ),
            GuideEntry(
                title = "Settings",
                hint = "Режим и обновления.",
                steps = listOf(
                    "Внизу Settings.",
                    "TUN или Proxy, у proxy поле listen.",
                    "В tun IPv6, транспорт, dual tun-tcp.",
                    "Сохранить. Обновления, кнопка Проверить обновления.",
                ),
                route = "settings",
                icon = Icons.Outlined.Settings,
            ),
        )
    }
    var openRoute by remember { mutableStateOf<String?>(null) }

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Куда тыкать",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Строка раскрывает шаги. Открыть раздел переключает нижнюю панель.",
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
                            contentDescription = null,
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
                                Text("Открыть раздел")
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Клиент и ядро, c0redev (maxkrya). Не взлетело, смотри Logs и Ошибка в статусе выше.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
