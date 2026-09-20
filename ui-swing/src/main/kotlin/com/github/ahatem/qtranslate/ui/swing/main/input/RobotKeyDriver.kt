package com.github.ahatem.qtranslate.ui.swing.main.input

import java.awt.Robot

/** Seam over [Robot] so tests can inject failures without a real device. */
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
