package com.github.ahatem.qtranslate.ui.swing.shared.icon

/**
 * An icon set the application can be dressed in.
 *
 * @param id the folder under `icons/`, and what is stored in the configuration
 * @param displayName shown in Settings, and not translated: these are proper nouns
 */
data class IconSetInfo(val id: String, val displayName: String)

/**
 * Which drawing of each icon the application uses.
 *
 * ### Why names, not paths
 * Every icon is asked for by what it means here — `edit`, `delete`, `ocr` — and this turns that
 * into a path inside whichever set is chosen. A set is a folder of SVGs answering to the same
 * names, so a second one is a folder to drop in rather than a change to any code.
 *
 * ### Falling back rather than failing
 * A set need not be complete. Anything it does not have comes from Lucide, which ships whole, so a
 * half-populated set is usable from its first icon instead of leaving blanks everywhere. Icons load
 * inside a `runCatching` and a missing one is a blank button rather than an error, which is exactly
 * why the gap is filled here rather than left to be noticed.
 *
 * ### When a change takes effect
 * Icons are built once and held by the components showing them, so switching sets applies to what
 * is built afterwards. Settings says so rather than implying the change is immediate.
 */
object IconSet {

    const val DEFAULT_ID = "lucide"

    /**
     * The sets the application knows about. A folder that is absent or empty is simply not offered,
     * so an unpopulated set never appears as a choice that does nothing.
     */
    private val known = listOf(
        IconSetInfo(DEFAULT_ID, "Lucide"),
        IconSetInfo("material-symbols", "Material Symbols"),
        IconSetInfo("tabler", "Tabler"),
        IconSetInfo("phosphor", "Phosphor"),
        IconSetInfo("heroicons", "Heroicons"),
    )

    private val loader: ClassLoader get() = IconSet::class.java.classLoader

    @Volatile
    private var activeId: String = DEFAULT_ID

    /** Switches sets. Unknown ids fall back rather than leaving the application iconless. */
    fun use(id: String) {
        activeId = if (known.any { it.id == id }) id else DEFAULT_ID
    }

    fun activeId(): String = activeId

    /**
     * The sets actually installed, default first.
     *
     * Probed rather than assumed: a set counts as present once it holds any icon at all, so the
     * empty folders waiting to be filled do not show up in Settings offering nothing.
     */
    fun available(): List<IconSetInfo> = known.filter { set ->
        set.id == DEFAULT_ID || PROBES.any { loader.getResource("icons/${set.id}/$it.svg") != null }
    }

    /** The classpath location of [name] in the active set, or in Lucide when it has no such icon. */
    fun path(name: String): String {
        val preferred = "icons/$activeId/$name.svg"
        if (activeId == DEFAULT_ID) return preferred
        return if (loader.getResource(preferred) != null) preferred else "icons/$DEFAULT_ID/$name.svg"
    }

    /**
     * A handful of names to test a folder with. Any one of them is enough: the point is to tell an
     * empty folder from a populated one, not to insist a set be finished before it can be chosen.
     */
    private val PROBES = listOf("settings", "close", "search", "edit")
}

/**
 * Every icon the application uses, by meaning.
 *
 * Constants rather than strings at the call site, because an icon is loaded by name at runtime
 * inside a `runCatching`: a mistyped string is a null icon and a blank button, never an error.
 * Written this way the compiler catches it, and the full list of what a new set has to supply is
 * readable in one place.
 *
 * Each resolves through [IconSet] on every read, so switching sets does not need these rebuilt.
 */
object Icons {
    // ── Actions ───────────────────────────────────────────────────────────────
    val ADD get() = IconSet.path("add")
    val EDIT get() = IconSet.path("edit")
    val DELETE get() = IconSet.path("delete")
    val MORE get() = IconSet.path("more")
    val COPY get() = IconSet.path("copy")
    val SEARCH get() = IconSet.path("search")
    val SWAP get() = IconSet.path("swap")
    val CHECK get() = IconSet.path("check")
    val CLOSE get() = IconSet.path("close")
    val PIN get() = IconSet.path("pin")
    val UNPIN get() = IconSet.path("unpin")
    val NAV_BACK get() = IconSet.path("nav-back")
    val NAV_FORWARD get() = IconSet.path("nav-forward")

    // ── Services ──────────────────────────────────────────────────────────────
    val TRANSLATE get() = IconSet.path("translate")
    val DICTIONARY get() = IconSet.path("dictionary")
    val SPEAK get() = IconSet.path("speak")
    val OCR get() = IconSet.path("ocr")
    val SUMMARIZE get() = IconSet.path("summarize")
    val SERVICE get() = IconSet.path("service")
    val DOCUMENT get() = IconSet.path("document")

    // ── Settings sections ─────────────────────────────────────────────────────
    val GENERAL get() = IconSet.path("general")
    val APPEARANCE get() = IconSet.path("appearance")
    val LANGUAGE get() = IconSet.path("language")
    val LAYOUT get() = IconSet.path("layout")
    val POPUP get() = IconSet.path("popup")
    val KEYBOARD get() = IconSet.path("keyboard")
    val PLUGIN get() = IconSet.path("plugin")
    val NETWORK get() = IconSet.path("network")
    val SETTINGS get() = IconSet.path("settings")

    // ── Status ────────────────────────────────────────────────────────────────
    val INFO get() = IconSet.path("info")
    val WARNING get() = IconSet.path("warning")
    val NOTIFICATION get() = IconSet.path("notification")
}
