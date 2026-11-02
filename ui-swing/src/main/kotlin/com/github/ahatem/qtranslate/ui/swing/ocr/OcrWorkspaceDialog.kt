package com.github.ahatem.qtranslate.ui.swing.ocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.ocr.OcrExtractionUseCase
import com.github.ahatem.qtranslate.core.ocr.OcrProviderInfo
import com.github.ahatem.qtranslate.core.ocr.OcrServiceCatalog
import com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceError
import com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceState
import com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceStore
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContent
import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContentClassifier
import com.github.ahatem.qtranslate.ui.swing.shared.util.connectedScreenBounds
import com.github.ahatem.qtranslate.ui.swing.shared.util.copyToClipboard
import com.github.ahatem.qtranslate.ui.swing.shared.util.isPositionReachable
import com.github.ahatem.qtranslate.ui.swing.shared.util.toImageData
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.CaretEvent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The dedicated OCR utility window.
 *
 * It is intentionally compact: image and recognition on the left, editable text and translation
 * on the right. It owns OCR work through [OcrWorkspaceStore]; translation is dispatched to the
 * canonical [MainStore] and observed back, so this window never becomes a second translator.
 */
class OcrWorkspaceDialog(
    /**
     * Deliberately nullable and normally null: the main frame is hidden/iconified while a
     * capture is in flight, and an owned dialog can be suppressed or pull that owner back.
     * The workspace is an independent utility window; MainAppFrame disposes it explicitly.
     */
    owner: Frame?,
    private val mainStore: MainStore,
    private val settingsStore: SettingsStore,
    private val localizer: LocalizationManager,
    extraction: OcrExtractionUseCase,
    catalog: OcrServiceCatalog,
    private val onCaptureRequested: () -> Unit,
    private val onShowMainWindow: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onClosed: () -> Unit = {},
) : JDialog(owner, localizer.getString("ocr_workspace.title"), false) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("OcrWorkspaceDialog"))
    private val store = OcrWorkspaceStore(
        scope = scope,
        extraction = extraction,
        catalog = catalog,
        configuration = { settingsStore.state.value.workingConfiguration },
        ocrSettings = { settingsStore.state.value.workingConfiguration.ocr },
        translationSource = { mainStore.state.value.sourceLanguage },
    )

    private val imagePanel = OcrImagePanel()
    private val imageScroll = JScrollPane(imagePanel)
    private val providerCombo = JComboBox<OcrProviderInfo>()
    private val languageCombo = JComboBox<LanguageCode>()
    private val recognizeButton = JButton()
    private val cancelRecognitionButton = JButton()
    private val statusLabel = JLabel(" ")
    private val annotationsCheck = JCheckBox()
    private val ocrTextArea = JTextArea()
    private val translationArea = JTextArea()
    private val sourceCombo = JComboBox<LanguageCode>()
    private val targetCombo = JComboBox<LanguageCode>()
    private val translateButton = JButton()
    private val copyOcrButton = JButton()
    private val copyTranslationButton = JButton()
    private val listenOcrButton = JButton()
    private val listenTranslationButton = JButton()
    private val copyImageButton = JButton()
    private val saveImageButton = JButton()

    private var updatingOcrText = false
    private var updatingCombos = false
    private var lastOcrState: OcrWorkspaceState? = null
    private var expectedTranslationInput: String? = null
    private var translatedTextForCurrent: String = ""
    private var autoTranslatePending = false
    private var regionRanges: List<IntRange> = emptyList()

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(760, 460)
        size = Dimension(900, 600)
        buildUi()
        bindEvents()
        observeState()
        restoreBounds()
        rootPane.registerKeyboardAction(
            { closeWorkspace() },
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                shutdownNow()
                persistBounds()
                onClosed()
            }
        })
    }

    /** Opens [image] and, when requested, starts recognition immediately. */
    fun openImage(
        image: ImageData,
        label: String,
        autoTranslate: Boolean,
        initialText: String? = null,
        initialRegions: List<OCRTextRegion> = emptyList(),
    ) {
        scope.launch {
            val decoded = withContext(Dispatchers.IO) { decode(image) }
            withContext(Dispatchers.Swing) {
                if (decoded == null) {
                    showError(localizer.getString("ocr_workspace.image_open_failed"))
                    return@withContext
                }
                imagePanel.setImage(decoded)
                invalidateWorkspaceTranslation()
                autoTranslatePending = autoTranslate
                if (initialText != null) {
                    store.openWithResult(image, label, initialText, initialRegions)
                } else {
                    store.openImage(image, label, autoRun = true)
                }
                if (!isVisible) {
                    isVisible = true
                }
                toFront()
                SwingUtilities.invokeLater {
                    imagePanel.fitToWindow(imageScroll.viewport.extentSize)
                    if (autoTranslate && initialText != null) {
                        translateCurrentText(initialText)
                        autoTranslatePending = false
                    }
                    ocrTextArea.requestFocusInWindow()
                }
            }
        }
    }

    fun closeWorkspace() {
        dispatchEvent(WindowEvent(this, WindowEvent.WINDOW_CLOSING))
    }

    /** Synchronous teardown for application exit, before AWT posts WINDOW_CLOSED. */
    fun shutdownNow() {
        store.close()
        scope.cancel()
    }

    private fun buildUi() {
        title = localizer.getString("ocr_workspace.title")
        contentPane.layout = BorderLayout()

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildImageSide(), buildTextSide()).apply {
            resizeWeight = 0.47
            border = BorderFactory.createEmptyBorder()
            dividerSize = 6
        }
        contentPane.add(split, BorderLayout.CENTER)
    }

    private fun buildImageSide(): JPanel {
        val toolbar = JPanel(WrapLayout(FlowLayout.LEADING, 4, 2)).apply { isOpaque = false }
        toolbar.add(actionButton("ocr_workspace.capture") { onCaptureRequested() })
        toolbar.add(actionButton("ocr_workspace.open_image") { chooseImageFile() })
        toolbar.add(actionButton("ocr_workspace.paste_image") { pasteImage() })
        copyImageButton.text = localizer.getString("ocr_workspace.copy_image")
        copyImageButton.addActionListener { copyCurrentImage() }
        saveImageButton.text = localizer.getString("ocr_workspace.save_image")
        saveImageButton.addActionListener { saveCurrentImage() }
        toolbar.add(copyImageButton)
        toolbar.add(saveImageButton)
        toolbar.add(Box.createHorizontalStrut(8))
        toolbar.add(actionButton("ocr_workspace.zoom_out") { imagePanel.zoomOut() })
        toolbar.add(actionButton("ocr_workspace.fit") { imagePanel.fitToWindow(imageScroll.viewport.extentSize) })
        toolbar.add(actionButton("ocr_workspace.actual_size") { imagePanel.actualSize() })
        toolbar.add(actionButton("ocr_workspace.zoom_in") { imagePanel.zoomIn() })

        annotationsCheck.text = localizer.getString("ocr_workspace.show_boxes")
        annotationsCheck.isOpaque = false
        annotationsCheck.isEnabled = false
        annotationsCheck.addActionListener { store.toggleAnnotations() }
        toolbar.add(annotationsCheck)

        imageScroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        imageScroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        imageScroll.border = BorderFactory.createTitledBorder(localizer.getString("ocr_workspace.image_group"))

        return JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(imageScroll, BorderLayout.CENTER)
        }
    }

    private fun buildTextSide(): JPanel {
        val root = JPanel(BorderLayout(0, 6))
        root.border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
        root.add(buildRecognitionBar(), BorderLayout.NORTH)

        val texts = JPanel(GridLayout(2, 1, 0, 6))
        texts.add(buildOcrTextPanel())
        texts.add(buildTranslationPanel())
        root.add(texts, BorderLayout.CENTER)
        root.add(buildBottomBar(), BorderLayout.SOUTH)
        return root
    }

    private fun buildRecognitionBar(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder(localizer.getString("ocr_workspace.recognition_group"))
        val gbc = GridBagConstraints().apply {
            gridy = 0
            insets = Insets(2, 4, 2, 4)
            anchor = GridBagConstraints.LINE_START
        }
        panel.add(JLabel(localizer.getString("ocr_workspace.provider")), gbc)
        providerCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): Component = super.getListCellRendererComponent(
                list, (value as? OcrProviderInfo)?.name ?: localizer.getString("settings_ocr.provider_automatic"),
                index, selected, focused,
            )
        }
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(providerCombo, gbc)

        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(JLabel(localizer.getString("ocr_workspace.language")), gbc)
        languageCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): Component = super.getListCellRendererComponent(
                list, (value as? LanguageCode)?.getDisplayName(autoDetectLabel = localizer.getString("common.auto_detect")) ?: "",
                index, selected, focused,
            )
        }
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(languageCombo, gbc)

        recognizeButton.text = localizer.getString("ocr_workspace.recognize")
        cancelRecognitionButton.text = localizer.getString("ocr_workspace.cancel")
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.gridwidth = 2
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(
            JPanel(FlowLayout(FlowLayout.LEADING, 6, 0)).apply {
                isOpaque = false
                add(recognizeButton)
                add(cancelRecognitionButton)
                add(statusLabel)
            },
            gbc,
        )
        return panel
    }

    private fun buildOcrTextPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 2))
        panel.border = BorderFactory.createTitledBorder(localizer.getString("ocr_workspace.text_group"))
        ocrTextArea.lineWrap = true
        ocrTextArea.wrapStyleWord = true
        ocrTextArea.font = UIManager.getFont("TextArea.font")
        val scroll = JScrollPane(ocrTextArea)
        panel.add(scroll, BorderLayout.CENTER)

        val actions = JPanel(FlowLayout(FlowLayout.LEADING, 4, 0)).apply { isOpaque = false }
        copyOcrButton.text = localizer.getString("ocr_workspace.copy_text")
        listenOcrButton.text = localizer.getString("ocr_workspace.listen")
        actions.add(copyOcrButton)
        actions.add(listenOcrButton)
        panel.add(actions, BorderLayout.SOUTH)
        return panel
    }

    private fun buildTranslationPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 2))
        panel.border = BorderFactory.createTitledBorder(localizer.getString("ocr_workspace.translation_group"))
        translationArea.isEditable = false
        translationArea.lineWrap = true
        translationArea.wrapStyleWord = true
        translationArea.font = UIManager.getFont("TextArea.font")
        panel.add(JScrollPane(translationArea), BorderLayout.CENTER)

        val actions = JPanel(FlowLayout(FlowLayout.LEADING, 4, 0)).apply { isOpaque = false }
        copyTranslationButton.text = localizer.getString("ocr_workspace.copy_translation")
        listenTranslationButton.text = localizer.getString("ocr_workspace.listen")
        translateButton.text = localizer.getString("ocr_workspace.translate")
        actions.add(translateButton)
        actions.add(copyTranslationButton)
        actions.add(listenTranslationButton)
        panel.add(actions, BorderLayout.SOUTH)
        return panel
    }

    private fun buildBottomBar(): JPanel {
        val panel = JPanel(WrapLayout(FlowLayout.TRAILING, 4, 2)).apply { isOpaque = false }
        panel.add(JLabel(localizer.getString("ocr_workspace.source_language")))
        panel.add(sourceCombo)
        panel.add(JLabel(localizer.getString("ocr_workspace.target_language")))
        panel.add(targetCombo)
        panel.add(Box.createHorizontalStrut(6))
        panel.add(actionButton("ocr_workspace.send_main") {
            sendCurrentToMain()
        })
        panel.add(actionButton("ocr_workspace.send_quick") {
            sendCurrentToQuick()
        })
        panel.add(actionButton("ocr_workspace.settings") { onOpenSettings() })
        sourceCombo.renderer = languageRenderer()
        targetCombo.renderer = languageRenderer()
        return panel
    }

    private fun bindEvents() {
        providerCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            store.selectProvider((providerCombo.selectedItem as? OcrProviderInfo)?.id)
        }
        languageCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            (languageCombo.selectedItem as? LanguageCode)?.let(store::selectLanguage)
        }
        recognizeButton.addActionListener { store.runOcr() }
        cancelRecognitionButton.addActionListener { store.cancelOcr() }
        copyOcrButton.addActionListener { copyText(selectedOrAll(ocrTextArea)) }
        copyTranslationButton.addActionListener { copyText(translatedTextForCurrent) }
        listenOcrButton.addActionListener { listenOcrText() }
        listenTranslationButton.addActionListener { listen(translatedTextForCurrent, TextSource.Output) }
        translateButton.addActionListener { translateCurrentText(ocrTextArea.text) }
        sourceCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            (sourceCombo.selectedItem as? LanguageCode)?.let {
                mainStore.dispatch(MainIntent.SelectSourceLanguage(it))
                retranslateIfVisible()
            }
        }
        targetCombo.addActionListener {
            if (updatingCombos) return@addActionListener
            (targetCombo.selectedItem as? LanguageCode)?.let {
                mainStore.dispatch(MainIntent.SelectTargetLanguage(it))
                retranslateIfVisible()
            }
        }

        ocrTextArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = textChanged()
            override fun removeUpdate(e: DocumentEvent) = textChanged()
            override fun changedUpdate(e: DocumentEvent) = textChanged()
            private fun textChanged() {
                if (!updatingOcrText) store.updateText(ocrTextArea.text)
            }
        })
        ocrTextArea.addCaretListener(::highlightRegionForCaret)
        imagePanel.onRegionClicked = ::selectRegion
        imageScroll.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                // A fitted image stays fitted when the window is resized; manual zoom is left alone.
                if (lastOcrState?.image != null) imagePanel.refitIfFitted(imageScroll.viewport.extentSize)
            }
        })
    }

    private fun observeState() {
        scope.launch {
            store.state.collect { state ->
                withContext(Dispatchers.Swing) { renderOcr(state) }
            }
        }
        scope.launch(Dispatchers.Swing) {
            mainStore.state.collect { renderMain(it) }
        }
    }

    private fun renderOcr(state: OcrWorkspaceState) {
        val previous = lastOcrState
        lastOcrState = state
        if (previous != null && previous.recognizedText != state.recognizedText) {
            // The translation workspace stores one source string. A new recognition result or
            // an edit makes any previous workspace translation describe different text.
            invalidateWorkspaceTranslation()
        }

        updatingCombos = true
        val providers = providerCombo.model as DefaultComboBoxModel<OcrProviderInfo>
        providers.removeAllElements()
        state.providers.forEach(providers::addElement)
        providerCombo.selectedItem = state.providers.firstOrNull { it.id == state.providerId }
        providerCombo.isEnabled = state.providers.isNotEmpty() && !state.isRecognizing
        val languages = languageCombo.model as DefaultComboBoxModel<LanguageCode>
        languages.removeAllElements()
        state.languages.forEach(languages::addElement)
        languageCombo.selectedItem = state.language.takeIf { it in state.languages }
        languageCombo.isEnabled = state.languages.isNotEmpty() && !state.isRecognizing
        updatingCombos = false

        recognizeButton.isEnabled = state.canRunOcr
        cancelRecognitionButton.isEnabled = state.isRecognizing
        translateButton.isEnabled = state.canTranslate && !state.isRecognizing
        copyOcrButton.isEnabled = state.hasText
        listenOcrButton.isEnabled = state.hasText
        copyImageButton.isEnabled = state.hasImage
        saveImageButton.isEnabled = state.hasImage
        copyTranslationButton.isEnabled = translatedTextForCurrent.isNotBlank()
        listenTranslationButton.isEnabled = translatedTextForCurrent.isNotBlank()
        annotationsCheck.isEnabled = state.regions.isNotEmpty()
        annotationsCheck.isSelected = state.showAnnotations && state.regions.isNotEmpty()
        imagePanel.setRegions(state.regions)
        imagePanel.setAnnotationsVisible(state.showAnnotations && state.regions.isNotEmpty())

        val error = state.error
        statusLabel.text = when {
            state.isRecognizing -> localizer.getString("ocr_workspace.recognizing")
            error != null -> errorText(error)
            state.isEdited && state.hasText -> localizer.getString("ocr_workspace.edited")
            state.hasText -> localizer.getString("ocr_workspace.ready")
            else -> localizer.getString("ocr_workspace.no_text")
        }
        statusLabel.foreground = if (error != null) {
            UIManager.getColor("Label.errorForeground") ?: Color.RED
        } else {
            UIManager.getColor("Label.foreground")
        }

        if (ocrTextArea.text != state.recognizedText) {
            val caret = ocrTextArea.caretPosition
            updatingOcrText = true
            ocrTextArea.text = state.recognizedText
            ocrTextArea.caretPosition = caret.coerceAtMost(ocrTextArea.text.length)
            updatingOcrText = false
        }
        regionRanges = buildRegionRanges(state.recognizedText, state.regions)

        if (previous != null && previous.isRecognizing && !state.isRecognizing) {
            if (state.hasText) {
                ocrTextArea.requestFocusInWindow()
                if (autoTranslatePending) {
                    autoTranslatePending = false
                    translateCurrentText(state.recognizedText)
                }
            } else {
                autoTranslatePending = false
            }
        }
    }

    private fun renderMain(state: MainState) {
        updatingCombos = true
        val sourceItems = sourceCombo.model as DefaultComboBoxModel<LanguageCode>
        sourceItems.removeAllElements()
        state.availableLanguages.forEach(sourceItems::addElement)
        sourceCombo.selectedItem = state.sourceLanguage
        sourceCombo.isEnabled = state.availableLanguages.isNotEmpty()
        val targetItems = targetCombo.model as DefaultComboBoxModel<LanguageCode>
        targetItems.removeAllElements()
        state.availableLanguages.filter { it != LanguageCode.AUTO }.forEach(targetItems::addElement)
        targetCombo.selectedItem = state.targetLanguage
        targetCombo.isEnabled = targetItems.size > 0
        updatingCombos = false

        val expected = expectedTranslationInput
        val isThisRequest = expected != null && state.inputText == expected
        if (isThisRequest) {
            translatedTextForCurrent = if (!state.isLoading && !state.translationFailed) {
                state.translatedText
            } else {
                ""
            }
            translationArea.text = when {
                state.isLoading -> localizer.getString("ocr_workspace.translating")
                state.translationFailed && state.translatedText.isBlank() ->
                    localizer.getString("ocr_workspace.translation_failed")
                else -> state.translatedText
            }
            translationArea.foreground = if (state.translationFailed) {
                UIManager.getColor("Label.errorForeground") ?: Color.RED
            } else {
                UIManager.getColor("TextArea.foreground")
            }
            translateButton.isEnabled = ocrTextArea.text.isNotBlank() &&
                !state.isLoading &&
                lastOcrState?.isRecognizing != true
            copyTranslationButton.isEnabled = translatedTextForCurrent.isNotBlank()
            listenTranslationButton.isEnabled = translatedTextForCurrent.isNotBlank()
        } else if (state.inputText != expected) {
            // Another surface now owns the shared translation workspace. Do not display an
            // unrelated result as if it belonged to this OCR source.
            invalidateWorkspaceTranslation()
        }
    }

    private fun invalidateWorkspaceTranslation() {
        expectedTranslationInput = null
        translatedTextForCurrent = ""
        translationArea.text = ""
        translationArea.foreground = UIManager.getColor("TextArea.foreground")
        copyTranslationButton.isEnabled = false
        listenTranslationButton.isEnabled = false
    }

    private fun translateCurrentText(text: String) {
        if (text.isBlank()) return
        mainStore.dispatch(MainIntent.UpdateInputText(text))
        val prepared = mainStore.state.value.inputText
        if (prepared.isBlank()) return
        expectedTranslationInput = prepared
        translatedTextForCurrent = ""
        translationArea.text = localizer.getString("ocr_workspace.translating")
        translationArea.foreground = UIManager.getColor("TextArea.foreground")
        copyTranslationButton.isEnabled = false
        listenTranslationButton.isEnabled = false
        mainStore.dispatch(MainIntent.Translate(prepared))
    }

    private fun retranslateIfVisible() {
        val text = expectedTranslationInput ?: return
        translatedTextForCurrent = ""
        mainStore.dispatch(MainIntent.Translate(text))
    }

    private fun sendCurrentToMain() {
        val text = ocrTextArea.text
        if (text.isBlank()) return
        if (translatedTextForCurrent.isNotBlank() &&
            expectedTranslationInput == mainStore.state.value.inputText
        ) {
            onShowMainWindow()
            return
        }
        translateCurrentText(text)
        onShowMainWindow()
    }

    private fun sendCurrentToQuick() {
        val text = translatedTextForCurrent.ifBlank { ocrTextArea.text }
        if (text.isBlank()) return
        mainStore.dispatch(MainIntent.ShowQuickTranslate(text))
    }

    private fun selectRegion(index: Int) {
        val region = lastOcrState?.regions?.getOrNull(index) ?: return
        val range = regionRanges.getOrNull(index) ?: return
        imagePanel.setHighlightedRegion(index)
        ocrTextArea.requestFocusInWindow()
        ocrTextArea.select(range.first, range.last + 1)
    }

    private fun highlightRegionForCaret(event: CaretEvent) {
        val index = regionRanges.indexOfFirst { event.dot in it }
        if (index >= 0) imagePanel.setHighlightedRegion(index)
    }

    private fun chooseImageFile() {
        val chooser = JFileChooser().apply {
            dialogTitle = localizer.getString("ocr_workspace.open_image_title")
            fileFilter = FileNameExtensionFilter(
                localizer.getString("ocr_workspace.image_filter"),
                "png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff", "webp",
            )
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            ocrTextArea.requestFocusInWindow()
            return
        }
        val file = chooser.selectedFile
        scope.launch {
            val image = withContext(Dispatchers.IO) { runCatching { ImageIO.read(file) }.getOrNull() }
            withContext(Dispatchers.Swing) {
                if (image == null) {
                    showError(localizer.getString("ocr_workspace.image_open_failed"))
                } else {
                    openBufferedImage(image, file.name)
                }
            }
        }
    }

    private fun pasteImage() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val transferable = runCatching { clipboard.getContents(null) }.getOrNull()
        if (transferable == null) {
            showError(localizer.getString("ocr_workspace.clipboard_empty"))
            return
        }
        when (val content = DroppedContentClassifier.classify(transferable)) {
            is DroppedContent.Picture -> openBufferedImage(content.image, localizer.getString("ocr_workspace.clipboard_label"))
            else -> showError(localizer.getString("ocr_workspace.clipboard_empty"))
        }
    }

    private fun openBufferedImage(image: BufferedImage, label: String) {
        imagePanel.setImage(image)
        invalidateWorkspaceTranslation()
        autoTranslatePending = false
        scope.launch {
            val data = withContext(Dispatchers.IO) { image.toImageData("png") }
            withContext(Dispatchers.Swing) {
                store.openImage(data, label, autoRun = true)
                imagePanel.fitToWindow(imageScroll.viewport.extentSize)
            }
        }
    }

    private fun copyCurrentImage() {
        val image = imagePanel.currentImage() ?: return
        val transferable = object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                if (!isDataFlavorSupported(flavor)) {
                    throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
                }
                return image
            }
        }
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null) }
        mainStore.dispatch(MainIntent.NotifyTextCopied)
    }

    private fun saveCurrentImage() {
        val image = imagePanel.currentImage() ?: return
        val chooser = JFileChooser().apply {
            dialogTitle = localizer.getString("ocr_workspace.save_image_title")
            fileFilter = FileNameExtensionFilter(localizer.getString("ocr_workspace.save_image_filter"), "png")
            selectedFile = File("ocr-image.png")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        var file = chooser.selectedFile
        if (!file.name.endsWith(".png", ignoreCase = true)) file = File("${file.absolutePath}.png")
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { ImageIO.write(image, "PNG", file) }.getOrDefault(false)
            }
            withContext(Dispatchers.Swing) {
                if (saved) {
                    statusLabel.text = localizer.getString("ocr_workspace.image_saved")
                } else {
                    showError(localizer.getString("ocr_workspace.image_save_failed"))
                }
            }
        }
    }

    private fun copyText(text: String) {
        if (text.isBlank()) return
        text.copyToClipboard()
        mainStore.dispatch(MainIntent.NotifyTextCopied)
    }

    private fun listenOcrText() {
        if (ocrTextArea.text.isBlank()) return
        val ocrLanguage = lastOcrState?.language
            ?.takeIf { it != LanguageCode.AUTO }
            ?: mainStore.state.value.resolvedSourceLanguage
        mainStore.dispatch(
            MainIntent.ListenToText(
                textSource = TextSource.Input,
                text = ocrTextArea.text,
                language = ocrLanguage,
            )
        )
    }

    private fun listen(text: String, source: TextSource) {
        if (text.isBlank()) return
        mainStore.dispatch(
            MainIntent.ListenToText(
                textSource = source,
                text = text,
                language = mainStore.state.value.targetLanguage,
            )
        )
    }

    private fun selectedOrAll(area: JTextArea): String =
        area.selectedText?.takeIf { it.isNotBlank() } ?: area.text

    private fun errorText(error: OcrWorkspaceError): String = when (error) {
        OcrWorkspaceError.NoImage -> localizer.getString("ocr_workspace.no_image")
        OcrWorkspaceError.NoProvider -> localizer.getString("status_bar.no_ocr_active")
        OcrWorkspaceError.NoText -> localizer.getString("status_bar.no_text_in_image")
        OcrWorkspaceError.Timeout -> localizer.getString("status_bar.ocr_timeout")
        is OcrWorkspaceError.Failed -> localizer.getString("status_bar.ocr_failed", error.summary)
    }

    private fun showError(message: String) {
        JOptionPane.showMessageDialog(this, message, localizer.getString("ocr_workspace.error_title"), JOptionPane.ERROR_MESSAGE)
    }

    private fun actionButton(key: String, onClick: () -> Unit): JButton =
        JButton(localizer.getString(key)).apply { addActionListener { onClick() } }

    private fun languageRenderer(): DefaultListCellRenderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
        ): Component = super.getListCellRendererComponent(
            list,
            (value as? LanguageCode)?.getDisplayName(autoDetectLabel = localizer.getString("common.auto_detect")) ?: "",
            index, selected, focused,
        )
    }

    private fun decode(image: ImageData): BufferedImage? = runCatching {
        ImageIO.read(java.io.ByteArrayInputStream(image.bytes))
    }.getOrNull()

    private fun buildRegionRanges(text: String, regions: List<OCRTextRegion>): List<IntRange> {
        var cursor = 0
        return regions.map { region ->
            val needle = region.text.takeIf { it.isNotBlank() } ?: return@map IntRange.EMPTY
            val found = text.indexOf(needle, cursor).takeIf { it >= 0 } ?: text.indexOf(needle)
            if (found < 0) {
                IntRange.EMPTY
            } else {
                cursor = found + needle.length
                found until (found + needle.length)
            }
        }
    }

    private fun restoreBounds() {
        val config = settingsStore.state.value.workingConfiguration
        val savedSize = config.ocrWorkspaceSize
        val savedPosition = config.ocrWorkspacePosition
        if (savedSize != null && savedSize.width > 0 && savedSize.height > 0) {
            size = Dimension(savedSize.width, savedSize.height)
        }
        if (savedPosition != null &&
            isPositionReachable(Rectangle(savedPosition.x, savedPosition.y, width, height), connectedScreenBounds())
        ) {
            setLocation(savedPosition.x, savedPosition.y)
        } else {
            setLocationRelativeTo(owner)
        }
    }

    private fun persistBounds() {
        settingsStore.dispatch(
            SettingsIntent.ToggleSetting {
                it.copy(
                    ocrWorkspaceSize = Size(width, height),
                    ocrWorkspacePosition = Position(x, y),
                )
            }
        )
    }
}
