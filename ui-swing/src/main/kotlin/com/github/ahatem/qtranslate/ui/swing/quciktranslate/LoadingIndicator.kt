package com.github.ahatem.qtranslate.ui.swing.quciktranslate

import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.Dimension
import java.awt.Frame
import java.awt.MouseInfo
import javax.swing.*

data class LoadingIndicatorState(
    val isVisible: Boolean
) : UiState

/**
 * The small progress marker that follows the pointer while a popup translation is being fetched.
 *
 * Owned by Swing's shared hidden frame rather than by the main window. It exists precisely for the
 * case where the main window is hidden in the tray, and an owned window is suppressed by the
 * platform while its owner is hidden — so owning it by the main window meant it never appeared at
 * the only time it was wanted.
 */
class LoadingIndicator(owner: Frame) : JWindow(), Renderable<LoadingIndicatorState> {

    private val positionUpdater = Timer(10) {
        val mouseLocation = MouseInfo.getPointerInfo().location
        setLocation(mouseLocation.x, mouseLocation.y + 20)
    }

    init {
        isAlwaysOnTop = true
        focusableWindowState = false
        type = Type.UTILITY

        val progressBar = JProgressBar().apply {
            isIndeterminate = true
            preferredSize = Dimension(45, 10)
            border = BorderFactory.createMatteBorder(2, 2, 2, 2, UIManager.getColor("Button.borderColor").darker())
            putClientProperty("JProgressBar.square", true)
        }
        contentPane.add(progressBar)
        pack()
    }

    /** When the marker went up, so a fast result cannot take it down before it was ever seen. */
    private var shownAt = 0L

    private var pendingHide: Timer? = null

    override fun render(state: LoadingIndicatorState) {
        if (state.isVisible) {
            pendingHide?.stop()
            pendingHide = null
            if (!isVisible) {
                // Positioned before showing, or the first frame lands wherever the window was
                // last left — usually the top-left corner of the screen.
                MouseInfo.getPointerInfo()?.location?.let { setLocation(it.x, it.y + 20) }
                shownAt = System.currentTimeMillis()
                isVisible = true
                positionUpdater.start()
            }
            return
        }

        if (!isVisible) return

        // A translation that returns in 80ms would otherwise show and hide the marker inside a
        // single frame — the user sees a flicker, or nothing at all, and reports that the
        // indicator "doesn't work". Held for a moment so that appearing means something.
        val shownFor = System.currentTimeMillis() - shownAt
        if (shownFor >= MINIMUM_VISIBLE_MS) {
            hideNow()
            return
        }
        if (pendingHide != null) return
        pendingHide = Timer((MINIMUM_VISIBLE_MS - shownFor).toInt()) {
            (it.source as Timer).stop()
            pendingHide = null
            hideNow()
        }.apply { isRepeats = false; start() }
    }

    private fun hideNow() {
        isVisible = false
        positionUpdater.stop()
    }

    private companion object {
        /** Long enough to register as "something is happening" rather than as a glitch. */
        const val MINIMUM_VISIBLE_MS = 350L
    }
}