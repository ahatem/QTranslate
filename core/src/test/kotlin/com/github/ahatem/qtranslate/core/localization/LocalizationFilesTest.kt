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

    /**
     * en-GB carries the same keys as the fallback, no more and no less.
     *
     * Both are English, so a key present in one and absent from the other is drift rather than a
     * translation gap — which is what it had become: en-GB was missing 67 keys and carried three
     * of its own that nothing referenced. Values are deliberately not compared; en-GB exists to
     * differ, in British spellings such as "Minimise" and "Summariser".
     *
     * The other twelve files are exempt on purpose. Falling behind is normal for them, since a
     * missing key falls back to English and an untranslated string is better than none.
     */
    @Test
    fun `en-GB carries exactly the keys the fallback does`() {
        val embeddedKeys = keysOf(embedded)
        val enGb = languageFiles.single { it.name == "en-GB.toml" }
        val enGbKeys = keysOf(enGb)

        val missing = embeddedKeys - enGbKeys
        val extra = enGbKeys - embeddedKeys

        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("en-GB.toml has drifted from embedded_en.toml.")
                    if (missing.isNotEmpty()) {
                        appendLine("Missing from en-GB (${missing.size}):")
                        missing.sorted().forEach { appendLine("  $it") }
                    }
                    if (extra.isNotEmpty()) {
                        appendLine("Present in en-GB but not the fallback (${extra.size}):")
                        extra.sorted().forEach { appendLine("  $it") }
                    }
                }
            )
        }
    }

    /**
     * Nothing in the fallback is unreachable from the code.
     *
     * The mirror of the check above, and the one that was missing: keys were verified to exist for
     * the code, never that the code still wants them. So a string outlived the feature that asked
     * for it and stayed, was translated into fourteen languages by people who had no way to know
     * it was dead, and cost real volunteer effort every time a new language was added.
     *
     * Deliberately scanned with [ANY_KEY_LITERAL] rather than the narrower [LITERAL_KEY] the check
     * above uses. Plenty of keys never appear inside a `getString(...)` call: `PluginsPanel` maps
     * a category to a key name and resolves it later, `NetworkPanel` passes one through a variable.
     * Matching any key-shaped literal errs towards keeping a string, which is the safe direction
     * for a test whose failure message says "delete this".
     *
     * [RUNTIME_KEY_SECTIONS] covers what no scan can see: sections whose keys are interpolated,
     * and `[meta]`, which is parsed into a structure instead of being looked up by name.
     */
    @Test
    fun `every key in the embedded fallback is asked for by the code`() {
        // :api and the plugins are in scope as well as the application modules. StandardOptions
        // names host keys in DisplayText, so a scan of the application alone reports the summary
        // and rewrite labels as dead while they are on screen.
        val sourceRoots = sequenceOf("api", "core", "ui-swing", "app")
            .map { File(repoRoot, "$it/src/main/kotlin") } +
            (File(repoRoot, "plugins").listFiles().orEmpty().asSequence()
                .map { File(it, "src/main/kotlin") })

        val referenced = sourceRoots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" } }
            .flatMap { file -> ANY_KEY_LITERAL.findAll(file.readText()).map { it.groupValues[1] } }
            .toSortedSet()

        assertTrue(referenced.size > 100, "Found only ${referenced.size} keys; the scan is probably broken")

        val orphans = keysOf(embedded)
            .filterNot { key -> key.substringBefore('.') in RUNTIME_KEY_SECTIONS }
            .filterNot { it in referenced }

        if (orphans.isNotEmpty()) {
            fail(
                "These strings are in embedded_en.toml but nothing asks for them. Delete them here " +
                    "and from every file in languages/, or add the section to RUNTIME_KEY_SECTIONS " +
                    "if the key is assembled at runtime:\n" + orphans.sorted().joinToString("\n") { "  $it" }
            )
        }
    }

    /**
     * A translated string takes the same number of arguments as the English it replaces.
     *
     * `getString` passes its arguments to `String.format`, which throws when a specifier has no
     * argument behind it. A translator who drops a `%s` turns a label into a crash, and only in
     * their language, which is the hardest kind of bug to notice.
     *
     * Counted rather than compared in order, because reordering is legitimate: zh-CN writes
     * `%2$s %1$s` where English has `%s %s`, since the clauses fall the other way round.
     */
    @Test
    fun `translations take the same arguments as the English they replace`() {
        val english = valuesOf(embedded)

        val problems = languageFiles.flatMap { file ->
            valuesOf(file).mapNotNull { (key, translated) ->
                val source = english[key] ?: return@mapNotNull null
                val expected = FORMAT_SPECIFIER.findAll(source).count()
                val actual = FORMAT_SPECIFIER.findAll(translated).count()
                if (expected == actual) null
                else "${file.name}: $key takes $actual argument(s), English takes $expected"
            }
        }

        if (problems.isNotEmpty()) {
            fail(
                "These translations would throw when formatted:\n" + problems.joinToString("\n") { "  $it" }
            )
        }
    }

    /** Flattens a TOML localization file to `section.key` → value. */
    private fun valuesOf(file: File): Map<String, String> {
        var section = ""
        val values = mutableMapOf<String, String>()
        file.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#") || line.isEmpty() -> return@forEach
                line.startsWith("[") -> section = line.trim('[', ']').trim()
                else -> VALUE_LINE.find(line)?.let { values["$section.${it.groupValues[1]}"] = it.groupValues[2] }
            }
        }
        return values
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
        /**
         * Sections whose keys are built by interpolation, so a literal scan cannot see them.
         *
         * Each one has a `getString("section.$key")` behind it: the editor context menus, the
         * layout preset names, the language editor, and the settings sidebar. Keep this list as
         * short as it can be — every section on it is a section where a dead string can hide.
         */
        val RUNTIME_KEY_SECTIONS = setOf(
            "main_window_editor_context_menu",
            "main_window_main_menu",
            "language_editor",
            "settings_dialog_sidebar",
            // Not looked up by name at all: LanguageTomlParser reads this section into
            // LocalizedLanguageMeta, and its keys are too generic for any scan to judge.
            "meta",
        )

        val LITERAL_KEY = Regex("""getString\("([a-zA-Z0-9_]+\.[a-zA-Z0-9_]+)"""")

        /** Any key-shaped string literal, however it is later used. */
        val ANY_KEY_LITERAL = Regex(""""([a-zA-Z0-9_]+\.[a-zA-Z0-9_]+)"""")
        val KEY_LINE = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=""")
        val VALUE_LINE = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$""")

        /** Covers `%s`, `%d`, and the positional `%1$s` form translators use to reorder. */
        val FORMAT_SPECIFIER = Regex("""%\d*\$?[sdf]""")
    }
}
