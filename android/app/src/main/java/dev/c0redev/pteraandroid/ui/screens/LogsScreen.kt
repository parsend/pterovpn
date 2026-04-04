package dev.c0redev.pteraandroid.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.c0redev.pteraandroid.R
import dev.c0redev.pteraandroid.clientlog.parseLogLine
import dev.c0redev.pteraandroid.clientlog.tagColor
import dev.c0redev.pteraandroid.theme.PteraSpacing
import dev.c0redev.pteraandroid.ui.ConnectionViewModel
import dev.c0redev.pteraandroid.ui.components.SectionCard

private val TAG_FILTERS = listOf("ALL", "ERR", "WARN", "OK", "INFO", "DPI", "DROP", "TRAFFIC")

@Composable
fun LogsScreen(vm: ConnectionViewModel, padding: PaddingValues) {
    val logs = vm.logs.collectAsState().value
    var query by remember { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf("ALL") }
    var pinBottom by remember { mutableStateOf(true) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val filtered = remember(logs, query, tagFilter) {
        logs.asSequence()
            .filter { line ->
                if (query.isBlank()) true else line.contains(query, ignoreCase = true)
            }
            .filter { line ->
                if (tagFilter == "ALL") true else parseLogLine(line).tag == tagFilter
            }
            .toList()
    }

    LaunchedEffect(filtered.size, pinBottom) {
        if (pinBottom && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = PteraSpacing.screenHorizontal, vertical = PteraSpacing.screenVertical),
    ) {
        Text(
            text = stringResource(R.string.logs_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.logs_search_hint)) },
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.logs_pin_bottom),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = pinBottom, onCheckedChange = { pinBottom = it })
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TAG_FILTERS.forEach { tag ->
                FilterChip(
                    selected = tagFilter == tag,
                    onClick = { tagFilter = tag },
                    label = { Text(tag) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    val text = filtered.joinToString("\n")
                    clipboard.setText(AnnotatedString(text))
                    android.widget.Toast.makeText(context, context.getString(R.string.logs_copied), android.widget.Toast.LENGTH_SHORT).show()
                },
                enabled = filtered.isNotEmpty(),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.logs_copy_all))
            }
        }

        if (filtered.isEmpty() && logs.isEmpty()) {
            Text(
                text = stringResource(R.string.logs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            SectionCard(modifier = Modifier.weight(1f), expandHeight = true) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(filtered, key = { ix, line -> "$ix$line" }) { _, line ->
                        val parsed = parseLogLine(line)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "[${parsed.tag}]",
                                color = tagColor(parsed.tag),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = parsed.body,
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
