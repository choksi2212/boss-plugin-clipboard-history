package ai.rever.boss.plugin.dynamic.clipboard

import ai.rever.boss.plugin.api.ClipboardProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginStorageFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope

/**
 * Clipboard History panel component.
 *
 * Hosts the [ClipboardViewModel] for the lifetime of the panel instance.
 * [DisposableEffect] calls [ClipboardViewModel.dispose] when the panel
 * leaves composition so the poller stops and the supervisor scope is
 * cancelled - a panel swap should not leave a background loop ticking
 * against the host clipboard.
 *
 * The [ClipboardProvider] and [PluginStorageFactory] are passed in by
 * the [ClipboardDynamicPlugin] rather than reached through a context
 * here, because the api does not give a component a direct handle on
 * either: the provider and factory are accessed on the plugin's
 * [ai.rever.boss.plugin.api.PluginContext], which lives only for the
 * duration of `register`.
 */
class ClipboardComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val clipboardProvider: ClipboardProvider?,
    private val storageFactory: PluginStorageFactory?,
    private val pluginScope: CoroutineScope,
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        val viewModel = remember {
            val storage = storageFactory?.createStorage("ai.rever.boss.plugin.dynamic.clipboard")
                ?: error("plugin storage factory is not available in this context")
            ClipboardViewModel(
                clipboardProvider = clipboardProvider,
                store = ClipboardStore(storage),
                pluginScope = pluginScope,
            )
        }
        DisposableEffect(viewModel) {
            onDispose { viewModel.dispose() }
        }
        ClipboardContent(viewModel)
    }
}
