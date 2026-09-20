package com.github.ahatem.qtranslate.ui.swing.main.input

/**
 * Deterministic [RobotKeyDriver] test double: records every call and can be scripted to fail on
 * a specific one, so Robot's creation-failure and mid-injection-failure paths can be pinned
 * without ever touching a real [java.awt.Robot] (which would physically move the keyboard).
 */
internal class FakeRobotKeyDriver(
    private val failOn: ((String) -> Boolean)? = null,
) : RobotKeyDriver {

    val calls = mutableListOf<String>()

    override fun keyPress(keyCode: Int) = record("press:$keyCode")
    override fun keyRelease(keyCode: Int) = record("release:$keyCode")
    override fun waitForIdle() = record("waitForIdle")

    private fun record(op: String) {
        calls += op
        if (failOn?.invoke(op) == true) throw RuntimeException("scripted failure at $op")
    }
}
