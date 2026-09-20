package ai.rever.boss.plugin.dynamic.clipboard

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Persistent clipboard history.
 *
 * Backs the entire ring buffer with the host's per-plugin storage so the
 * last 200 copies survive across restarts. The store is the single
 * authority on what is in the buffer: the [ClipboardPoller] appends to it,
 * the [ClipboardViewModel] reads from it, and the MCP tools read and
 * mutate it. All public methods take a [Mutex] so two callers (panel +
 * agent, or two agent calls at once) cannot race a delete against an
 * append and lose entries.
 *
 * Capacity is bounded. [MAX_ENTRIES] is the hard cap; once it is reached,
 * the next append evicts the OLDEST UNPINNED entry first, and pinned
 * entries only evict after every unpinned one has gone. This matches the
 * user-visible behaviour: pin what matters, accept that anything else is
 * rolling. [MAX_TEXT_BYTES] caps a single entry so a multi-megabyte paste
 * cannot blow the per-key JSON blob - larger text is REFUSED at write
 * time, not truncated, because truncating would silently change what the
 * user thought they had copied.
 *
 * Persistence is a single JSON blob under [STORAGE_KEY]. The list is
 * stored newest-first; [entries] returns it in that order so the panel can
 * render without a sort, and the MCP tools also iterate in that order
 * (matching their semantics: most-recent first).
 *
 * On load, malformed entries are dropped rather than throwing - a corrupt
 * record from a previous version should not take the panel down on every
 * launch. The store does NOT attempt migration beyond that.
 */
