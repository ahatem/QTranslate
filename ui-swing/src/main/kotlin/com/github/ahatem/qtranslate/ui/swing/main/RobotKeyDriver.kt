package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.Robot

/**
 * Thin seam over the [Robot] methods `RobotCopyInjector`/`RobotPasteInjector` use, so their
 * creation-failure and mid-injection-failure paths can be pinned by tests without a real
 * `Robot` — constructing one only to fail deterministically is not possible, and letting a real
 * one succeed would physically inject keystrokes into the developer's desktop.
 */
internal interface RobotKeyDriver {
    fun keyPress(keyCode: Int)
    fun keyRelease(keyCode: Int)
    fun waitForIdle()
}

/** Production driver: a real [Robot], constructed lazily so creation failure surfaces here. */
internal class AwtRobotKeyDriver : RobotKeyDriver {
    private val robot = Robot().apply { autoDelay = 20 }

    override fun keyPress(keyCode: Int) = robot.keyPress(keyCode)
    override fun keyRelease(keyCode: Int) = robot.keyRelease(keyCode)
    override fun waitForIdle() = robot.waitForIdle()
}
