package com.github.ahatem.qtranslate.ui.swing.shared

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * That no cell renderer derives a font from its own current font.
 *
 * A cell renderer is one component reused for every row. `font = font.deriveFont(size - 1f)` reads
 * the size it set last time and shrinks it again on every paint, so the text creeps smaller until
 * it disappears — and because rows share the component, one shrinking row drags every row painted
 * after it down with it.
 *
 * It is a slow, silent failure: nothing throws, nothing logs, and at first glance the label merely
 * looks a little small. It has been introduced twice in this codebase — once in the translation
 * editor's row renderer, once in the settings sidebar — and diagnosed both times only from a
 * screenshot of text that had vanished.
 *
 * The fix in both cases was to derive once from a stable base, so that is what this enforces.
 */
class FontDerivationInRendererTest {

    @Test
    fun `no renderer shrinks its own font`() {
        val offenders = rendererFiles().flatMap { file -> offendersIn(file) }

        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("A renderer is reused for every row, so deriving from its own font compounds")
                    appendLine("until the text disappears. Derive once from a stable base instead:")
                    offenders.forEach { appendLine("  $it") }
                }
            )
        }
    }

    @Test
    fun `the scan looks only inside renderer callbacks`() {
        // One-shot derivation in a builder is fine: it runs once, on a component that is not
        // reused, and flagging it would make this test noise that people learn to ignore.
        val oneShotIsAllowed = rendererFiles()
            .any { f -> f.readText().contains("deriveFont") && offendersIn(f).isEmpty() }
        assertTrue(oneShotIsAllowed, "Expected at least one safe, one-shot deriveFont to be ignored")
    }

    /**
     * Lines inside a `get*CellRendererComponent` body that derive a font from the current one.
     *
     * Scoped by brace depth from the callback's own opening brace, because only that body reruns
     * per row against the same component. Everything outside it runs once.
     */
    private fun offendersIn(file: File): List<String> {
        val lines = file.readLines()
        val found = mutableListOf<String>()
        var depth = 0
        var inCallback = false

        lines.forEachIndexed { index, line ->
            if (!inCallback && CALLBACK.containsMatchIn(line)) {
                inCallback = true
                depth = 0
            }
            if (inCallback) {
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (SELF_DERIVED_SIZE.containsMatchIn(line)) {
                    found += "${file.name}:${index + 1}  ${line.trim()}"
                }
                if (depth <= 0 && line.contains("}")) inCallback = false
            }
        }
        return found
    }

    private fun rendererFiles(): List<File> =
        File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { "Renderer" in it || "RendererComponent" in it } }
            .toList()

    private companion object {
        /** The per-row callbacks, the only place a renderer's own font compounds. */
        val CALLBACK = Regex("""fun get\w*CellRendererComponent""")

        /**
         * `font = font.deriveFont(...)` where the arguments touch `font.size`.
         *
         * Changing only the style, `font.deriveFont(Font.PLAIN)`, is safe and stays allowed since
         * it cannot compound. It is the size arithmetic that does.
         */
        val SELF_DERIVED_SIZE = Regex("""font\s*=\s*font\.deriveFont\([^)]*font\.size""")
    }
}
