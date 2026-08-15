package com.github.ahatem.qtranslate.ui.swing.shared.util

import java.awt.datatransfer.Clipboard
import java.awt.event.InputEvent
import javax.swing.JComponent
import javax.swing.TransferHandler

/**
 * Accepts dropped pictures and documents, and lets everything else through.
 *
 * ### Why every component needs one
 * Swing does not bubble a drop up the hierarchy: the deepest component under the pointer that owns
 * a `DropTarget` decides, and no one else is consulted. A `JTextPane` always owns one, so a single
 * handler on the frame is consulted only over window chrome — over the editors the pane refuses
 * the drag and the user gets the prohibited cursor. Installing this same handler on each of them
 * is what actually makes a drop behave the same everywhere in the window.
 *
 * ### Why it delegates
 * The pane's own handler is what makes dragging text into the editor work, and what puts selected
 * text on the clipboard for Ctrl+C, Ctrl+X and drag-out — `createTransferable` is protected, so a
 * replacement that does not delegate silently breaks all three. Anything this handler does not
 * recognise as a picture or a document is therefore passed straight back to [delegate].
 *
 * @param delegate the handler being wrapped, or null on a component that had none.
 */
class ContentDropHandler(
    private val delegate: TransferHandler?,
    private val onContent: (DroppedContent) -> Unit,
    private val onDragOver: () -> Unit = {},
    private val onDropped: () -> Unit = {}
) : TransferHandler() {

    override fun canImport(support: TransferSupport): Boolean {
        if (DroppedContentClassifier.canAccept(support.transferable)) {
            // Only while actually dragging: canImport is also called for paste, and flashing the
            // overlay on Ctrl+V would be nonsense.
            if (support.isDrop) onDragOver()
            return true
        }
        return delegate?.canImport(support) ?: false
    }

    override fun importData(support: TransferSupport): Boolean {
        val content = DroppedContentClassifier.classify(support.transferable)
        if (content != DroppedContent.None) {
            onDropped()
            onContent(content)
            return true
        }
        return delegate?.importData(support) ?: false
    }

    // Export is entirely the delegate's business. These three public entry points stand in for
    // createTransferable and exportDone, which are protected and cannot be called across
    // instances.

    override fun getSourceActions(c: JComponent?): Int = delegate?.getSourceActions(c) ?: NONE

    override fun exportToClipboard(comp: JComponent?, clip: Clipboard?, action: Int) {
        delegate?.exportToClipboard(comp, clip, action)
    }

    override fun exportAsDrag(comp: JComponent?, e: InputEvent?, action: Int) {
        delegate?.exportAsDrag(comp, e, action)
    }
}

/**
 * Wraps whatever handler [this] already has, so the component accepts pictures and documents
 * while keeping everything it could do before.
 */
fun JComponent.installContentDropHandler(
    onContent: (DroppedContent) -> Unit,
    onDragOver: () -> Unit = {},
    onDropped: () -> Unit = {}
) {
    // Captured before replacement; putting the new handler in place first would wrap itself.
    val existing = transferHandler
    transferHandler = ContentDropHandler(existing, onContent, onDragOver, onDropped)
    dropTarget?.isActive = true
}
