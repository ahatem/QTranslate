package com.github.ahatem.qtranslate.ui.swing.main

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the frame's exit/popup/drop wiring to the collaborators it is supposed to use.
 *
 * The extracted policies are tested in isolation; these assertions catch the call site being
 * rewired to the wrong effect (for example disposing the tray where global input was meant) or
 * the ordered teardown losing a step, which no isolated test can see.
 */
class MainAppFrameWiringTest {
    private val source: String =
        File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/main/MainAppFrame.kt")
            .readText()
            .replace("\r\n", "\n")

    @Test
    fun `windowClosed only delegates to the exit sequence`() {
        assertContains(
            source,
            "override fun windowClosed(e: WindowEvent?) {\n                frameExit.exit()\n            }"
        )
    }

    @Test
    fun `exit sequence binds every teardown step in order`() {
        assertContains(source, "private val frameExit = FrameExitSequence(")
        assertContains(source, "shutdownGlobalInput = { globalKeyListener.shutdown() },")
        assertContains(source, "disposeSelectionButton = { selectionTranslateButton.dispose() },")
        assertContains(source, "disposeTray = { trayBinding.dispose() },")
        assertContains(source, "cancelFrameScope = { appScope.cancel() },")
        assertContains(source, "captureBounds = { Size(width, height) to Position(x, y) },")
        assertContains(source, "onApplicationExit = onApplicationExit,")
        val block = source
            .substringAfter("private val frameExit = FrameExitSequence(")
            .substringBefore("\n    )")
        val order = listOf(
            "shutdownGlobalInput",
            "disposeSelectionButton",
            "disposeTray",
            "cancelFrameScope",
            "captureBounds",
            "onApplicationExit",
        )
        val positions = order.map { block.indexOf(it) }
        assertTrue(positions.all { it >= 0 }, "every teardown step is wired: $positions")
        assertEquals(positions.sorted(), positions, "teardown steps keep their order")
    }

    @Test
    fun `popups and drops route through their policies`() {
        assertContains(source, "PopupDismissalPolicy.dismissals(")
        assertContains(source, "contentDropRouter.route(content)")
    }

    @Test
    fun `main state rendering stays on the Swing observer`() {
        // The behavior is covered by MainStateRenderObserverTest; this pins the production call
        // site so the frame cannot quietly go back to collecting render state on a background
        // dispatcher.
        assertContains(source, "MainStateRenderObserver(")
        assertContains(source, ".start(handler, ::renderMainState)")
    }
}
