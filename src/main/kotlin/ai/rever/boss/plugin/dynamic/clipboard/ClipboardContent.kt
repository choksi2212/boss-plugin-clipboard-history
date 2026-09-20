package ai.rever.boss.plugin.dynamic.clipboard

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun ClipboardContent(viewModel: ClipboardViewModel) {
    BossTheme {
        if (viewModel.clipboardUnavailable.collectAsState().value) {
            ClipboardUnavailableMessage()
        } else {
            ClipboardPanel(viewModel)
        }
    }
}

@Composable
private fun ClipboardUnavailableMessage() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Clipboard History",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Host clipboard unavailable",
                fontSize = 12.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ClipboardPanel(viewModel: ClipboardViewModel) {
    val visible by viewModel.visibleEntries.collectAsState()
    val query by viewModel.query.collectAsState()
    val paused by viewModel.paused.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var confirmClear by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            PrivacyBanner(paused = paused)
            ToolbarRow(
                query = query,
                paused = paused,
                onQueryChange = { viewModel.setQuery(it) },
                onTogglePause = { viewModel.togglePause() },
                onClearAll = { confirmClear = true },
            )
            ToastBar(statusMessage = statusMessage, errorMessage = errorMessage, onDismiss = { viewModel.clearMessages() })
            EntryList(entries = visible, onRestore = { viewModel.restore(it) }, onTogglePin = { viewModel.togglePin(it) }, onDelete = { viewModel.deleteEntry(it) })
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear unpinned entries?") },
            text = { Text("Pinned entries will be kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearUnpinned()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
            backgroundColor = MaterialTheme.colors.surface,
            shape = RoundedCornerShape(8.dp),
        )
    }
}

@Composable
private fun PrivacyBanner(paused: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BossThemeColors.WarningColor.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(BossThemeColors.WarningColor),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (paused) "Capture paused - secrets will not be recorded while paused"
                else "Entries matching SECRET/TOKEN/KEY/PASSWORD/CREDENTIAL/API_KEY/PRIVATE KEY are masked to <masked>",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToolbarRow(
    query: String,
    paused: Boolean,
    onQueryChange: (String) -> Unit,
    onTogglePause: () -> Unit,
    onClearAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f).height(36.dp),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = MaterialTheme.colors.onSurface,
                focusedBorderColor = MaterialTheme.colors.primary.copy(alpha = 0.6f),
                unfocusedBorderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.2f),
            ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        IconButton(onClick = onTogglePause, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (paused) "Resume capture" else "Pause capture",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
        IconButton(onClick = onClearAll, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Clear unpinned",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ToastBar(statusMessage: String?, errorMessage: String?, onDismiss: () -> Unit) {
    LaunchedEffect(statusMessage, errorMessage) {
        delay(2500)
        onDismiss()
    }
    val isError = errorMessage != null
    val message = errorMessage ?: statusMessage ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) BossThemeColors.ErrorColor else BossThemeColors.SuccessColor)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            fontSize = 11.sp,
            color = BossThemeColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(12.dp),
                tint = BossThemeColors.TextPrimary.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun EntryList(
    entries: List<ClipboardEntry>,
    onRestore: (ClipboardEntry) -> Unit,
    onTogglePin: (ClipboardEntry) -> Unit,
    onDelete: (ClipboardEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyState()
        return
    }

    val pinned = entries.filter { it.pinned }
    val unpinned = entries.filter { !it.pinned }
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (pinned.isNotEmpty()) {
                item(key = "pinned-header") { SectionHeader("Pinned", pinned.size) }
                items(pinned, key = { "pin-${it.id}" }) { e ->
                    EntryRow(entry = e, onRestore = onRestore, onTogglePin = onTogglePin, onDelete = onDelete)
                }
            }
            if (unpinned.isNotEmpty()) {
                item(key = "recent-header") { SectionHeader("Recent", unpinned.size) }
                items(unpinned, key = { "rec-${it.id}" }) { e ->
                    EntryRow(entry = e, onRestore = onRestore, onTogglePin = onTogglePin, onDelete = onDelete)
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.4f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No clipboard captures yet",
                fontSize = 12.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = "($count)", fontSize = 11.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
private fun EntryRow(
    entry: ClipboardEntry,
    onRestore: (ClipboardEntry) -> Unit,
    onTogglePin: (ClipboardEntry) -> Unit,
    onDelete: (ClipboardEntry) -> Unit,
) {
    val isMasked = entry.maskedText == SecretMask.MASKED_PLACEHOLDER
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRestore(entry) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (isMasked) BossThemeColors.WarningColor.copy(alpha = 0.2f)
                    else BossThemeColors.AccentColor.copy(alpha = 0.18f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isMasked) "!" else "#",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isMasked) BossThemeColors.WarningColor else BossThemeColors.AccentColor,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.maskedText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatTimestamp(entry.createdAt),
                fontSize = 10.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
            )
        }
        Column {
            IconButton(onClick = { onTogglePin(entry) }, modifier = Modifier.size(22.dp)) {
                Icon(
                    imageVector = if (entry.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (entry.pinned) "Unpin" else "Pin",
                    modifier = Modifier.size(13.dp),
                    tint = if (entry.pinned) BossThemeColors.AccentColor else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = { onDelete(entry) }, modifier = Modifier.size(22.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Delete",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

private fun formatTimestamp(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val delta = now - epochMs
    return when {
        delta < 60_000 -> "just now"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        delta < 86_400_000 -> "${delta / 3_600_000}h ago"
        delta < 7 * 86_400_000L -> "${delta / 86_400_000}d ago"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(epochMs))
    }
}
