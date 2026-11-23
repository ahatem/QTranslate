package com.github.ahatem.qtranslate.core.localization

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the localization files against silent breakage: a malformed line loads without error and
 * only the affected labels render as raw keys.
 */
class LocalizationFilesTest {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "languages").isDirectory && File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate the repository root from ${System.getProperty("user.dir")}")

    private val embedded = File(repoRoot, "core/src/main/resources/localization/embedded_en.toml")
    private val languageFiles = File(repoRoot, "languages").listFiles { f -> f.extension == "toml" }!!.sorted()
    private val localizationFiles = languageFiles + embedded

    /**
     * A closing quote followed immediately by something that looks like another key.
     *
     * This is what a missing newline looks like after the fact, and every key after the first on
     * such a line is lost: the parser takes the first value and discards the rest of the line.
     */
    private val runTogetherKeys = Regex("\"[A-Za-z_][A-Za-z0-9_]*\\s*=")

    @Test
    fun `no localization file packs several keys onto one line`() {
        val offenders = localizationFiles.flatMap { file ->
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

    /** The parser accepts quoted strings on one line only. Newlines must be escaped as `\n`. */
    @Test
    fun `no localization value spans physical lines`() {
        val offenders = localizationFiles.flatMap { file ->
            file.readLines().withIndex().mapNotNull { (index, raw) ->
                val line = raw.trim()
                if (!KEY_LINE.containsMatchIn(line)) return@mapNotNull null

                val value = line.substringAfter('=').trim()
                if (value.startsWith("\"\"\"") || value.startsWith('"') && !hasClosingQuote(value)) {
                    "${file.name}:${index + 1}: ${raw.take(80)}"
                } else null
            }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "Localization values must fit on one physical line because LanguageTomlParser " +
                    "reads one line at a time. Use escaped \\n sequences instead:\n" +
                    offenders.joinToString("\n")
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
            "layout", "popups", "ocr", "hotkeys", "plugins", "network",
            "group_translation", "group_interface"
        ).map { "settings_dialog_sidebar.$it" }

        val layouts = listOf("layout_preset_classic", "layout_preset_side_by_side", "layout_preset_comparison")
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

    /**
     * No `@reference` points to a key its own file does not define.
     *
     * References are file-local: the parser resolves each file against itself, so a locale
     * holding `cut = "@common.cut"` without a `common.cut` of its own renders the literal text
     * `@common.cut`, no matter what English defines. Ten such references shipped this way.
     */
    @Test
    fun `no localization reference points to a key missing from its own file`() {
        val problems = localizationFiles.flatMap { file ->
            // `[meta]` is parsed into a structure rather than the entries map, so a reference
            // to it could never resolve at runtime either.
            val keys = keysOf(file).filterNot { it.substringBefore('.') == "meta" }.toSet()
            rawReferencesOf(file).mapNotNull { (key, target) ->
                if (target in keys) null
                else "${file.name}: $key points to @$target, which this file does not define"
            }
        }

        if (problems.isNotEmpty()) {
            fail(
                "These references resolve to nothing and render as literal @text. " +
                    "Either define the target in the same file or drop the referring key " +
                    "so it falls back to English:\n" + problems.joinToString("\n") { "  $it" }
            )
        }
    }

    /** Flattens a TOML localization file to `section.key` → value. */
    private fun valuesOf(file: File): Map<String, String> = LanguageTomlParser().parse(file.readText()).entries

    private fun hasClosingQuote(value: String): Boolean {
        var escaped = false
        for (character in value.drop(1)) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> return true
            }
        }
        return false
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

    /**
     * Raw `@section.key` values per key, before the parser resolves them.
     *
     * Read straight from the lines, the way `LanguageTomlParser` sees them: quoted value whose
     * text starts with `@`. A value the parser already resolved can no longer say where it came
     * from, which is why this cannot reuse [valuesOf].
     */
    private fun rawReferencesOf(file: File): Map<String, String> {
        var section = ""
        val refs = mutableMapOf<String, String>()
        file.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#") || line.isEmpty() -> return@forEach
                line.startsWith("[") -> section = line.trim('[', ']').trim()
                else -> KEY_LINE.find(line)?.let {
                    val value = line.substringAfter('=').trim().removeSurrounding("\"")
                    if (value.startsWith("@")) {
                        refs["$section.${it.groupValues[1]}"] = value.removePrefix("@")
                    }
                }
            }
        }
        return refs
    }

    private companion object {
        val LITERAL_KEY = Regex("""getString\("([a-zA-Z0-9_]+\.[a-zA-Z0-9_]+)"""")

        val KEY_LINE = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=""")

        /** Covers `%s`, `%d`, and the positional `%1$s` form translators use to reorder. */
        val FORMAT_SPECIFIER = Regex("""%\d*\$?[sdf]""")
    }
}
