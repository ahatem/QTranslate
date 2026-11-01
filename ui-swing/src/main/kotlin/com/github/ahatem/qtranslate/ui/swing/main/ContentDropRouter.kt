package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContent
import com.github.ahatem.qtranslate.ui.swing.shared.util.toImageData
import java.io.File
import javax.swing.SwingUtilities

/**
 * Routes one dropped [DroppedContent] to what the application does with it.
 *
 * The frame installs the same routing on every surface — window chrome, the drag overlay
 * and the text panes — so a drop behaves the same wherever in the window it lands. This
 * router is the decision half of that: pictures go straight to the OCR translation
 * intent, documents open the document translation dialog (deferred to the EDT, as a drop
 * can arrive mid-render), and everything else — plain text included — is deliberately
 * declined, because a text drag belongs to whichever editor it landed on.
 *
 * [deferToUi] is injectable so routing can be exercised without waiting on the real EDT.
 */
internal class ContentDropRouter(
    private val dispatch: (MainIntent) -> Unit,
    private val openDocument: (File) -> Unit,
    private val deferToUi: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
) {
    fun route(content: DroppedContent) {
        when (content) {
            is DroppedContent.Picture ->
                dispatch(MainIntent.OcrAndTranslateImage(content.image.toImageData("png")))
            is DroppedContent.Document ->
                deferToUi { openDocument(content.file) }
            DroppedContent.None -> Unit
        }
    }
}
