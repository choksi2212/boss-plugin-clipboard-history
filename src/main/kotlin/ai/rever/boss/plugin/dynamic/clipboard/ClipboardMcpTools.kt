package ai.rever.boss.plugin.dynamic.clipboard

import ai.rever.boss.plugin.api.ClipboardProvider
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * MCP tools contributed by the Clipboard History plugin.
 *
 * Registered through [ClipboardDynamicPlugin.register]. Every tool is
 * gated on a non-null [ClipboardStore] (the panel and the MCP tools
 * share one), so a panel that has not yet loaded returns the same
 * unavailable result the user sees on screen.
 *
 * Read tools return the MASKED text - reveal is its own verb, and is
 * the only path that surfaces an entry's raw content. Restore goes
 * through the host's [ClipboardProvider.setText] so it lands on the
 * system clipboard alongside any other copy the user makes.
 *
 * All mutating tools (pin / unpin / delete / clear / pause / resume /
 * restore) are declared readOnly = false so the host's MCP approval
 * dialog presents them as side-effecting, in line with the governed
 * MCP rule that mutating verbs must NOT default to ALLOW.
 */
internal class ClipboardMcpToolProvider(
    override val providerId: String,
    private val store: ClipboardStore,
    private val clipboardProvider: ClipboardProvider?,
    private val isPaused: () -> Boolean,
    private val setPaused: (Boolean) -> Unit,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "clipboard_history_list",
            description = "List recent clipboard history entries, newest first. " +
                "Optional substring filter narrows by masked text.",
            inputSchema = LIST_SCHEMA,
            handler = McpToolHandler { args -> listEntries(args.string("query")) },
        ),
        McpToolDefinition(
            name = "clipboard_history_get",
            description = "Fetch one clipboard entry by id. Returns the masked text.",
            inputSchema = ID_SCHEMA,
            handler = McpToolHandler { args -> getEntry(args.string("id")) },
        ),
        McpToolDefinition(
            name = "clipboard_history_search",
            description = "Full-text case-insensitive substring search across all stored " +
                "clipboard entries (up to 200 hits), newest first.",
            inputSchema = QUERY_SCHEMA,
            handler = McpToolHandler { args -> search(args.string("query")) },
        ),
        McpToolDefinition(
            name = "clipboard_history_pin",
            description = "Pin an entry so it survives clear-history and ring-buffer eviction.",
            inputSchema = ID_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args -> pinOp(args) { id -> store.pin(id) } },
        ),
        McpToolDefinition(
            name = "clipboard_history_unpin",
            description = "Remove the pin from an entry.",
            inputSchema = ID_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args -> pinOp(args) { id -> store.unpin(id) } },
        ),
        McpToolDefinition(
            name = "clipboard_history_delete",
            description = "Delete a single entry by id. Pinned entries can still be deleted.",
            inputSchema = ID_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args -> deleteOp(args) },
        ),
        McpToolDefinition(
            name = "clipboard_history_clear",
            description = "Clear all UNPINNED entries. Pinned entries are preserved.",
            readOnly = false,
            handler = McpToolHandler { clearOp() },
        ),
        McpToolDefinition(
            name = "clipboard_history_pause",
            description = "Pause clipboard capture. Future copies are not recorded until resume.",
            readOnly = false,
            handler = McpToolHandler { pauseOp(true) },
        ),
        McpToolDefinition(
            name = "clipboard_history_resume",
            description = "Resume clipboard capture after a pause.",
            readOnly = false,
            handler = McpToolHandler { pauseOp(false) },
        ),
        McpToolDefinition(
            name = "clipboard_history_restore",
            description = "Copy the entry's text back to the system clipboard. Refuses masked entries.",
            inputSchema = ID_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args -> restoreOp(args.string("id")) },
        ),
        McpToolDefinition(
            name = "clipboard_history_reveal",
            description = "Return the original (unmasked) text of an entry. The call is recorded in " +
                "the host MCP ledger. Masked entries return the literal placeholder.",
            inputSchema = ID_SCHEMA,
            handler = McpToolHandler { args -> revealOp(args.string("id")) },
        ),
    )

    private suspend fun listEntries(query: String?): McpToolResult {
        val all = store.entries()
        val filtered = if (query.isNullOrBlank()) all
        else all.filter { it.maskedText.contains(query, ignoreCase = true) }
        return McpToolResult(formatEntries(filtered, includeReveal = false))
    }

    private suspend fun getEntry(id: String?): McpToolResult {
        val i = id ?: return McpToolResult("Missing required argument: id", isError = true)
        val entry = store.get(i) ?: return McpToolResult("Unknown id: $i", isError = true)
        return McpToolResult(formatEntry(entry, includeReveal = false))
    }

    private suspend fun search(query: String?): McpToolResult {
        val q = query ?: return McpToolResult("Missing required argument: query", isError = true)
        if (q.isBlank()) return McpToolResult("Query must not be blank", isError = true)
        val hits = store.entries().filter { it.maskedText.contains(q, ignoreCase = true) }.take(200)
        return McpToolResult(formatEntries(hits, includeReveal = false))
    }

    private suspend fun pinOp(args: McpToolArgs, op: suspend (String) -> Unit): McpToolResult {
        val id = args.string("id") ?: return McpToolResult("Missing required argument: id", isError = true)
        op(id)
        return McpToolResult("OK")
    }

    private suspend fun deleteOp(args: McpToolArgs): McpToolResult {
        val id = args.string("id") ?: return McpToolResult("Missing required argument: id", isError = true)
        store.delete(id)
        return McpToolResult("OK")
    }

    private suspend fun clearOp(): McpToolResult {
        store.clearUnpinned()
        return McpToolResult("OK")
    }

    private suspend fun pauseOp(value: Boolean): McpToolResult {
        setPaused(value)
        return McpToolResult(if (value) "Capture paused" else "Capture resumed")
    }

    private suspend fun restoreOp(id: String?): McpToolResult {
        val i = id ?: return McpToolResult("Missing required argument: id", isError = true)
        val provider = clipboardProvider ?: return McpToolResult("Clipboard unavailable", isError = true)
        val entry = store.get(i) ?: return McpToolResult("Unknown id: $i", isError = true)
        if (entry.maskedText == SecretMask.MASKED_PLACEHOLDER) {
            return McpToolResult("Masked entries cannot be restored", isError = true)
        }
        val ok = provider.setText(entry.text)
        return if (ok) McpToolResult("OK")
        else McpToolResult("Clipboard write refused", isError = true)
    }

    private suspend fun revealOp(id: String?): McpToolResult {
        val i = id ?: return McpToolResult("Missing required argument: id", isError = true)
        val entry = store.get(i) ?: return McpToolResult("Unknown id: $i", isError = true)
        return McpToolResult(entry.text)
    }

    private fun formatEntries(list: List<ClipboardEntry>, includeReveal: Boolean): String {
        if (list.isEmpty()) return "(no entries)"
        return list.joinToString("\n") { formatEntry(it, includeReveal) }
    }

    private fun formatEntry(e: ClipboardEntry, includeReveal: Boolean): String {
        val sb = StringBuilder()
        sb.append("id=").append(e.id)
        sb.append(" pinned=").append(if (e.pinned) "true" else "false")
        sb.append(" created=").append(formatIso(e.createdAt))
        sb.append(" text=")
        appendQuoted(sb, e.maskedText)
        return sb.toString()
    }

    private fun formatIso(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(epochMs))
    }

    private fun appendQuoted(sb: StringBuilder, value: String) {
        sb.append('"')
        val oneLine = value.replace("\n", " ").replace("\r", " ")
        if (oneLine.length <= 200) {
            sb.append(oneLine)
        } else {
            sb.append(oneLine.substring(0, 200)).append("...")
        }
        sb.append('"')
    }

    private companion object {
        const val ID_SCHEMA =
            """{"type":"object","properties":{"id":{"type":"string","description":"Clipboard entry id."}},"required":["id"]}"""
        const val QUERY_SCHEMA =
            """{"type":"object","properties":{"query":{"type":"string","description":"Case-insensitive substring."}},"required":["query"]}"""
        const val LIST_SCHEMA =
            """{"type":"object","properties":{"query":{"type":"string","description":"Optional case-insensitive substring filter."}}}"""
    }
}
