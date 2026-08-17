package com.github.ahatem.qtranslate.ui.swing.shared.icon

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * That nobody scales an SVG icon twice.
 *
 * `FlatSVGIcon.getIconWidth` runs the size it was given through `scaleSize`, which applies
 * `UIScale.scale` itself. Passing an already-scaled size therefore applies the factor twice: at
 * 100% the two are identical and nothing looks wrong, at 125% the icon is a quarter too big, and at
 * 150% it is half again too big and gets crammed into a button whose size was scaled only once.
 *
 * That is a bug which cannot be seen on the machine most likely to be writing the code, and the
 * correct call — a bare `14` next to a lot of carefully scaled numbers — reads like an oversight
 * somebody should fix. So it is written down here rather than left to be rediscovered from a
 * screenshot at 150%.
 */
class IconScalingConventionTest {

    @Test
    fun `no icon is constructed with a pre-scaled size`() {
        val offenders = uiSwingSources()
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> DOUBLE_SCALED.containsMatchIn(line) }
                    .map { (index, line) -> "${file.name}:${index + 1}  ${line.trim()}" }
            }

        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("FlatSVGIcon scales its own size; passing UIScale.scale() applies it twice.")
                    appendLine("Pass the unscaled size instead:")
                    offenders.forEach { appendLine("  $it") }
                }
            )
        }
    }

    @Test
    fun `the scan actually reaches the source it is meant to guard`() {
        // Without this, a wrong path makes the test above pass by finding nothing, which is the
        // failure mode every file-scanning test has and the reason so few of them are worth having.
        val sources = uiSwingSources()
        if (sources.size < 20) fail("Only found ${sources.size} source files; the scan is broken")
        if (sources.none { it.name == "SettingsPanel.kt" }) {
            fail("SettingsPanel.kt was not scanned, and it is where this bug was found")
        }
    }

    private fun uiSwingSources(): List<File> =
        File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private companion object {
        /** A `UIScale.scale(...)` anywhere inside a `FlatSVGIcon(...)` construction. */
        val DOUBLE_SCALED = Regex("""FlatSVGIcon\s*\([^)]*UIScale\.scale""")
    }
}
