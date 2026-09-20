package ai.rever.boss.plugin.dynamic.clipboard

/**
 * One entry in the clipboard ring buffer.
 *
 * [id] is stable for the lifetime of the entry, including across restarts
 * (it is persisted with the entry). [createdAt] is the millisecond epoch
 * the entry was first seen on the clipboard; eviction sorts by this field.
 *
 * [text] is the value actually written to the system clipboard by this
 * plugin when the user clicks Restore, and is the value the reveal MCP
 * tool returns. [maskedText] is the value the panel renders and the value
 * every other MCP tool returns - it equals [text] for normal entries and
 * equals [SecretMask.MASKED_PLACEHOLDER] for entries that matched the
 * secret pattern at capture time.
 *
 * [pinned] marks an entry that survives clear-history and ring-buffer
 * eviction. Pinned entries still respect the global MAX_ENTRIES cap on
 * total stored entries, but eviction prefers to drop unpinned ones first.
 *
 * [sourceLabel] is a free-form hint the poller records when it captures
 * the entry - today this is always "clipboard", since the host provider
 * is the only capture source, but the field is here so a future capture
 * (e.g. a clipboard hook raised by another plugin) can leave a breadcrumb
 * without changing the data shape.
 */
data class ClipboardEntry(
    val id: String,
    val text: String,
    val maskedText: String,
    val createdAt: Long,
    val pinned: Boolean,
    val sourceLabel: String = "clipboard",
)
