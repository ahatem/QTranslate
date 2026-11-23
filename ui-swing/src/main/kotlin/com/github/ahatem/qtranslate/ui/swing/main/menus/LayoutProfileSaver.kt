package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.LayoutProfile
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.cleanProfileName
import com.github.ahatem.qtranslate.core.settings.data.profileNamed
import com.github.ahatem.qtranslate.core.settings.data.withProfileSaved
import java.awt.Component
import javax.swing.JOptionPane

/** What the user is asked while saving a layout. Separate from the flow so the flow can be tested. */
interface ProfilePrompts {
    /** The name the user typed, or null when they cancelled. */
    fun askName(): String?

    /** Whether to overwrite the saved layout called [name]. */
    fun confirmReplace(name: String): Boolean

    /** Tells the user the list holds [max] layouts already, so a new name cannot be added. */
    fun showFull(max: Int)
}

/** The questions of a page that also renames and deletes saved layouts. */
interface ProfileManagementPrompts : ProfilePrompts {
    /** The new name the user typed for the layout called [current], or null when they cancelled. */
    fun askRename(current: String): String?

    /** Whether to delete the saved layout called [name]. */
    fun confirmDelete(name: String): Boolean

    /** Tells the user another saved layout already has [name], so the rename did not happen. */
    fun showNameTaken(name: String)
}

/**
 * Saves the window's current layout as a named profile.
 *
 * The name is trimmed and an empty one cancels. A name that is already taken asks before
 * replacing; a new name on a full list says so and saves nothing. The list is written only
 * through [save], which stores it with `Configuration.withLayoutProfiles`.
 */
class LayoutProfileSaver(
    private val prompts: ProfilePrompts,
    private val profiles: () -> List<LayoutProfile>,
    private val layout: () -> WindowLayout,
    private val save: (List<LayoutProfile>) -> Unit,
) {
    fun saveCurrent() {
        val name = cleanProfileName(prompts.askName()) ?: return
        val existing = profiles().profileNamed(name)
        if (existing != null && !prompts.confirmReplace(existing.name)) return

        val updated = profiles().withProfileSaved(name, layout())
        if (updated == null) {
            prompts.showFull(LayoutProfile.MAX_PROFILES)
            return
        }
        save(updated)
    }
}

/** The words of the save dialogs. */
data class ProfileStrings(
    val nameTitle: String,
    val namePrompt: String,
    val replaceTitle: String,
    val replaceMessage: (String) -> String,
    val replace: String,
    val cancel: String,
    val fullTitle: String,
    val fullMessage: (Int) -> String,
    val renameTitle: String,
    val renamePrompt: String,
    val nameTakenMessage: (String) -> String,
    val deleteTitle: String,
    val deleteMessage: (String) -> String,
    val delete: String,
)

fun LocalizationManager.profileStrings() = ProfileStrings(
    nameTitle = getString("main_window_main_menu.profile_name_title"),
    namePrompt = getString("main_window_main_menu.profile_name_prompt"),
    replaceTitle = getString("main_window_main_menu.profile_replace_title"),
    replaceMessage = { name -> getString("main_window_main_menu.profile_replace_message", name) },
    replace = getString("main_window_main_menu.region_replace"),
    cancel = getString("common.cancel"),
    fullTitle = getString("main_window_main_menu.layout_saved_layouts"),
    fullMessage = { max -> getString("main_window_main_menu.profile_full_message", max) },
    renameTitle = getString("settings_window.layout_profile_rename_title"),
    renamePrompt = getString("settings_window.layout_profile_rename_prompt"),
    nameTakenMessage = { name -> getString("settings_window.layout_profile_name_taken", name) },
    deleteTitle = getString("settings_window.layout_profile_delete_title"),
    deleteMessage = { name -> getString("settings_window.layout_profile_delete_message", name) },
    delete = getString("common.delete"),
)

/**
 * The dialogs as option panes over [parent]. An option pane takes its reading direction from its
 * parent, so a right-to-left window asks its questions right to left.
 */
class SwingProfilePrompts(
    private val parent: () -> Component?,
    private val strings: () -> ProfileStrings,
) : ProfileManagementPrompts {

    override fun askName(): String? = JOptionPane.showInputDialog(
        parent(), strings().namePrompt, strings().nameTitle, JOptionPane.PLAIN_MESSAGE
    )

    override fun confirmReplace(name: String): Boolean {
        val words = strings()
        val options = arrayOf(words.replace, words.cancel)
        val choice = JOptionPane.showOptionDialog(
            parent(), words.replaceMessage(name), words.replaceTitle,
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1],
        )
        return choice == 0
    }

    override fun showFull(max: Int) {
        val words = strings()
        JOptionPane.showMessageDialog(parent(), words.fullMessage(max), words.fullTitle, JOptionPane.INFORMATION_MESSAGE)
    }

    override fun askRename(current: String): String? = JOptionPane.showInputDialog(
        parent(), strings().renamePrompt, strings().renameTitle, JOptionPane.PLAIN_MESSAGE, null, null, current
    ) as? String

    override fun confirmDelete(name: String): Boolean {
        val words = strings()
        val options = arrayOf(words.delete, words.cancel)
        val choice = JOptionPane.showOptionDialog(
            parent(), words.deleteMessage(name), words.deleteTitle,
            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1],
        )
        return choice == 0
    }

    override fun showNameTaken(name: String) {
        val words = strings()
        JOptionPane.showMessageDialog(parent(), words.nameTakenMessage(name), words.renameTitle, JOptionPane.INFORMATION_MESSAGE)
    }
}
