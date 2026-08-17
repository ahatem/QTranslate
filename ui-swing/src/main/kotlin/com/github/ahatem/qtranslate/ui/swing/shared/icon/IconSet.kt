package com.github.ahatem.qtranslate.ui.swing.shared.icon

import com.formdev.flatlaf.extras.FlatSVGIcon
import java.io.File

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
    fun available(): List<IconSetInfo> = installed().filter { it.id == DEFAULT_ID || it.id != DEFAULT_ID }

    /** Known sets that actually have icons, whether bundled or dropped into the icons folder. */
    private fun installed(): List<IconSetInfo> = known.filter { set ->
        set.id == DEFAULT_ID || PROBES.any { exists("icons/${set.id}/$it.svg") }
    }

    /**
     * Where sets other than the default live, mirroring the languages and themes folders.
     *
     * Set once at startup. Until it is, only the bundled default resolves, which is the right
     * behaviour for anything constructing icons before the application has found its data
     * directory rather than a reason to fail.
     */
    @Volatile
    private var externalRoot: File? = null

    fun installTo(appDataDirectory: File) {
        externalRoot = File(appDataDirectory, "icons").also { it.mkdirs() }
    }

    /** Whether [path] exists, on the classpath or under the icons folder. */
    private fun exists(path: String): Boolean =
        loader.getResource(path) != null || externalFile(path)?.isFile == true

    private fun externalFile(path: String): File? =
        externalRoot?.let { File(it, path.removePrefix("icons/")) }

    /**
     * Loads [path] from wherever it actually is.
     *
     * The default set is bundled in the module's resources, the way the English strings are, so
     * the application is never iconless however the data directory looks. Every other set is a
     * folder on disk that the user can add to, the way languages and themes are, and neither the
     * call sites nor the path shape have to know which of the two they are getting.
     */
    fun load(path: String, width: Int, height: Int): FlatSVGIcon {
        if (loader.getResource(path) != null) return FlatSVGIcon(path, width, height, loader)

        externalFile(path)?.takeIf { it.isFile }?.let { return FlatSVGIcon(it).derive(width, height) }

        // Non-null on purpose. The default set is bundled and complete, so a name that resolved
        // nowhere means the path was wrong rather than the set being thin, and every call site
        // wants an icon it can style. Returning the default's copy keeps a button from going blank
        // over a mistake somewhere else.
        val name = path.substringAfterLast('/')
        val bundled = "icons/$DEFAULT_ID/$name"
        return if (loader.getResource(bundled) != null) {
            FlatSVGIcon(bundled, width, height, loader)
        } else {
            FlatSVGIcon("ui/icons/missing_icon.svg", width, height, loader)
        }
    }

    /** The classpath location of [name] in the active set, or in Lucide when it has no such icon. */
    fun path(name: String): String {
        val preferred = "icons/$activeId/$name.svg"
        if (activeId == DEFAULT_ID) return preferred
        return if (exists(preferred)) preferred else "icons/$DEFAULT_ID/$name.svg"
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
