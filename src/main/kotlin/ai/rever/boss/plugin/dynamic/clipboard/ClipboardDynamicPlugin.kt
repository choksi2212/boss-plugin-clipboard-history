package ai.rever.boss.plugin.dynamic.clipboard

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageFactory

/**
 * Clipboard History dynamic plugin entry point.
 *
 * Registers one panel and one MCP-tool provider. The panel uses the
 * host's [ai.rever.boss.plugin.api.ClipboardProvider] (read once per
 * second from a supervisor scope) and the host's
 * [PluginStorageFactory] for persistence. The MCP provider exposes
 * the same store as the panel; both share the same paused-state
 * [Boolean] flag, so toggling pause from one place is reflected in
 * the other without re-reading anything.
 *
 * Disposal nulls the references the panel and MCP provider held so
 * the host's unload cycle does not leave a background loop ticking
 * against a clipboard it can no longer reach.
 */
class ClipboardDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.clipboard"
    override val displayName: String = "Clipboard History"
    override val version: String = "0.1.0"
    override val description: String =
        "Persistent clipboard ring buffer - the last 200 copied texts are searchable, " +
            "pinnable, and one click away from being re-copied. Secrets are masked by default."
    override val author: String = "choksi2212"
    override val url: String = "https://github.com/choksi2212/boss-plugin-clipboard-history"

    private var store: ClipboardStore? = null
    private var storageFactory: PluginStorageFactory? = null
    private var clipboardProvider: ai.rever.boss.plugin.api.ClipboardProvider? = null
    private var pausedRef: java.util.concurrent.atomic.AtomicBoolean? = null

    override fun register(context: PluginContext) {
        val factory = context.pluginStorageFactory
        storageFactory = factory
        val storage = factory?.createStorage(pluginId)
        val localStore: ClipboardStore? = if (storage != null) ClipboardStore(storage) else null
        store = localStore
        clipboardProvider = context.clipboardProvider
        val paused = java.util.concurrent.atomic.AtomicBoolean(false)
        pausedRef = paused

        if (localStore != null) {
            context.panelRegistry.registerPanel(ClipboardInfo) { ctx, panelInfo ->
                ClipboardComponent(
                    ctx = ctx,
                    panelInfo = panelInfo,
                    clipboardProvider = clipboardProvider,
                    storageFactory = storageFactory,
                    pluginScope = context.pluginScope,
                )
            }
            context.registerMcpToolProvider(
                ClipboardMcpToolProvider(
                    providerId = pluginId,
                    store = localStore,
                    clipboardProvider = clipboardProvider,
                    isPaused = { paused.get() },
                    setPaused = { value ->
                        paused.set(value)
                    },
                ),
            )
        }
    }

    override fun dispose() {
        store = null
        storageFactory = null
        clipboardProvider = null
        pausedRef = null
    }
}
