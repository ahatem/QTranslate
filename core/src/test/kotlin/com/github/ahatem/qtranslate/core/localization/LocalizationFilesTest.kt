package com.github.ahatem.qtranslate.core.localization

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the localization files against the two ways they have silently broken.
 *
 * Both failures found by these checks were shipped: five hotkey labels and the no-service action
 * button rendered their raw key names, because the strings had been appended without a newline
 * and so never parsed. Nothing failed loudly — the app started, the file loaded, and only the
 * affected labels were wrong, which is exactly the kind of defect a test earns its place on.
 */
class LocalizationFilesTest {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "languages").isDirectory && File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate the repository root from ${System.getProperty("user.dir")}")

    private val embedded = File(repoRoot, "core/src/main/resources/localization/embedded_en.toml")
    private val languageFiles = File(repoRoot, "languages").listFiles { f -> f.extension == "toml" }!!.sorted()

    /**
     * A closing quote followed immediately by something that looks like another key.
     *
     * This is what a missing newline looks like after the fact, and every key after the first on
     * such a line is lost: the parser takes the first value and discards the rest of the line.
     */
    private val runTogetherKeys = Regex("\"[A-Za-z_][A-Za-z0-9_]*\\s*=")

    @Test
    fun `no localization file packs several keys onto one line`() {
        val offenders = (languageFiles + embedded).flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> runTogetherKeys.containsMatchIn(line) }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.take(80)}" }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "Some keys share a line with the one before them and will never be parsed.\n" +
                    "Put each key on its own line:\n" + offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun `every key the code asks for exists in the embedded fallback`() {
        val embeddedKeys = keysOf(embedded)

        val referenced = sequenceOf("core", "ui-swing", "app")
            .map { File(repoRoot, "$it/src/main/kotlin") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" } }
            .flatMap { file -> LITERAL_KEY.findAll(file.readText()).map { it.groupValues[1] } }
            .toSortedSet()

        assertTrue(referenced.size > 100, "Found only ${referenced.size} keys; the scan is probably broken")

        val missing = referenced - embeddedKeys
        if (missing.isNotEmpty()) {
            fail(
                "These keys are requested by the code but absent from embedded_en.toml, so they " +
                    "render as their own key text:\n" + missing.joinToString("\n") { "  $it" }
            )
        }
    }

    /**
     * Keys assembled at runtime, which the literal scan above cannot see.
     *
     * Listed by hand because that is the only way to cover them, and kept short: these are the
     * places where a key is built from an enum or a loop, so a new enum constant silently adds a
     * key nobody wrote a string for. That is how five hotkey labels went missing.
     */
    @Test
    fun `keys built at runtime exist in the embedded fallback`() {
        val embeddedKeys = keysOf(embedded)

        val sidebar = listOf(
            "general", "appearance", "services", "behavior", "languages",
            "layout", "popups", "hotkeys", "plugins",
            "group_translation", "group_interface"
        ).map { "settings_dialog_sidebar.$it" }

        val layouts = listOf("layout_preset_classic", "layout_preset_side_by_side", "layout_preset_compact")
            .map { "main_window_main_menu.$it" }

        val missing = (sidebar + layouts).filterNot { it in embeddedKeys }
        if (missing.isNotEmpty()) {
            fail("Runtime-assembled keys missing from embedded_en.toml:\n" + missing.joinToString("\n") { "  $it" })
        }
    }

    /** Flattens a TOML localization file to `section.key` strings. */
    private fun keysOf(file: File): Set<String> {
        var section = ""
        val keys = mutableSetOf<String>()
        file.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#") || line.isEmpty() -> return@forEach
                line.startsWith("[") -> section = line.trim('[', ']').trim()
                else -> KEY_LINE.find(line)?.let { keys += "$section.${it.groupValues[1]}" }
            }
        }
        return keys
    }

    private companion object {
        val LITERAL_KEY = Regex("""getString\("([a-zA-Z0-9_]+\.[a-zA-Z0-9_]+)"""")
        val KEY_LINE = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=""")
    }
}
