package com.github.ahatem.qtranslate.ui.swing.shared.textpane

import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContent
import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContentClassifier
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.KeyStroke

/**
 * The platform's menu shortcut modifier: Command on macOS, Ctrl elsewhere.
 *
 * `Toolkit.getMenuShortcutKeyMaskEx` is headful-only: `HeadlessToolkit` throws `HeadlessException`,
 * so asking it during construction would make the pane impossible to build in a headless JVM. With
 * no headful toolkit the modifier is derived from the platform instead, which is the value the
 * toolkit reports on a desktop.
 */
internal object MenuShortcutModifier {

    fun current(): Int = resolve(
        headless = GraphicsEnvironment.isHeadless(),
        osName = System.getProperty("os.name"),
        toolkitMask = { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx },
    )

    /** [toolkitMask] is consulted only when a headful toolkit is available. */
    fun resolve(headless: Boolean, osName: String?, toolkitMask: () -> Int): Int =
        if (headless) fallback(osName) else toolkitMask()

    fun fallback(osName: String?): Int =
        if (osName.orEmpty().startsWith("Mac", ignoreCase = true)) InputEvent.META_DOWN_MASK
        else InputEvent.CTRL_DOWN_MASK
}

internal class TextPaneKeyBindings(
    private val pane: AdvancedTextPane,
    private val onTranslateRequest: (text: String) -> Unit,
    private val onImageDropped: ((BufferedImage) -> Unit)?,
    private val onDocumentPasted: ((File) -> Unit)?,
) {

    fun install() {
        val menuMask = MenuShortcutModifier.current()

        // Redo differs by platform: Ctrl+Y on Windows and Linux, Shift+Cmd+Z on macOS.
        val undoStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask)
        val redoStroke = if (menuMask == InputEvent.CTRL_DOWN_MASK) {
            KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK)
        } else {
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask or InputEvent.SHIFT_DOWN_MASK)
        }

        val undoAction = createAction("Undo", undoStroke) {
            if (pane.undoManager.canUndo()) pane.undoManager.undo()
        }
        val redoAction = createAction("Redo", redoStroke) {
            if (pane.undoManager.canRedo()) pane.undoManager.redo()
        }

        // Tab focus traversal — JTextPane normally inserts a literal tab; override that so
        // keyboard-only users can navigate out of the pane.
        //   Tab         → move focus to the next component in the traversal cycle
        //   Shift+Tab   → move focus to the previous component
        //   Ctrl+Tab    → insert a literal tab character (escape hatch for power users)
        val tabForwardAction = object : AbstractAction("tab-forward") {
            override fun actionPerformed(e: ActionEvent) = pane.transferFocus()
        }
        val tabBackwardAction = object : AbstractAction("tab-backward") {
            override fun actionPerformed(e: ActionEvent) = pane.transferFocusBackward()
        }
        val tabInsertAction = object : AbstractAction("tab-insert") {
            override fun actionPerformed(e: ActionEvent) {
                if (pane.isEditable) pane.replaceSelection("\t")
            }
        }
        pane.actionMap.put("tab-forward",  tabForwardAction)
        pane.actionMap.put("tab-backward", tabBackwardAction)
        pane.actionMap.put("tab-insert",   tabInsertAction)
        pane.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0),                          "tab-forward")
        pane.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "tab-backward")
        pane.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK),  "tab-insert")

        // Translate action — keystroke is set dynamically via setTranslateKeyStroke() so the
        // user-configured binding is always used; selected text is preferred over full pane text.
        val translateAction = object : AbstractAction("Translate") {
            override fun actionPerformed(e: ActionEvent) {
                val textToTranslate = pane.selectedText?.takeIf { it.isNotBlank() } ?: pane.text
                if (textToTranslate.isNotBlank()) onTranslateRequest(textToTranslate)
            }
        }

        pane.actionMap.put("undo", undoAction)
        pane.actionMap.put("redo", redoAction)
        pane.actionMap.put(TRANSLATE_ACTION, translateAction)
        pane.inputMap.put(undoStroke, "undo")
        pane.inputMap.put(redoStroke, "redo")
        // Accept the other redo convention as well: Ctrl+Shift+Z where the menu key is Ctrl, or
        // Ctrl+Y elsewhere.
        val alternativeRedo = KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask or InputEvent.SHIFT_DOWN_MASK)
        if (alternativeRedo != redoStroke) pane.inputMap.put(alternativeRedo, "redo")
        if (menuMask != InputEvent.CTRL_DOWN_MASK) {
            pane.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo")
        }
        // Ctrl+H would otherwise reach the look and feel's delete-previous binding. Ctrl, not the
        // menu key, since Cmd+H is the system Hide command on macOS.
        pane.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "none")

        // Explicitly wire standard text shortcuts in the component-level WHEN_FOCUSED InputMap.
        // Although these are already in the LAF's parent InputMap, setting the EditorKit
        // (WrappingEditorKit) triggers a UI reinstall whose InputMap parent chain can be
        // momentarily incomplete on some JVM/LAF combinations, causing the shortcuts to
        // silently fail.  Wiring them here makes the binding deterministic regardless of
        // reinstallation order.
        // CutAction and PasteAction are self-guarding: they no-op when isEditable = false.
        pane.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask), "copy-to-clipboard")
        pane.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, menuMask), "select-all")
        pane.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, menuMask), "cut-to-clipboard")

        val pasteStroke = KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask)
        if (onImageDropped != null) {
            // Paste stays on the pane rather than moving to the frame with drops: it targets
            // whatever has focus, so it is genuinely this component's business. It shares the
            // classifier so pasting and dropping agree on what a thing is.
            val pasteAction = createAction("PasteImageOrText", pasteStroke) {
                val contents = runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
                }.getOrNull()

                when (val content = contents?.let(DroppedContentClassifier::classify)) {
                    is DroppedContent.Picture -> onImageDropped.invoke(content.image)
                    is DroppedContent.Document -> onDocumentPasted?.invoke(content.file) ?: pane.paste()
                    else -> pane.paste()
                }
            }
            pane.actionMap.put("paste-image-or-text", pasteAction)
            pane.inputMap.put(pasteStroke, "paste-image-or-text")
        } else {
            // Output / read-only panes: wire plain text paste so it is always available
            // through the component-level InputMap (PasteAction is a no-op when !isEditable).
            pane.inputMap.put(pasteStroke, "paste-from-clipboard")
        }
    }

    /**
     * Swaps the shortcut that triggers the translate action. [old] is released and [new] registered;
     * either may be null.
     *
     * Returns false and installs nothing when [new] is already taken by another action, so a
     * configured shortcut cannot silently disarm Copy, Paste or Undo.
     */
    fun setTranslateKeyStroke(old: KeyStroke?, new: KeyStroke?): Boolean {
        // Validate before releasing anything, so a refused rebind leaves the old binding untouched.
        if (new != null) {
            val occupiedBy = pane.inputMap.get(new)
            if (occupiedBy != null && occupiedBy != TRANSLATE_ACTION) return false
        }

        // Only a stroke this action owns may be released.
        if (old != null && old != new && pane.inputMap.get(old) == TRANSLATE_ACTION) {
            pane.inputMap.remove(old)
        }

        // An unchanged stroke is already in place; a null [new] clears the binding.
        if (new != null && new != old) {
            pane.inputMap.put(new, TRANSLATE_ACTION)
        }
        return true
    }

    private fun createAction(name: String, accelerator: KeyStroke, action: (ActionEvent) -> Unit): Action =
        object : AbstractAction(name) {
            init { putValue(ACCELERATOR_KEY, accelerator) }
            override fun actionPerformed(e: ActionEvent) = action(e)
        }

    private companion object {
        /** Action name for the configurable translate command. */
        const val TRANSLATE_ACTION = "translate"
    }
}
