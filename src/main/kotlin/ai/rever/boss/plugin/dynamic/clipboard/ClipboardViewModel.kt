package ai.rever.boss.plugin.dynamic.clipboard

import ai.rever.boss.plugin.api.ClipboardProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder for the clipboard history panel.
 *
 * Owns the [ClipboardStore], [ClipboardPoller] and the small set of UI
 * flags (paused, clipboard-unavailable, search query) that the panel
 * reads through [StateFlow]s. Two scopes live here: a [SupervisorJob]
 * for long-running work that should outlive any single composition, and
 * the lifecycle-tied [pluginScope] the host hands us for everything
 * that must be cancelled when the plugin is disposed.
 *
 * [dispose] cancels the supervisor scope, stops the poller and nulls
 * the callbacks the poller reads; the host calls it when the plugin is
 * disabled or unloaded. Pinned entries are not touched.
 */
class ClipboardViewModel(
    private val clipboardProvider: ClipboardProvider?,
    private val store: ClipboardStore,
    private val pluginScope: CoroutineScope,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default)

    private val poller: ClipboardPoller? = if (clipboardProvider != null) {
        ClipboardPoller(
            clipboard = clipboardProvider,
            store = store,
            isPaused = { _paused.value },
            scope = scope,
        )
    } else {
        null
    }

    private val _entries = MutableStateFlow<List<ClipboardEntry>>(emptyList())
    val entries: StateFlow<List<ClipboardEntry>> = _entries.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _clipboardUnavailable = MutableStateFlow(clipboardProvider == null)
    val clipboardUnavailable: StateFlow<Boolean> = _clipboardUnavailable.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
        poller?.start()
    }

    /**
     * Filtered view of [entries] for the panel: search query applied to
     * the masked text (case-insensitive substring). Pinned entries float
     * to the top regardless of the query, since the panel renders them
     * in a separate section.
     */
    val visibleEntries: StateFlow<List<ClipboardEntry>> = MutableStateFlow<List<ClipboardEntry>>(emptyList()).also { out ->
        // Recompute on either change; collected on the supervisor scope so
        // it survives panel recompositions.
        scope.launch {
            kotlinx.coroutines.flow.combine(_entries, _query) { all, q ->
                if (q.isBlank()) all
                else all.filter { it.maskedText.contains(q, ignoreCase = true) }
            }.collect { out.value = it }
        }
    }.asStateFlow()

    fun setQuery(text: String) {
        _query.value = text
    }

    fun togglePause() {
        val now = !_paused.value
        _paused.value = now
        _statusMessage.value = if (now) "Capture paused" else "Capture resumed"
    }

    fun restore(entry: ClipboardEntry) {
        val provider = clipboardProvider ?: run {
            _errorMessage.value = "Clipboard unavailable"
            return
        }
        if (entry.maskedText == SecretMask.MASKED_PLACEHOLDER) {
            _errorMessage.value = "Masked entries cannot be restored"
            return
        }
        val ok = provider.setText(entry.text)
        if (ok) {
            _statusMessage.value = "Restored to clipboard"
        } else {
            _errorMessage.value = "Clipboard write refused"
        }
    }

    fun togglePin(entry: ClipboardEntry) {
        scope.launch {
            if (entry.pinned) store.unpin(entry.id) else store.pin(entry.id)
            refresh()
        }
    }

    fun deleteEntry(entry: ClipboardEntry) {
        scope.launch {
            store.delete(entry.id)
            refresh()
        }
    }

    fun clearUnpinned() {
        scope.launch {
            store.clearUnpinned()
            refresh()
            _statusMessage.value = "Cleared unpinned entries"
        }
    }

    fun clearMessages() {
        _statusMessage.value = null
        _errorMessage.value = null
    }

    fun dispose() {
        poller?.stop()
        scope.cancel()
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val list = store.entries()
            _entries.value = list
        }
    }
}
