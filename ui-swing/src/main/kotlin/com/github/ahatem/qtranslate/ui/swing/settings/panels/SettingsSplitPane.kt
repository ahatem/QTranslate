package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.ui.swing.main.layout.BorderSplitPaneUI
import com.github.ahatem.qtranslate.ui.swing.main.layout.MirroredSplitPane
import com.github.ahatem.qtranslate.ui.swing.main.layout.UISpacing
import java.awt.Component
import javax.swing.JSplitPane

/**
 * A [MirroredSplitPane] that keeps the workspace's border seam across look-and-feel updates.
 *
 * [MirroredSplitPane] installs [BorderSplitPaneUI] once, in its constructor. That is enough for the
 * workspace, which is built after the theme is chosen — but a settings page is re-dressed every time
 * it is shown: [SettingsDialog][com.github.ahatem.qtranslate.ui.swing.settings.SettingsDialog] calls
 * `updateComponentTreeUI` on the panel it is mounting, and that reinstalls the look-and-feel's own
 * UI. The seam then silently reverted to FlatLaf's grip dots, which is why the same component read
 * as a border in the dialog and as something else on the Plugins page.
 *
 * Reinstalling it here means the seam survives the re-dress, a theme change, and anything else that
 * asks the component to update its UI.
 */
internal class SettingsSplitPane(
    orientation: Int,
    leading: Component,
    trailing: Component,
) : MirroredSplitPane(orientation, true, leading, trailing) {

    init {
        installSeam()
    }

    override fun updateUI() {
        super.updateUI()
        // The look and feel has just replaced our UI; put the seam back.
        installSeam()
    }

    private fun installSeam() {
        dividerSize = UISpacing.DIVIDER_SIZE
        setUI(BorderSplitPaneUI())
        revalidate()
    }

    companion object {
        fun horizontal(leading: Component, trailing: Component): SettingsSplitPane =
            SettingsSplitPane(JSplitPane.HORIZONTAL_SPLIT, leading, trailing)
    }
}
