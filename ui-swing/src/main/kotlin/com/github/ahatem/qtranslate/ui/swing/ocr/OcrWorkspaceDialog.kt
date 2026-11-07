package com.github.ahatem.qtranslate.ui.swing.ocr

import com.formdev.flatlaf.ui.FlatLineBorder
import com.formdev.flatlaf.util.UIScale
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
import com.github.ahatem.qtranslate.ui.swing.main.layout.MirroredSplitPane
import com.github.ahatem.qtranslate.ui.swing.main.layout.UISpacing
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.ThemeIcon
import com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContentClassifier
import com.github.ahatem.qtranslate.ui.swing.shared.util.connectedScreenBounds
import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import com.github.ahatem.qtranslate.ui.swing.shared.util.copyToClipboard
import com.github.ahatem.qtranslate.ui.swing.shared.util.isPositionReachable
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
    private val imageCards = JPanel(CardLayout())
    private lateinit var imageControls: JPanel
    private val providerCombo = JComboBox<OcrProviderInfo>()
    private val languageCombo = JComboBox<LanguageCode>()
    private val recognizeButton = JButton()
    private val cancelRecognitionButton = JButton()
    private val statusLabel = JLabel(" ")
    private val infoLabel = JLabel(" ")
    private val busyBar = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
        preferredSize = Dimension(UIScale.scale(80), UIScale.scale(6))
    }
    private val recognitionOptions = JButton()
    private val zoomLabel = JButton()
    private val annotationsCheck = JCheckBox()
    private val ocrTextArea = HintTextArea()
    private val translationArea = HintTextArea()
    private val sourceCombo = JComboBox<LanguageCode>()
    private val targetCombo = JComboBox<LanguageCode>()
    private val swapButton = JButton("⇄")
    private val translateButton = JButton()
    private val copyOcrButton = JButton()
    private val copyTranslationButton = JButton()
    private val listenOcrButton = JButton()
    private val listenTranslationButton = JButton()
    private lateinit var acquisitionBar: OcrAcquisitionBar

    private var updatingOcrText = false
    private var updatingCombos = false
    private var lastOcrState: OcrWorkspaceState? = null
    private var expectedTranslationInput: String? = null
    private var translatedTextForCurrent: String = ""
    private var autoTranslatePending = false
    private var regionRanges: List<IntRange> = emptyList()

    @Volatile
    private var closed = false

    @Volatile
    private var shutdownComplete = false
    private var closeNotified = false

    /** The last image actually shown, restored when a newer materialization fails. */
    private var lastGoodPanelImage: BufferedImage? = null

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(760, 460)
        size = Dimension(900, 600)
        buildUi()
        bindEvents()
        registerShortcuts()
        observeState()
        restoreBounds()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                shutdownNow()
                if (!closeNotified) {
                    closeNotified = true
                    persistBounds()
                    onClosed()
                }
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
        initialLanguage: LanguageCode? = null,
    ) {
        // Claim input ownership before any decoding. A second open/paste/capture immediately
        // supersedes this one even while its bytes are still being materialized.
        val token = claimImageInput() ?: return
        scope.launch {
            val decoded = OcrImageMaterializer.decode(image)
            withContext(Dispatchers.Swing) {
                if (decoded == null) {
                    failMaterialization(token, OcrWorkspaceError.ImageOpenFailed)
                    return@withContext
                }
                finishMaterialization(
                    token = token,
                    decoded = decoded,
                    image = image,
                    label = label,
                    autoTranslate = autoTranslate,
                    initialText = initialText,
                    initialRegions = initialRegions,
                    initialLanguage = initialLanguage,
                )
            }
        }
    }

    /** Opens a screenshot captured by this workspace, encoding it off the EDT. */
    fun openCapturedImage(image: BufferedImage, label: String) {
        val token = claimImageInput() ?: return
        scope.launch {
            val materialized = OcrImageMaterializer.encode(image)
            withContext(Dispatchers.Swing) {
                if (materialized == null) {
                    failMaterialization(token, OcrWorkspaceError.ImageOpenFailed)
                    return@withContext
                }
                finishMaterialization(
                    token = token,
                    decoded = materialized.image,
                    image = materialized.data,
                    label = label,
                    autoTranslate = false,
                )
            }
        }
    }

    fun isWorkspaceClosed(): Boolean = closed

    fun closeWorkspace() {
        dispatchEvent(WindowEvent(this, WindowEvent.WINDOW_CLOSING))
    }

    override fun dispose() {
        // WINDOW_CLOSED is queued only after peer disposal. Stop reuse synchronously, including
        // direct dispose() and application-exit paths, before that delayed notification exists.
        shutdownNow()
        super.dispose()
    }

    /** Synchronous teardown for application exit, before AWT posts WINDOW_CLOSED. */
    fun shutdownNow() {
        if (shutdownComplete) return
        shutdownComplete = true
        closed = true
        store.close()
        scope.cancel()
    }

    /** Claims the visible input slot synchronously, before any file or clipboard work starts. */
    private fun claimImageInput(): Long? {
        if (closed) return null
        cancelWorkspaceTranslationIfRunning()
        imagePanel.currentImage()?.let { lastGoodPanelImage = it }
        val token = store.beginImageInput() ?: return null
        imagePanel.setImage(null)
        invalidateWorkspaceTranslation()
        autoTranslatePending = false
        return token
    }

    /** A new input supersedes the translation that belonged to the old one. */
    private fun cancelWorkspaceTranslationIfRunning() {
        val expected = expectedTranslationInput ?: return
        val main = mainStore.state.value
        if (main.isLoading && main.inputText == expected) {
            mainStore.dispatch(MainIntent.CancelTranslation)
        }
    }

    private fun failMaterialization(token: Long, error: OcrWorkspaceError) {
        if (store.failImageInput(token, error)) {
            imagePanel.setImage(lastGoodPanelImage)
            lastGoodPanelImage?.let { imagePanel.fitToWindow(imageScroll.viewport.extentSize) }
            flashStatus(errorText(error), isError = true)
            updateInfo()
        }
    }

    /**
     * Publishes materialized bytes only when [token] still owns the input. The image panel, OCR
     * auto-run and optional initial translation all follow that same decision, so text and
     * geometry from image A can never attach to an image B shown later.
     */
    private fun finishMaterialization(
        token: Long,
        decoded: BufferedImage,
        image: ImageData,
        label: String,
        autoTranslate: Boolean,
        initialText: String? = null,
        initialRegions: List<OCRTextRegion> = emptyList(),
        initialLanguage: LanguageCode? = null,
    ) {
        val accepted = store.completeImageInput(
            token = token,
            image = image,
            label = label,
            autoRun = initialText == null,
            initialText = initialText,
            initialRegions = initialRegions,
            recognizedLanguage = initialLanguage,
        )
        if (!accepted) return

        imagePanel.setImage(decoded)
        lastGoodPanelImage = decoded
        autoTranslatePending = autoTranslate && initialText == null
        updateInfo()
        if (!isVisible) {
            isVisible = true
        }
        toFront()
        SwingUtilities.invokeLater {
            if (!store.isImageInputCurrent(token)) return@invokeLater
            imagePanel.fitToWindow(imageScroll.viewport.extentSize)
            zoomLabel.text = "${(imagePanel.zoomScale() * 100).toInt()}%"
            if (autoTranslate && initialText != null) {
                translateCurrentText(initialText)
                autoTranslatePending = false
            }
            ocrTextArea.requestFocusInWindow()
        }
    }

    private fun buildUi() {
        contentPane.layout = BorderLayout()
        contentPane.add(JPanel(BorderLayout()).apply {
            add(buildAcquisitionBar(), BorderLayout.CENTER)
            add(JSeparator(), BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        val split = MirroredSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, buildImageSide(), buildTextSide()).apply {
            leadingResizeWeight = 0.46
            isMirrored = localizer.isRtl
            dividerSize = UISpacing.DIVIDER_SIZE
            clearBorder()
            setLeadingProportion(0.46)
        }
        contentPane.add(split, BorderLayout.CENTER)
        contentPane.add(JPanel(BorderLayout()).apply {
            add(JSeparator(), BorderLayout.NORTH)
            add(buildBottomBar(), BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
    }

    private fun buildAcquisitionBar(): JPanel {
        acquisitionBar = OcrAcquisitionBar(
            OcrAcquisitionBar.Labels(
                capture = localizer.getString("ocr_workspace.capture"),
                open = localizer.getString("ocr_workspace.open_image"),
                paste = localizer.getString("ocr_workspace.paste_image"),
                copy = localizer.getString("ocr_workspace.copy_image"),
                save = localizer.getString("ocr_workspace.save_image"),
                settings = localizer.getString("ocr_workspace.settings"),
                more = localizer.getString("screen_capture.more_actions"),
            ), onCaptureRequested, ::chooseImageFile, ::pasteImage, ::copyCurrentImage, ::saveCurrentImage, onOpenSettings,
        )
        return acquisitionBar
    }

    private fun buildImageSide(): JPanel {
        imagePanel.accessibleContext.accessibleName = localizer.getString("ocr_workspace.image_group")
        imagePanel.emptyMessage = localizer.getString("ocr_workspace.empty_image")
        imagePanel.accessibleContext.accessibleDescription = imagePanel.emptyMessage
        imageScroll.clearBorder()
        imageScroll.viewport.background = imagePanel.background
        annotationsCheck.text = localizer.getString("ocr_workspace.show_boxes")
        annotationsCheck.isOpaque = false
        annotationsCheck.isEnabled = false
        annotationsCheck.addActionListener { store.toggleAnnotations() }
        zoomLabel.toolTipText = localizer.getString("ocr_workspace.actual_size")
        zoomLabel.accessibleContext.accessibleName = zoomLabel.toolTipText
        fun zoomChanged() { zoomLabel.text = "${(imagePanel.zoomScale() * 100).toInt()}%" }
        zoomLabel.addActionListener { imagePanel.actualSize(); zoomChanged() }
        zoomChanged()
        zoomLabel.putClientProperty("JButton.buttonType", "toolBarButton")
        val more = compactButton("⋯", "screen_capture.more_actions") {}
        val fit = actionButton("ocr_workspace.fit") { imagePanel.fitToWindow(imageScroll.viewport.extentSize); zoomChanged() }.apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
        }
        more.addActionListener {
            JPopupMenu().apply {
                add(JMenuItem(fit.text).apply { addActionListener { fit.doClick() } })
                add(JCheckBoxMenuItem(annotationsCheck.text, annotationsCheck.isSelected).apply {
                    isEnabled = annotationsCheck.isEnabled
                    addActionListener { annotationsCheck.doClick() }
                })
            }.let { it.show(more, 0, -it.preferredSize.height) }
        }
        val zoomOut = compactButton("−", "ocr_workspace.zoom_out") { imagePanel.zoomOut(); zoomChanged() }
        val zoomIn = compactButton("+", "ocr_workspace.zoom_in") { imagePanel.zoomIn(); zoomChanged() }
        val zoom = JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(2), UIScale.scale(1))).apply {
            add(zoomOut); add(zoomLabel); add(zoomIn); add(fit); add(annotationsCheck); add(more)
            // Floating card above the image: opaque so the image does not show through the
            // controls, rounded with the same arc the toolbar buttons use.
            isOpaque = true
            border = object : FlatLineBorder(Insets(1, 1, 1, 1), null, 1f, -1) {
                override fun getLineColor() = UIManager.getColor("Component.borderColor") ?: Color.GRAY
                override fun getArc(): Int = UIManager.getInt("Button.arc")
            }
            background = UIManager.getColor("Panel.background") ?: Color.DARK_GRAY
        }
        imageControls = zoom
        imageControls.isVisible = false
        val empty = object : JPanel(GridBagLayout()) {
            override fun updateUI() {
                super.updateUI()
                background = UIManager.getColor("TextArea.background") ?: UIManager.getColor("Panel.background")
            }
        }.apply {
            val c = GridBagConstraints().apply { gridx = 0; fill = GridBagConstraints.NONE; anchor = GridBagConstraints.CENTER }
            add(JLabel(IconSet.load(Icons.OCR, 28, 28)).apply {
                foreground = UIManager.getColor("Label.disabledForeground")
            }, c)
            c.gridy = 1; c.insets = Insets(8, 12, 2, 12)
            add(JLabel(localizer.getString("ocr_workspace.empty_title")).apply {
                font = font.deriveFont(Font.BOLD)
                border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
            }, c)
            c.gridy = 2; c.insets = Insets(0, 12, 8, 12)
            add(JLabel(localizer.getString("ocr_workspace.empty_hint")).apply {
                border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
            }, c)
            c.gridy = 3; c.insets = Insets(0, 0, 0, 0)
            add(actionButton("ocr_workspace.capture") { onCaptureRequested() }, c)
        }
        imageCards.add(imageScroll, "image")
        imageCards.add(empty, "empty")
        (imageCards.layout as CardLayout).show(imageCards, "empty")

        // The zoom controls sit in a floating card docked at the bottom of the
        // image viewer with a gap around it, instead of a full-width strip
        // underneath the image — so a small capture leaves more of itself
        // visible and the controls read as floating over the viewer.
        val dock = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, UIScale.scale(8), UIScale.scale(8), UIScale.scale(8))
            add(zoom)
        }
        val wrapper = JPanel(BorderLayout()).apply {
            // The essential controls must fit on one line even when the divider is dragged.
            val essentialWidth = listOf(zoomOut, zoomLabel, zoomIn, more).sumOf { it.preferredSize.width } + UIScale.scale(12)
            minimumSize = Dimension(essentialWidth, 0)
            add(imageCards, BorderLayout.CENTER)
            add(dock, BorderLayout.SOUTH)
        }
        fun updateZoomVisibility() {
            if (!zoom.isVisible) return
            val available = wrapper.width - 2 * UIScale.scale(8)
            // Hide the secondary controls before measuring the bar, so it never
            // grows wider than the image it floats over.
            val essential = listOf(zoomOut, zoomLabel, zoomIn).sumOf { it.preferredSize.width }
            val fullWidth = essential + fit.preferredSize.width + annotationsCheck.preferredSize.width + UIScale.scale(24)
            val fitWidth = essential + fit.preferredSize.width + more.preferredSize.width + UIScale.scale(20)
            val full = available >= fullWidth
            annotationsCheck.isVisible = full
            fit.isVisible = full || available >= fitWidth
            more.isVisible = !full
            // Re-validate so the card shrinks/grows with its contents.
            dock.revalidate()
            wrapper.revalidate()
        }
        wrapper.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                updateZoomVisibility()
            }
        })
        // Keep the original responsive rule (listening to the bar itself) as a
        // second trigger; both compute the same visibility, so they agree.
        zoom.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                updateZoomVisibility()
            }
        })
        return wrapper
    }

    private fun buildTextSide(): JScrollPane {
        val sections = object : JPanel(GridLayout(2, 1, 0, 4)), Scrollable {
            override fun getPreferredScrollableViewportSize() = preferredSize
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight(): Boolean =
                (parent as? JViewport)?.height?.let { it >= preferredSize.height } == true
            override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = getFontMetrics(font).height
            override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = r.height
        }.apply {
            border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
            add(buildOcrTextPanel()); add(buildTranslationPanel())
        }
        return JScrollPane(sections).apply {
            clearBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            minimumSize = Dimension(0, 0)
        }
    }

    private fun sectionHeader(key: String, vararg buttons: JButton): JPanel = JPanel(BorderLayout()).apply {
        add(JLabel(localizer.getString(key)).apply {
            font = font.deriveFont(Font.BOLD)
            border = BorderFactory.createEmptyBorder(0, if (localizer.isRtl) 12 else 2, 0, if (localizer.isRtl) 12 else 2)
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0)).apply { buttons.forEach(::add) }, BorderLayout.LINE_END)
    }

    private fun compactButton(glyph: String, key: String, action: () -> Unit): JButton = JButton(glyph).apply {
        toolTipText = localizer.getString(key)
        accessibleContext.accessibleName = toolTipText
        putClientProperty("JButton.buttonType", "toolBarButton")
        addActionListener { action() }
    }

    private fun configureSecondary(button: JButton, iconPath: String, key: String) {
        button.icon = IconSet.load(iconPath, 16, 16)
        button.toolTipText = localizer.getString(key)
        button.accessibleContext.accessibleName = button.toolTipText
        button.putClientProperty("JButton.buttonType", "toolBarButton")
    }

    private fun buildOcrTextPanel(): JPanel {
        providerCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean): Component =
                super.getListCellRendererComponent(list, (value as? OcrProviderInfo)?.name ?: localizer.getString("settings_ocr.provider_automatic"), index, selected, focused)
        }
        languageCombo.renderer = languageRenderer()
        recognitionOptions.text = localizer.getString("ocr_workspace.recognition_group")
        recognitionOptions.icon = ThemeIcon("Table.descendingSortIcon")
        recognitionOptions.horizontalTextPosition = SwingConstants.LEADING
        recognitionOptions.putClientProperty("JButton.buttonType", "toolBarButton")
        recognitionOptions.horizontalAlignment = SwingConstants.LEADING
        recognitionOptions.isRequestFocusEnabled = false
        recognitionOptions.addActionListener {
            JPopupMenu().apply {
                add(ocrChoiceMenu(localizer.getString("ocr_workspace.provider"), providerCombo) { it.name })
                add(ocrChoiceMenu(localizer.getString("ocr_workspace.language"), languageCombo) {
                    it.getDisplayName(autoDetectLabel = localizer.getString("common.auto_detect"))
                })
            }.show(recognitionOptions, 0, recognitionOptions.height)
        }
        ocrTextArea.lineWrap = true
        ocrTextArea.wrapStyleWord = true
        ocrTextArea.border = BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10))
        ocrTextArea.background = UIManager.getColor("TextArea.background")
        ocrTextArea.hint = localizer.getString("ocr_workspace.no_text")
        statusLabel.labelFor = ocrTextArea
        configureSecondary(copyOcrButton, Icons.COPY, "ocr_workspace.copy_text")
        configureSecondary(listenOcrButton, Icons.SPEAK, "ocr_workspace.listen")
        recognizeButton.text = localizer.getString("ocr_workspace.recognize")
        cancelRecognitionButton.text = localizer.getString("ocr_workspace.cancel")
        cancelRecognitionButton.isVisible = false
        val footer = JPanel(BorderLayout()).apply {
            add(recognitionOptions, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, 2, 0)).apply { add(recognizeButton); add(cancelRecognitionButton) }, BorderLayout.LINE_END)
        }
        return JPanel(BorderLayout(0, 2)).apply {
            // Based on the current font, so the editor remains useful at every UI scale.
            preferredSize = Dimension(1, getFontMetrics(font).height * 7)
            add(sectionHeader("ocr_workspace.text_group", copyOcrButton, listenOcrButton), BorderLayout.NORTH)
            add(JScrollPane(ocrTextArea).apply {
                isFocusable = false
                viewport.background = ocrTextArea.background
            }, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
    }

    private fun buildTranslationPanel(): JPanel {
        translationArea.isEditable = false
        translationArea.lineWrap = true
        translationArea.wrapStyleWord = true
        translationArea.border = BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10))
        translationArea.hint = localizer.getString("ocr_workspace.translation_empty")
        translationArea.accessibleContext.accessibleDescription = translationArea.hint
        sourceCombo.renderer = languageRenderer(); targetCombo.renderer = languageRenderer()
        sourceCombo.minimumSize = Dimension(0, sourceCombo.preferredSize.height)
        targetCombo.minimumSize = Dimension(0, targetCombo.preferredSize.height)
        sourceCombo.toolTipText = localizer.getString("ocr_workspace.source_language")
        targetCombo.toolTipText = localizer.getString("ocr_workspace.target_language")
        configureSecondary(copyTranslationButton, Icons.COPY, "ocr_workspace.copy_translation")
        configureSecondary(listenTranslationButton, Icons.SPEAK, "ocr_workspace.listen")
        translateButton.text = localizer.getString("ocr_workspace.translate")
        swapButton.toolTipText = localizer.getString("main_window_language_bar.swap_languages_tooltip")
        swapButton.accessibleContext.accessibleName = swapButton.toolTipText
        swapButton.putClientProperty("JButton.buttonType", "toolBarButton")
        swapButton.isRequestFocusEnabled = false
        val direction = OcrTranslationControls(sourceCombo, targetCombo, translateButton, swapButton)
        val heading = JPanel(BorderLayout(0, 4)).apply {
            add(JSeparator(), BorderLayout.NORTH)
            add(sectionHeader("ocr_workspace.translation_group", copyTranslationButton, listenTranslationButton), BorderLayout.CENTER)
            add(direction, BorderLayout.SOUTH)
        }
        translationArea.background = UIManager.getColor("TextArea.background")
        return JPanel(BorderLayout(0, 2)).apply {
            preferredSize = Dimension(1, getFontMetrics(font).height * 7)
            add(heading, BorderLayout.NORTH)
            add(JScrollPane(translationArea).apply {
                isFocusable = false
                viewport.background = translationArea.background
            }, BorderLayout.CENTER)
        }
    }

    private fun buildBottomBar(): JPanel {
        val open = JButton(localizer.getString("ocr_workspace.open_in")).apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
            icon = ThemeIcon("Table.descendingSortIcon")
            horizontalTextPosition = SwingConstants.LEADING
            isRequestFocusEnabled = false
        }
        open.addActionListener {
            JPopupMenu().apply {
                add(JMenuItem(localizer.getString("ocr_workspace.send_main")).apply { addActionListener { sendCurrentToMain() } })
                add(JMenuItem(localizer.getString("ocr_workspace.send_quick")).apply { addActionListener { sendCurrentToQuick() } })
            }.show(open, 0, -preferredSize.height)
        }
        statusLabel.border = BorderFactory.createEmptyBorder(0, 10, 0, 10)
        infoLabel.foreground = UIManager.getColor("Label.disabledForeground")
        infoLabel.border = BorderFactory.createEmptyBorder(0, 10, 0, 0)
        return JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createEmptyBorder(2, 5, 2, 5)
            add(
                JPanel(FlowLayout(FlowLayout.LEADING, 6, 0)).apply {
                    isOpaque = false
                    add(busyBar)
                    add(statusLabel)
                },
                BorderLayout.CENTER,
            )
            add(
                JPanel(FlowLayout(FlowLayout.TRAILING, 8, 0)).apply {
                    isOpaque = false
                    add(infoLabel)
                    add(open)
                },
                BorderLayout.LINE_END,
            )
        }
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
        swapButton.addActionListener { swapLanguages() }
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
        imagePanel.onZoomChanged = { zoomLabel.text = "${(it * 100).toInt()}%" }
        imagePanel.onFileDropped = ::openImageFile
        imageScroll.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                // A fitted image stays fitted when the window is resized; manual zoom is left alone.
                if (lastOcrState?.image != null) {
                    imagePanel.refitIfFitted(imageScroll.viewport.extentSize)
                    zoomLabel.text = "${(imagePanel.zoomScale() * 100).toInt()}%"
                }
            }
        })
    }

    private fun registerShortcuts() {
        fun bind(code: Int, modifiers: Int, action: () -> Unit) {
            rootPane.registerKeyboardAction(
                { action() },
                KeyStroke.getKeyStroke(code, modifiers),
                JComponent.WHEN_IN_FOCUSED_WINDOW,
            )
        }
        // Focus ownership is explicit: text editing never loses its chords
        // (notably Ctrl+V for pasting text) to destructive/modal actions.
        fun isEditingText(): Boolean = focusOwner is JTextArea

        fun bindUnlessEditing(code: Int, modifiers: Int, action: () -> Unit) {
            bind(code, modifiers) { if (!isEditingText()) action() }
        }

        val mask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        // Esc: stop a running recognition first; close only when there is nothing to stop.
        bind(java.awt.event.KeyEvent.VK_ESCAPE, 0) {
            if (lastOcrState?.isRecognizing == true) store.cancelOcr() else closeWorkspace()
        }
        // Ctrl/Cmd+Enter mirrors the main window's TRANSLATE binding; safe while editing
        // (plain Enter still inserts a newline).
        bind(java.awt.event.KeyEvent.VK_ENTER, mask) { if (translateButton.isEnabled) translateCurrentText(ocrTextArea.text) }
        // Destructive or modal actions only when the user is not editing text, so edited
        // text is never overwritten by an accidental chord and Ctrl+V is never stolen.
        bindUnlessEditing(java.awt.event.KeyEvent.VK_R, mask) { if (lastOcrState?.canRunOcr == true) store.runOcr() }
        bindUnlessEditing(java.awt.event.KeyEvent.VK_O, mask) { if (!closed) chooseImageFile() }
        bindUnlessEditing(java.awt.event.KeyEvent.VK_S, mask) { if (imagePanel.currentImage() != null) saveCurrentImage() }
        bind(java.awt.event.KeyEvent.VK_0, mask) { imagePanel.fitToWindow(imageScroll.viewport.extentSize) }
        bind(java.awt.event.KeyEvent.VK_1, mask) { imagePanel.actualSize() }
        bind(java.awt.event.KeyEvent.VK_EQUALS, mask) { imagePanel.zoomIn() }
        bind(java.awt.event.KeyEvent.VK_MINUS, mask) { imagePanel.zoomOut() }
        rootPane.defaultButton = recognizeButton
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
        (imageCards.layout as CardLayout).show(imageCards, if (state.hasImage || state.isLoadingImage) "image" else "empty")
        imageControls.isVisible = state.hasImage
        if (previous != null && previous.recognizedText != state.recognizedText) {
            // The translation workspace stores one source string. A new recognition result or
            // an edit makes any previous workspace translation describe different text.
            invalidateWorkspaceTranslation()
        }

        val busy = state.isRecognizing || state.isLoadingImage
        updatingCombos = true
        providerCombo.syncItems(state.providers, state.providers.firstOrNull { it.id == state.providerId })
        providerCombo.isEnabled = state.providers.isNotEmpty() && !busy
        val shownLanguages = if (state.language == LanguageCode.AUTO &&
            LanguageCode.AUTO !in state.languages
        ) {
            // Providers such as System OCR accept AUTO as their default even when their
            // advertised list only names concrete recognition languages.
            state.languages + LanguageCode.AUTO
        } else {
            state.languages
        }
        languageCombo.syncItems(shownLanguages, state.language.takeIf { it in shownLanguages })
        languageCombo.isEnabled = state.languages.isNotEmpty() && !busy
        updatingCombos = false

        recognizeButton.isEnabled = state.canRunOcr
        cancelRecognitionButton.isEnabled = state.isRecognizing
        cancelRecognitionButton.isVisible = state.isRecognizing
        recognizeButton.isVisible = !state.isRecognizing
        recognitionOptions.isEnabled = !state.isRecognizing && !state.isLoadingImage
        val recognitionSummary = listOfNotNull(
            (providerCombo.selectedItem as? OcrProviderInfo)?.name,
            (languageCombo.selectedItem as? LanguageCode)?.getDisplayName(autoDetectLabel = localizer.getString("common.auto_detect")),
        ).joinToString(" · ")
        recognitionOptions.text = recognitionSummary.ifEmpty { localizer.getString("ocr_workspace.recognition_group") }
        recognitionOptions.toolTipText = recognitionSummary
        val translation = mainStore.state.value
        val translatingHere = expectedTranslationInput != null && translation.inputText == expectedTranslationInput && translation.isLoading
        translateButton.isEnabled = state.canTranslate && !state.isRecognizing && !translatingHere
        copyOcrButton.isEnabled = state.hasText
        listenOcrButton.isEnabled = state.hasText
        acquisitionBar.setImageAvailable(state.hasImage)
        copyTranslationButton.isEnabled = translatedTextForCurrent.isNotBlank()
        listenTranslationButton.isEnabled = translatedTextForCurrent.isNotBlank()
        annotationsCheck.isEnabled = state.regions.isNotEmpty()
        annotationsCheck.isSelected = state.showAnnotations && state.regions.isNotEmpty()
        // Accent whichever action is the natural next step; Enter fires it outside text areas.
        rootPane.defaultButton = if (state.hasText && !state.isRecognizing) translateButton else recognizeButton
        imagePanel.setRegions(state.regions)
        imagePanel.setAnnotationsVisible(state.showAnnotations && state.regions.isNotEmpty())

        busyBar.isVisible = busy
        val error = state.error
        statusLabel.text = when {
            state.isLoadingImage -> localizer.getString("ocr_workspace.loading_image")
            state.isRecognizing -> localizer.getString("ocr_workspace.recognizing")
            error != null -> errorText(error)
            state.isEdited && state.hasText -> localizer.getString("ocr_workspace.edited")
            state.hasText || !state.hasImage -> localizer.getString("ocr_workspace.ready")
            else -> localizer.getString("ocr_workspace.no_text")
        }
        ocrTextArea.hint = if (!state.hasText && error == null && !busy) {
            localizer.getString("ocr_workspace.no_text")
        } else statusLabel.text
        ocrTextArea.accessibleContext.accessibleDescription = ocrTextArea.hint
        statusLabel.foreground = when {
            error != null -> errorColor()
            busy -> UIManager.getColor("Label.foreground")
            else -> UIManager.getColor("Label.disabledForeground") ?: UIManager.getColor("Label.foreground")
        }

        if (ocrTextArea.text != state.recognizedText) {
            val caret = ocrTextArea.caretPosition
            updatingOcrText = true
            ocrTextArea.text = state.recognizedText
            ocrTextArea.caretPosition = caret.coerceAtMost(ocrTextArea.text.length)
            updatingOcrText = false
        }
        ocrTextArea.autoOrient()
        updateInfo()
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
        sourceCombo.syncItems(state.availableLanguages, state.sourceLanguage)
        sourceCombo.isEnabled = state.availableLanguages.isNotEmpty()
        val targets = state.availableLanguages.filter { it != LanguageCode.AUTO }
        targetCombo.syncItems(targets, state.targetLanguage)
        targetCombo.isEnabled = targets.isNotEmpty()
        updatingCombos = false
        // Swapping with an auto-detected source would make the target "auto", which is invalid.
        swapButton.isEnabled = state.sourceLanguage != LanguageCode.AUTO && targets.isNotEmpty()

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
                errorColor()
            } else {
                UIManager.getColor("TextArea.foreground")
            }
            translationArea.autoOrient()
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

    private fun swapLanguages() {
        val source = sourceCombo.selectedItem as? LanguageCode ?: return
        val target = targetCombo.selectedItem as? LanguageCode ?: return
        if (source == LanguageCode.AUTO) return
        mainStore.dispatch(MainIntent.SelectSourceLanguage(target))
        mainStore.dispatch(MainIntent.SelectTargetLanguage(source))
        retranslateIfVisible()
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
        // Quick Translate always receives the recognized/edited source text. Sending
        // translatedTextForCurrent here would feed an English target into the same
        // Japanese->English pair and translate it a second time.
        val text = ocrTextArea.text
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
        if (index >= 0) imagePanel.setHighlightedRegion(index, reveal = true)
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
        openImageFile(chooser.selectedFile)
    }

    /** Shared by the file chooser and drag & drop. */
    private fun openImageFile(file: File) {
        val token = claimImageInput() ?: return
        scope.launch {
            val materialized = OcrImageMaterializer.readFile(file)
            withContext(Dispatchers.Swing) {
                if (materialized == null) {
                    failMaterialization(token, OcrWorkspaceError.ImageOpenFailed)
                } else {
                    finishMaterialization(
                        token = token,
                        decoded = materialized.image,
                        image = materialized.data,
                        label = file.name,
                        autoTranslate = false,
                    )
                }
            }
        }
    }

    private fun pasteImage() {
        // Clipboard access stays on the EDT; classification, file reads and decoding do not.
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val transferable = runCatching { clipboard.getContents(null) }.getOrNull()
        if (transferable == null) {
            flashStatus(localizer.getString("ocr_workspace.clipboard_empty"), isError = true)
            return
        }
        if (!DroppedContentClassifier.canAccept(transferable)) {
            // Plain text and other unsupported contents must not discard the current image.
            flashStatus(localizer.getString("ocr_workspace.clipboard_empty"), isError = true)
            return
        }
        val token = claimImageInput() ?: return
        scope.launch {
            val materialized = OcrImageMaterializer.readClipboard(transferable)
            withContext(Dispatchers.Swing) {
                if (materialized == null) {
                    failMaterialization(token, OcrWorkspaceError.ClipboardEmpty)
                } else {
                    finishMaterialization(
                        token = token,
                        decoded = materialized.image,
                        image = materialized.data,
                        label = localizer.getString("ocr_workspace.clipboard_label"),
                        autoTranslate = false,
                    )
                }
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
                    flashStatus(localizer.getString("ocr_workspace.image_saved"), isError = false)
                } else {
                    flashStatus(localizer.getString("ocr_workspace.image_save_failed"), isError = true)
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
        // The recognized text's own provenance, not the dropdown for the next OCR run and not
        // an unrelated main-window detection.
        val ocrLanguage = lastOcrState?.recognizedLanguage ?: LanguageCode.AUTO
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
        OcrWorkspaceError.ImageOpenFailed -> localizer.getString("ocr_workspace.image_open_failed")
        OcrWorkspaceError.ClipboardEmpty -> localizer.getString("ocr_workspace.clipboard_empty")
        is OcrWorkspaceError.Failed -> localizer.getString("ocr_workspace.recognition_failed")
    }

    private fun updateInfo() {
        val dims = imagePanel.currentImage()?.let { "${it.width}\u00D7${it.height}" }
        infoLabel.text = dims ?: " "
    }

    private fun flashStatus(message: String, isError: Boolean) {
        statusLabel.text = message
        statusLabel.foreground = if (isError) errorColor() else UIManager.getColor("Label.foreground")
    }

    private fun errorColor(): Color = UIManager.getColor("Label.errorForeground") ?: Color.RED

    /** Replaces combo items only when they actually changed, so an open popup never flickers. */
    private fun <T> JComboBox<T>.syncItems(items: List<T>, selected: T?) {
        @Suppress("UNCHECKED_CAST")
        val m = model as DefaultComboBoxModel<T>
        val current = (0 until m.size).map(m::getElementAt)
        if (current != items) {
            m.removeAllElements()
            items.forEach(m::addElement)
        }
        if (selectedItem != selected) selectedItem = selected
    }

    /** Mirrors the text direction to the first strongly directional character, like bidi does. */
    private fun JTextArea.autoOrient() {
        val rtl = text.codePoints().toArray().asSequence().firstNotNullOfOrNull { cp ->
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> false
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
                else -> null
            }
        } ?: return
        val target = if (rtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
        if (componentOrientation != target) applyComponentOrientation(target)
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