class ClipboardStore(
    private val storage: PluginStorageProvider,
) {
    private val mutex = Mutex()

    /**
     * Latest snapshot, newest entry first.
     */
    suspend fun entries(): List<ClipboardEntry> = mutex.withLock { load() }

    /**
     * Append a new entry, evicting the oldest unpinned entry if the cap
     * is reached. The id is minted here so the caller does not need to
     * coordinate one; [text] is assumed to be already-masked (the poller
     * runs it through [SecretMask] before calling) and is what gets
     * stored verbatim. Refuses text larger than [MAX_TEXT_BYTES] and
     * returns null in that case.
     */
    suspend fun append(text: String, sourceLabel: String = "clipboard"): ClipboardEntry? {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return null
        val now = System.currentTimeMillis()
        val entry = ClipboardEntry(
            id = UUID.randomUUID().toString(),
            text = text,
            maskedText = text,
            createdAt = now,
            pinned = false,
            sourceLabel = sourceLabel,
        )
        mutex.withLock {
            val list = load().toMutableList()
            list.add(0, entry)
            trimToCap(list)
            persist(list)
        }
        return entry
    }

    /**
     * Mark an entry pinned. No-op if the id is unknown.
     */
    suspend fun pin(id: String) {
        mutex.withLock {
            val list = load().toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return@withLock
            if (!list[idx].pinned) {
                list[idx] = list[idx].copy(pinned = true)
                persist(list)
            }
        }
    }

    /**
     * Clear the pinned flag. No-op if the id is unknown or already unpinned.
     */
    suspend fun unpin(id: String) {
        mutex.withLock {
            val list = load().toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0 || !list[idx].pinned) return@withLock
            list[idx] = list[idx].copy(pinned = false)
            persist(list)
        }
    }

    /**
     * Delete a single entry by id. Pinned entries can still be deleted -
     * pin survives clear-history, not explicit user choice.
     */
    suspend fun delete(id: String) {
        mutex.withLock {
            val list = load().filterNot { it.id == id }
            persist(list)
        }
    }

    /**
     * Clear all UNPINNED entries. Pinned entries are preserved.
     */
    suspend fun clearUnpinned() {
        mutex.withLock {
            val kept = load().filter { it.pinned }
            persist(kept)
        }
    }

    /**
     * Resolve an id to its entry, or null if the id is unknown.
     */
    suspend fun get(id: String): ClipboardEntry? = mutex.withLock { load().firstOrNull { it.id == id } }

    private suspend fun load(): List<ClipboardEntry> {
        val raw = storage.getJson(STORAGE_KEY) ?: return emptyList()
        return parseList(raw)
    }

    private suspend fun persist(list: List<ClipboardEntry>) {
        storage.putJson(STORAGE_KEY, serializeList(list))
    }

    private fun parseList(raw: String): List<ClipboardEntry> {
        val items = readArray(raw)
        val out = ArrayList<ClipboardEntry>(items.size)
        for (i in items.indices) {
            val obj = items[i] as? JsonObject ?: continue
            val id = obj.string("id") ?: continue
            val text = obj.string("text") ?: continue
            val masked = obj.string("maskedText") ?: text
            val created = obj.long("createdAt") ?: continue
            val pinned = obj.boolean("pinned") ?: false
            val source = obj.string("sourceLabel") ?: "clipboard"
            out.add(ClipboardEntry(id, text, masked, created, pinned, source))
        }
        return out
    }

    private fun serializeList(list: List<ClipboardEntry>): String {
        val sb = StringBuilder(64 + list.size * 64)
        sb.append('[')
        for (i in list.indices) {
            if (i > 0) sb.append(',')
            val e = list[i]
            sb.append('{')
            sb.append("\"id\":")
            appendString(sb, e.id)
            sb.append(",\"text\":")
            appendString(sb, e.text)
            sb.append(",\"maskedText\":")
            appendString(sb, e.maskedText)
            sb.append(",\"createdAt\":")
            sb.append(e.createdAt.toString())
            sb.append(",\"pinned\":")
            sb.append(if (e.pinned) "true" else "false")
            sb.append(",\"sourceLabel\":")
            appendString(sb, e.sourceLabel)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    private fun readArray(raw: String): List<Any?> {
        val p = JsonParser(raw)
        p.skipWs()
        require(p.peek() == '[') { "expected '[' at start of clipboard history blob" }
        p.pos++
        val out = ArrayList<Any?>()
        p.skipWs()
        if (p.peek() == ']') {
            p.pos++
            return out
        }
        while (true) {
            p.skipWs()
            out.add(p.readValue())
            p.skipWs()
            when (p.peek()) {
                ',' -> {
                    p.pos++
                    continue
                }
                ']' -> {
                    p.pos++
                    return out
                }
                else -> error("unexpected character '${p.peek()}' at offset ${p.pos}")
            }
        }
    }

    /**
     * Drop oldest unpinned first; pinned only evict after every unpinned
     * one is gone. Mutates [list] in place.
     */
    private fun trimToCap(list: MutableList<ClipboardEntry>) {
        while (list.size > MAX_ENTRIES) {
            val victim = list.indexOfLast { !it.pinned }
            if (victim < 0) {
                // All pinned and over cap; trim the oldest pinned entry to
                // honour the hard cap rather than refusing to store.
                list.removeAt(list.size - 1)
            } else {
                list.removeAt(victim)
            }
        }
    }

    private class JsonParser(val src: String) {
        var pos: Int = 0

        fun peek(): Char = if (pos < src.length) src[pos] else ' '

        fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWs()
            return when (val c = peek()) {
                '"' -> readString()
                '{' -> readObject()
                '[' -> readArrayValue()
                't', 'f' -> readBool()
                'n' -> readNull()
                '-', in '0'..'9' -> readNumber()
                else -> error("unexpected character '$c' at offset $pos")
            }
        }

        fun readString(): String {
            require(peek() == '"') { "expected '\"' at offset $pos" }
            pos++
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    val esc = src[pos++]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = src.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> error("bad escape '\\$esc' at offset ${pos - 1}")
                    }
                } else {
                    sb.append(c)
                }
            }
            error("unterminated string")
        }

        fun readObject(): JsonObject {
            require(peek() == '{') { "expected '{' at offset $pos" }
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return JsonObject(map)
            }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                require(peek() == ':') { "expected ':' at offset $pos" }
                pos++
                skipWs()
                map[key] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> {
                        pos++
                        continue
                    }
                    '}' -> {
                        pos++
                        return JsonObject(map)
                    }
                    else -> error("unexpected character '${peek()}' at offset $pos")
                }
            }
        }

        fun readArrayValue(): List<Any?> {
            require(peek() == '[') { "expected '[' at offset $pos" }
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                out.add(readValue())
                skipWs()
                when (peek()) {
                    ',' -> {
                        pos++
                        continue
                    }
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> error("unexpected character '${peek()}' at offset $pos")
                }
            }
        }

        fun readBool(): Boolean {
            return when {
                src.startsWith("true", pos) -> {
                    pos += 4
                    true
                }
                src.startsWith("false", pos) -> {
                    pos += 5
                    false
                }
                else -> error("expected boolean at offset $pos")
            }
        }

        fun readNull(): Any? {
            require(src.startsWith("null", pos)) { "expected null at offset $pos" }
            pos += 4
            return null
        }

        fun readNumber(): Long {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E' || src[pos] == '+' || src[pos] == '-')) pos++
            val s = src.substring(start, pos)
            return s.toLongOrNull() ?: s.toDouble().toLong()
        }
    }

    private class JsonObject(val map: Map<String, Any?>) {
        fun string(key: String): String? = (map[key] as? String)
        fun boolean(key: String): Boolean? = (map[key] as? Boolean)
        fun long(key: String): Long? = (map[key] as? Long)
    }

    private fun appendString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    companion object {
        /**
         * Hard cap on stored entries. A new append evicts the oldest
         * UNPINNED entry first; pinned entries only evict after every
         * unpinned one has gone, and only if pinning has filled the cap.
         */
        const val MAX_ENTRIES: Int = 200

        /**
         * Largest single entry the store will accept, in UTF-8 bytes.
         * Larger text is REFUSED at write time - truncating would silently
         * change what the user thought they had copied.
         */
        const val MAX_TEXT_BYTES: Int = 64 * 1024

        private const val STORAGE_KEY: String = "clipboard_history_v1"
    }
}
