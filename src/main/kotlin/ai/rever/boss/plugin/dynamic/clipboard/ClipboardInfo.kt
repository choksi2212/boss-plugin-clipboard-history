package ai.rever.boss.plugin.dynamic.clipboard

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard

/**
 * Clipboard History panel descriptor.
 *
 * Lives in the left sidebar's bottom slot and orders itself at priority
 * 84 so it sits below the search index but above the long tail of user
 * plugins - high enough to be reached without scrolling, low enough that
 * it does not crowd the editor surface.
 */
object ClipboardInfo : PanelInfo {
    override val id = PanelId("clipboard-history", 84)
    override val displayName = "Clipboard History"
    override val icon = FeatherIcons.Clipboard
    override val defaultSlotPosition = left.bottom
}
