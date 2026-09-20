package ai.rever.boss.plugin.dynamic.clipboard

import ai.rever.boss.plugin.api.ClipboardProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background clipboard watcher.
 *
 * The host's [ClipboardProvider] is the only legal way for a plugin to
 * see what the user copied: AWT's [java.awt.Toolkit] is not on a
 * plugin's classloader, and clipboard listeners are not exposed through
 * the api. The provider is read-only from the plugin's side - we read
 * whatever is on the clipboard now, compare to last time, and store any
 * new text we see.
 *
 * Why polling and not a hook. There is no host-side hook for "clipboard
 * changed" today, and what we do have is a [ClipboardProvider.readText]
 * that runs in the host's own classloader. One read per second catches
 * the common case (Cmd+C elsewhere, focus back to the host panel) and
 * is cheap enough that it does not show in a profiler. If a copy
 * happens between two polls it is caught on the next one; if a copy
 * happens DURING the host closing the panel that is a single missed
 * entry on the buffer, not data loss.
 *
 * The poller skips empty reads (the clipboard is sometimes empty in
 * the gap between copies), skips duplicates of the last text it stored
 * (Cmd+C twice, focus back to the host panel, no second entry), and
 * defers all secret-pattern matching to [SecretMask] before any text
 * reaches [ClipboardStore.append]. The provider returning null is
 * treated as "clipboard not available" - the [pause] path stops us
 * without the panel drawing the "host clipboard unavailable" state.
 */
class ClipboardPoller(
    private val clipboard: ClipboardProvider,
    private val store: ClipboardStore,
    private val isPaused: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /**
     * The text we last saw AND last stored. A copy that matches this is
     * not stored twice. Null until the first poll lands.
     */
    private var lastStored: String? = null

    /**
     * True while the loop should exit (provider null on first read, or
     * the panel has been disposed). The view model flips this on
     * dispose and on Pause and on a null-provider signal.
     */
    @Volatile
    private var stopped: Boolean = false

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive && !stopped) {
                if (!isPaused()) {
                    tick()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        job = null
    }

    private suspend fun tick() {
        val current = try {
            clipboard.readText()
        } catch (t: Throwable) {
            // The host's provider can throw on transient failures (e.g.
            // X11 selection ownership races). Treat one failure like an
            // empty clipboard; the next tick will retry.
            null
        }
        if (current == null || current.isEmpty()) return
        if (current == lastStored) return
        lastStored = current
        if (SecretMask.isSecret(current)) {
            // Store the placeholder so the panel shows it was captured
            // and the user can pin / delete it; nothing of the original
            // is retained.
            store.append(SecretMask.MASKED_PLACEHOLDER)
        } else {
            store.append(current)
        }
    }

    companion object {
        /**
         * How often the loop reads the host clipboard. One second catches
         * every copy a user makes by hand without showing up in the
         * profiler.
         */
        const val POLL_INTERVAL_MS: Long = 1000L
    }
}
