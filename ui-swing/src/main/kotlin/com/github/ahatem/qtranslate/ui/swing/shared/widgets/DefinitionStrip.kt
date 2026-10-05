package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.shared.fonts.FontRuns
import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import java.awt.BorderLayout
import java.awt.Color
import java.awt.ComponentOrientation
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.UIManager
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * A short definition shown under a translation, for single words.
 *
 * Deliberately a separate strip rather than text appended to the translation itself, which is how
 * this used to be done elsewhere. Appended text ends up in the clipboard: copying a translated
 * word would hand you the word *and* a dictionary entry, which is almost never what was wanted.
 * Keeping it out of the output pane keeps copy honest.
 *
 * Styled as an aside — smaller, dimmer, set apart from the answer — because it is a detail
 * attached to the answer and not the answer. It hides itself entirely when there is nothing to
 * say, so a multi-word translation is laid out exactly as it was before this existed.
 *
 * @param showDivider whether to draw a hairline above the definition.
 *
 * True where the strip is the only thing marking the boundary — the popup, whose content pane
 * draws nothing of its own, and where the rule is exactly right. False beneath the main window's
 * output pane, which already has a border of its own: a second line immediately below it reads as a
 * doubled rule and cuts the definition off into a band rather than attaching it to the
 * translation. The two placements genuinely differ, so this is a parameter rather than a single
 * choice imposed on both.
 */
class DefinitionStrip(private val showDivider: Boolean = true) : JPanel(BorderLayout()) {

    private val borderColor: Color get() = UIManager.getColor("Component.borderColor") ?: Color.GRAY

    private val attrs = SimpleAttributeSet()

    /**
     * Styled rather than plain because a definition is a translation too, and one in a script the
     * interface font cannot draw is unreadable for the same reason the output pane was. The pane's
     * editor kit brings the per-run fonts; nothing else of the editor comes with it.
     *
     * Read-only selectable text. It may take mouse focus so the keyboard copy shortcut reaches its
     * own selection, but is excluded from normal focus traversal: a reader does not tab to a
     * definition.
     */
    private val text = JTextPane().apply {
        editorKit = WrappingEditorKit()
        isEditable = false
        isOpaque = false
        isFocusable = true
        skipFocusTraversal()
        putClientProperty("FlatLaf.styleClass", "small")
        foreground = UIManager.getColor("Label.disabledForeground")
    }

    /** Copy and Select All over this pane alone, never the translation it sits under. */
    private val selectionMenu = SelectionContextMenu(text)

    init {
        isOpaque = false
        isVisible = false
        applyBorder()
        add(text, BorderLayout.CENTER)
    }

    /**
     * Localized labels for the selection menu, resolved from `common.copy` / `common.select_all`.
     * Optional: a caller with no localizer still gets a working menu with untranslated labels.
     */
    var contextMenuLabels: ContextMenuLabels?
        get() = selectionMenu.labels
        set(value) {
            selectionMenu.labels = value
        }

    /** The strip's text component, exposed so a host can inspect what it is about to show. */
    val textComponent: JTextPane get() = text

    /**
     * Height measured against the width this strip has actually been given.
     *
     * A wrapping text component reports a preferred size based on its own current width, which in
     * `BorderLayout.SOUTH` is whatever it was last set to rather than what it is about to get.
     * Left alone it asks for one line and gets one line, clipping a two-line definition — or asks
     * for its full unwrapped width and is squeezed flat. Measuring at the real width avoids both.
     */
    override fun getPreferredSize(): Dimension {
        val insets = insets
        val available = (width - insets.left - insets.right).coerceAtLeast(1)
        text.setSize(available, Int.MAX_VALUE)
        val textHeight = text.preferredSize.height
        return Dimension(0, textHeight + insets.top + insets.bottom)
    }

    /** Blank hides the strip; anything else shows it. */
    fun render(definition: String) {
        val wanted = definition.isNotBlank()
        if (wanted && text.text != definition) {
            text.text = definition
            applyFallbackFonts(definition)
            // Follows the definition's own script, not the interface language. A definition of an
            // Arabic word is Arabic, and left-aligning it under a right-aligned translation reads
            // as a stray line of text rather than a note about the word above it.
            val orientation =
                if (definition.isRTL()) ComponentOrientation.RIGHT_TO_LEFT
                else ComponentOrientation.LEFT_TO_RIGHT
            if (text.componentOrientation != orientation) {
                text.componentOrientation = orientation
                componentOrientation = orientation
            }
        }
        if (isVisible != wanted) {
            isVisible = wanted
            revalidate()
            repaint()
        }
    }

    /**
     * Gives every grapheme cluster a face that can draw it, through the resolver the output pane
     * uses. The strip has no editor font of its own — it is drawn in the interface font — so it
     * resolves against that font alone and lets the rescue faces answer for what it cannot draw.
     */
    private fun applyFallbackFonts(definition: String) {
        val doc = text.styledDocument
        val primary = text.font
        val fonts = FontRuns.resolve(definition, primary, primary)
        var runStart = 0
        while (runStart < fonts.size) {
            val font = fonts[runStart]
            var runEnd = runStart + 1
            while (runEnd < fonts.size && fonts[runEnd] === font) runEnd++
            if (font != null && font !== primary) {
                // LabelView resolves a run's font from family and size, so the ascent adjustment the
                // output pane applies has to be a size here too.
                val aligned = font.metricAlignedTo(primary)
                attrs.removeAttributes(attrs)
                StyleConstants.setFontFamily(attrs, aligned.family)
                StyleConstants.setFontSize(attrs, aligned.size)
                doc.setCharacterAttributes(runStart, runEnd - runStart, attrs, false)
            }
            runStart = runEnd
        }
    }

    /** Re-reads the theme's colours, which borders and foregrounds do not do by themselves. */
    fun refreshTheme() {
        text.foreground = UIManager.getColor("Label.disabledForeground")
        applyBorder()
        repaint()
    }

    private fun applyBorder() {
        border = if (showDivider) {
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor),
                BorderFactory.createEmptyBorder(
                    UIScale.scale(6),
                    UIScale.scale(8),
                    UIScale.scale(6),
                    UIScale.scale(8)
                )
            )
        } else {
            // Spacing alone. Indented to the same measure as the text above, so the definition
            // lines up with the translation instead of running wider than the box it belongs to.
            BorderFactory.createEmptyBorder(
                UIScale.scale(4),
                UIScale.scale(10),
                UIScale.scale(6),
                UIScale.scale(10)
            )
        }
    }
}
