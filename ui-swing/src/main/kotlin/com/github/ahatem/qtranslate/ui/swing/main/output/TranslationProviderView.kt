package com.github.ahatem.qtranslate.ui.swing.main.output

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorPopupButton
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelectorState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.createToolbarButton
import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import com.github.ahatem.qtranslate.ui.swing.shared.util.toFont
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DefinitionStrip
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.InlineLoadingBar
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.ComponentOrientation
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.Font
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.UIManager

enum class ProviderRole {
    PRIMARY,
    SECONDARY
}

enum class ProviderPresentation {
    MAIN,
    QUICK
}

enum class ProviderStatus {
    PLACEHOLDER,
    LOADING,
    SUCCESS,
    FAILURE
}

/** The primary provider's status: a blank result is a failure only when the translation said so. */
fun primaryProviderStatus(translatedText: String, isLoading: Boolean, translationFailed: Boolean): ProviderStatus = when {
    translatedText.isNotBlank() -> ProviderStatus.SUCCESS
    isLoading -> ProviderStatus.LOADING
    translationFailed -> ProviderStatus.FAILURE
    else -> ProviderStatus.PLACEHOLDER
}

data class TranslationProviderState(
    val serviceId: String,
    val serviceName: String?,
    val iconPath: String?,
    val role: ProviderRole,
    val presentation: ProviderPresentation,
    val status: ProviderStatus,
    val text: String = "",
    val errorMessage: String? = null,
    val loadingText: String = "",
    val failureText: String = "",
    val copyLabel: String = "",
    /** Label of the control that reveals a secondary failure's error message; empty offers none. */
    val detailsLabel: String = "",
    val listenLabel: String = "",
    val stopLabel: String = "",
    val isTtsPlaying: Boolean = false,
    val primaryLabel: String = "",
    val placeholderTitle: String = "",
    val placeholderSubtitle: String = "",
    val definition: String = "",
    val findInDictionaryLabel: String = "",
    val searchImagesLabel: String = "",
    val setAsInputLabel: String = "",
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val selectorState: TranslatorSelectorState? = null,
    val onCopy: (String) -> Unit = {},
    val onListen: (String) -> Unit = {},
    val onStop: () -> Unit = {},
    val onTranslateRequest: (String) -> Unit = {},
    val onFindInDictionary: ((String) -> Unit)? = null,
    val onSearchImages: ((String) -> Unit)? = null,
    val onSetAsInput: ((String) -> Unit)? = null,
    val getContextMenuLabel: ((String) -> String)? = null
)

/**
 * One provider result: header, body, and optional primary extras.
 *
 * Primary and secondary providers share this skeleton. The primary adds a live
 * translator selector, a thin accent rail on the interface-start edge, primary
 * actions, and its definition; secondaries show identity, status, body, and Copy.
 */
class TranslationProviderView(
    private val iconManager: IconManager?,
    private val selector: TranslatorPopupButton? = null
) : JPanel(BorderLayout()) {
    val serviceId: String get() = currentServiceId

    private var currentServiceId = ""
    private var lastRenderKey: RenderKey? = null
    private var initialized = false

    private var onCopyRef: ((String) -> Unit)? = null
    private var onListenRef: ((String) -> Unit)? = null
    private var onStopRef: (() -> Unit)? = null
    private var onTranslateRequestRef: ((String) -> Unit)? = null
    private var onFindInDictionaryRef: ((String) -> Unit)? = null
    private var onSearchImagesRef: ((String) -> Unit)? = null
    private var onSetAsInputRef: ((String) -> Unit)? = null

    private val rail = JPanel().apply {
        isOpaque = true
        preferredSize = Dimension(UIScale.scale(2), 0)
        minimumSize = preferredSize
        maximumSize = Dimension(UIScale.scale(2), Int.MAX_VALUE)
        isVisible = false
    }

    private val providerIcon = JLabel().apply {
        horizontalAlignment = JLabel.CENTER
    }

    private val providerName = JLabel().apply {
        font = font.deriveFont(font.style or Font.BOLD)
        minimumSize = Dimension(0, preferredSize.height)
    }

    private val primaryTag = JLabel().apply {
        putClientProperty("FlatLaf.styleClass", "small")
        isVisible = false
    }

    private val statusLabel = JLabel().apply {
        putClientProperty("FlatLaf.styleClass", "small")
    }

    private val copyButton = createToolbarButton().apply {
        addActionListener { onCopyRef?.invoke(actionCommand ?: "") }
    }

    private val listenButton = createToolbarButton().apply {
        addActionListener {
            val text = actionCommand ?: ""
            if (isStopMode) onStopRef?.invoke() else onListenRef?.invoke(text)
        }
    }
    private var isStopMode = false

    private val detailsButton = createToolbarButton().apply {
        isVisible = false
        addActionListener { toggleFailureDetails() }
    }

    /** Set while a secondary failure has an error message worth showing; keys the expansion. */
    private var failureDetailsKey: String? = null
    private var expandedDetailsKey: String? = null
    private var compactFailure = false
    private var placeholderHasText = false
    private var isPlaceholderStatus = false

    private val headerIdentity = JPanel(BorderLayout(UIScale.scale(6), 0)).apply {
        isOpaque = false
        add(providerIcon, BorderLayout.LINE_START)
        add(providerName, BorderLayout.CENTER)
        add(primaryTag, BorderLayout.LINE_END)
    }

    private val headerActions = JPanel(FlowLayout(FlowLayout.TRAILING, UIScale.scale(2), 0)).apply {
        isOpaque = false
        // Trailing order: Listen/Stop then Copy, so Copy stays on the outer
        // (trailing) column aligned with secondary providers' Copy.
        // FlowLayout.TRAILING is logical, so RTL mirrors without hard-coding sides.
        add(statusLabel)
        add(detailsButton)
        add(listenButton)
        add(copyButton)
    }

    private val header = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(headerIdentity, BorderLayout.CENTER)
        add(headerActions, BorderLayout.LINE_END)
    }

    private val textPane = AdvancedTextPane(
        onTextChanged = {},
        onTranslateRequest = { text -> onTranslateRequestRef?.invoke(text) },
        onListenRequest = { text -> onListenRef?.invoke(text) }
    ).apply {
        isEditable = false
        isOpaque = false
        isFocusable = true
    }

    private val readableSpacer = JPanel().apply {
        isOpaque = false
        isVisible = false
        preferredSize = Dimension(0, 0)
    }

    private val bodyRow = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(textPane, BorderLayout.CENTER)
        add(readableSpacer, BorderLayout.LINE_END)
    }

    private val placeholderTitle = JLabel()
    private val placeholderSubtitle = JLabel().apply {
        putClientProperty("FlatLaf.styleClass", "small")
    }
    private val placeholderPanel = JPanel(BorderLayout(0, UIScale.scale(2))).apply {
        isOpaque = false
        isVisible = false
        add(placeholderTitle, BorderLayout.NORTH)
        add(placeholderSubtitle, BorderLayout.CENTER)
    }

    private val loadingLabel = JLabel()
    private val loadingBar = InlineLoadingBar()
    private val loadingPanel = JPanel(BorderLayout(0, UIScale.scale(4))).apply {
        isOpaque = false
        isVisible = false
        add(loadingLabel, BorderLayout.NORTH)
        add(loadingBar, BorderLayout.SOUTH)
    }

    private val definitionStrip = DefinitionStrip(showDivider = false)

    private val bodyStack = JPanel(CardLayout()).apply {
        isOpaque = false
        add(bodyRow, BODY_CARD)
        add(placeholderPanel, PLACEHOLDER_CARD)
        add(loadingPanel, LOADING_CARD)
    }

    private val content = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(header, BorderLayout.NORTH)
        add(bodyStack, BorderLayout.CENTER)
        add(definitionStrip, BorderLayout.SOUTH)
    }

    val textPaneComponent: JComponent get() = textPane

    fun requestFocusOnText() = textPane.requestFocusInWindow()

    fun textPaneForTest(): AdvancedTextPane = textPane

    fun readableCapActiveForTest(): Boolean = readableSpacer.isVisible

    fun bodyVisibleForTest(): Boolean = bodyStack.isVisible

    fun detailsButtonForTest(): JButton = detailsButton

    init {
        isOpaque = false
        add(rail, BorderLayout.LINE_START)
        add(content, BorderLayout.CENTER)
        textPane.getContextMenuLabel = { key -> getContextMenuLabelRef(key) }
        textPane.onBeforeContextMenuPopup = { menu, clickPosition ->
            if (onFindInDictionaryRef != null || onSearchImagesRef != null) {
                addFindInDictionaryItem(menu, clickPosition)
            }
            if (onSetAsInputRef != null) addSetAsInputItem(menu)
        }
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = updatePrimaryTagVisibility()
        })
        initialized = true
    }

    private var getContextMenuLabelRef: ((String) -> String) = { it }
    private var findInDictionaryLabelRef = ""
    private var searchImagesLabelRef = ""
    private var setAsInputLabelRef = ""

    private var dictMenuItem: JMenuItem? = null
    private var dictMenuSeparator: JSeparator? = null
    private var imageMenuItem: JMenuItem? = null
    private var setAsInputMenuItem: JMenuItem? = null
    private var setAsInputSeparator: JSeparator? = null

    fun render(state: TranslationProviderState) {
        currentServiceId = state.serviceId
        onCopyRef = state.onCopy
        onListenRef = state.onListen
        onStopRef = state.onStop
        onTranslateRequestRef = state.onTranslateRequest
        onFindInDictionaryRef = state.onFindInDictionary
        onSearchImagesRef = state.onSearchImages
        onSetAsInputRef = state.onSetAsInput
        state.getContextMenuLabel?.let { getContextMenuLabelRef = it }
        findInDictionaryLabelRef = state.findInDictionaryLabel
        searchImagesLabelRef = state.searchImagesLabel
        setAsInputLabelRef = state.setAsInputLabel

        val key = RenderKey(
            serviceId = state.serviceId,
            serviceName = state.serviceName,
            iconPath = state.iconPath,
            role = state.role,
            presentation = state.presentation,
            status = state.status,
            text = if (state.status == ProviderStatus.SUCCESS || state.status == ProviderStatus.FAILURE) state.text else "",
            errorMessage = state.errorMessage,
            loadingText = state.loadingText,
            failureText = state.failureText,
            copyLabel = state.copyLabel,
            detailsLabel = state.detailsLabel,
            listenLabel = state.listenLabel,
            stopLabel = state.stopLabel,
            isTtsPlaying = state.isTtsPlaying,
            primaryLabel = state.primaryLabel,
            placeholderTitle = state.placeholderTitle,
            placeholderSubtitle = state.placeholderSubtitle,
            definition = state.definition,
            findInDictionaryLabel = state.findInDictionaryLabel,
            searchImagesLabel = state.searchImagesLabel,
            setAsInputLabel = state.setAsInputLabel,
            fontConfig = state.fontConfig,
            fallbackFontConfig = state.fallbackFontConfig,
            selectorState = state.selectorState
        )
        val keyChanged = key != lastRenderKey
        lastRenderKey = key
        if (!keyChanged) return

        applyPresentation(state)
        renderHeader(state)
        renderBody(state)
        definitionStrip.render(if (state.role == ProviderRole.PRIMARY) state.definition else "")
        revalidate()
        repaint()
    }

    private fun applyPresentation(state: TranslationProviderState) {
        val quick = state.presentation == ProviderPresentation.QUICK
        val headerPad = if (quick) 8 else 12
        header.border = BorderFactory.createEmptyBorder(
            UIScale.scale(6),
            UIScale.scale(headerPad),
            UIScale.scale(6),
            UIScale.scale(if (quick) 8 else 6)
        )
        val bodyPad = if (quick) 8 else 12
        val bodyBottom = if (quick) 8 else 10
        bodyStack.border = BorderFactory.createEmptyBorder(
            UIScale.scale(2),
            UIScale.scale(bodyPad),
            UIScale.scale(bodyBottom),
            UIScale.scale(bodyPad)
        )
        val placeholderPad = if (quick) 8 else 12
        placeholderPanel.border = BorderFactory.createEmptyBorder(
            0, UIScale.scale(placeholderPad), UIScale.scale(4), UIScale.scale(placeholderPad)
        )
        loadingPanel.border = BorderFactory.createEmptyBorder(
            0, UIScale.scale(bodyPad), UIScale.scale(6), UIScale.scale(bodyPad)
        )
        textPane.margin = Insets(0, 0, 0, 0)
        providerIcon.preferredSize = Dimension(UIScale.scale(16), UIScale.scale(16))
        rail.isVisible = state.role == ProviderRole.PRIMARY
        if (rail.isVisible) refreshRail()
    }

    private fun renderHeader(state: TranslationProviderState) {
        val displayName = state.serviceName ?: ""
        val hasSelector = state.role == ProviderRole.PRIMARY && selector != null

        if (hasSelector) {
            // The selector owns the single service identity (icon + name + chevron);
            // a separate provider icon beside it would duplicate the identity.
            providerIcon.icon = null
            providerIcon.isVisible = false
            providerName.isVisible = false
            selector.textMode = true
            selector.isVisible = true
            state.selectorState?.let(selector::render)
            if (selector.parent !== headerIdentity) {
                headerIdentity.remove(providerName)
                headerIdentity.add(selector, BorderLayout.CENTER)
            }
        } else {
            providerIcon.icon = state.iconPath?.let { path ->
                iconManager?.getIcon(state.serviceId, path, UIScale.scale(16), UIScale.scale(16))
            }
            providerIcon.isVisible = providerIcon.icon != null
            selector?.takeIf { it.parent === headerIdentity }?.let {
                headerIdentity.remove(it)
                headerIdentity.add(providerName, BorderLayout.CENTER)
            }
            providerName.isVisible = true
            providerName.text = displayName
        }

        primaryTag.text = state.primaryLabel
        primaryTag.foreground = UIManager.getColor("Label.disabledForeground")
            ?: UIManager.getColor("Label.foreground")
        primaryTagWanted = state.role == ProviderRole.PRIMARY && state.primaryLabel.isNotBlank()
        primaryTag.isVisible = primaryTagWanted
        updatePrimaryTagVisibility()

        when (state.status) {
            ProviderStatus.LOADING -> {
                statusLabel.text = state.loadingText
                statusLabel.icon = null
                statusLabel.foreground = UIManager.getColor("Label.disabledForeground")
            }
            ProviderStatus.FAILURE -> {
                statusLabel.text = state.failureText
                // Inline failure state: standard action-size warning glyph, never the
                // dialog-scale OptionPane error icon (which grows the header).
                // Text-only when no icon manager is available (e.g. tests).
                statusLabel.icon = iconManager?.getIcon(Icons.WARNING, UIScale.scale(16), UIScale.scale(16))
                statusLabel.foreground = UIManager.getColor("Component.error.focusedBorderColor")
                    ?: UIManager.getColor("Component.error.foreground")
                    ?: UIManager.getColor("Label.disabledForeground")
            }
            else -> {
                statusLabel.text = ""
                statusLabel.icon = null
            }
        }

        val isQuickPrimary = state.role == ProviderRole.PRIMARY &&
            state.presentation == ProviderPresentation.QUICK
        val bodyText = if (state.status == ProviderStatus.FAILURE) {
            state.errorMessage?.takeIf { it.isNotBlank() } ?: state.failureText
        } else state.text
        copyButton.actionCommand = bodyText
        copyButton.toolTipText = state.copyLabel
        copyButton.icon = iconManager?.getIcon(Icons.COPY, UIScale.scale(16), UIScale.scale(16))
        copyButton.isVisible = !isQuickPrimary &&
            state.status == ProviderStatus.SUCCESS && state.text.isNotBlank()

        if (state.role == ProviderRole.PRIMARY && !isQuickPrimary) {
            isStopMode = state.isTtsPlaying
            listenButton.actionCommand = state.text
            listenButton.icon = iconManager?.getIcon(
                if (state.isTtsPlaying) Icons.CLOSE else Icons.SPEAK,
                UIScale.scale(16), UIScale.scale(16)
            )
            listenButton.toolTipText = if (state.isTtsPlaying) state.stopLabel else state.listenLabel
            listenButton.isVisible =
                state.isTtsPlaying || (state.status == ProviderStatus.SUCCESS && state.text.isNotBlank())
            listenButton.isEnabled = state.isTtsPlaying || state.text.isNotBlank()
        } else {
            listenButton.isVisible = false
        }
    }

    private fun renderBody(state: TranslationProviderState) {
        placeholderHasText = state.placeholderTitle.isNotBlank() || state.placeholderSubtitle.isNotBlank()
        renderFailureDetails(state)
        // All three bodies stay mounted under a CardLayout; only the active
        // card is shown, so the text component, its selection, and focus
        // survive state transitions.
        (bodyStack.layout as CardLayout).show(
            bodyStack, when (state.status) {
                ProviderStatus.PLACEHOLDER -> PLACEHOLDER_CARD
                ProviderStatus.LOADING -> LOADING_CARD
                else -> BODY_CARD
            }
        )

        when (state.status) {
            ProviderStatus.PLACEHOLDER -> {
                placeholderTitle.text = state.placeholderTitle
                placeholderTitle.foreground = UIManager.getColor("Label.disabledForeground")
                placeholderSubtitle.text = state.placeholderSubtitle
                placeholderSubtitle.foreground = UIManager.getColor("Label.disabledForeground")
            }
            ProviderStatus.LOADING -> {
                loadingLabel.text = state.loadingText
                loadingLabel.foreground = UIManager.getColor("Label.disabledForeground")
                loadingBar.isLoading = true
            }
            ProviderStatus.SUCCESS -> {
                loadingBar.isLoading = false
                textPane.componentOrientation = if (state.text.isRTL()) {
                    ComponentOrientation.RIGHT_TO_LEFT
                } else {
                    ComponentOrientation.LEFT_TO_RIGHT
                }
                textPane.render(state.text, emptyList(), isEditable = false)
                textPane.updateFontsAndRescanDocument(
                    state.fontConfig.toFont(), state.fallbackFontConfig.toFont()
                )
            }
            ProviderStatus.FAILURE -> {
                loadingBar.isLoading = false
                val message = state.errorMessage?.takeIf { it.isNotBlank() } ?: state.failureText
                textPane.componentOrientation = if (message.isRTL()) {
                    ComponentOrientation.RIGHT_TO_LEFT
                } else {
                    ComponentOrientation.LEFT_TO_RIGHT
                }
                textPane.render(message, emptyList(), isEditable = false)
                textPane.updateFontsAndRescanDocument(
                    state.fontConfig.toFont(), state.fallbackFontConfig.toFont()
                )
            }
        }
        updateReadableCap()
    }

    /**
     * A failed secondary is compact: its header already says it failed, so the body stays hidden
     * until the error message is asked for. The primary's failure is never compacted.
     * Expansion is local to this view and lapses when the provider, its message or its status
     * changes, which covers a new translation.
     */
    private fun renderFailureDetails(state: TranslationProviderState) {
        isPlaceholderStatus = state.status == ProviderStatus.PLACEHOLDER
        compactFailure = state.role == ProviderRole.SECONDARY && state.status == ProviderStatus.FAILURE
        val message = state.errorMessage?.takeIf { it.isNotBlank() && it != state.failureText }
        failureDetailsKey = if (compactFailure && message != null && state.detailsLabel.isNotBlank()) {
            "${state.serviceId}|$message"
        } else null
        if (expandedDetailsKey != failureDetailsKey) expandedDetailsKey = null

        detailsButton.isVisible = failureDetailsKey != null
        detailsButton.text = state.detailsLabel
        detailsButton.toolTipText = state.detailsLabel
        updateBodyVisibility()
    }

    private fun toggleFailureDetails() {
        expandedDetailsKey = if (expandedDetailsKey == null) failureDetailsKey else null
        updateBodyVisibility()
        revalidate()
        repaint()
    }

    private fun updateBodyVisibility() {
        val expanded = expandedDetailsKey != null && expandedDetailsKey == failureDetailsKey
        detailsButton.isSelected = expanded
        bodyStack.isVisible = when {
            compactFailure -> expanded
            // A placeholder with no text of its own (the Comparison board draws that state itself)
            // leaves the provider as its header alone.
            isPlaceholderStatus -> placeholderHasText
            else -> true
        }
    }

    private var primaryTagWanted = false

    private fun updatePrimaryTagVisibility() {
        if (!primaryTagWanted) return
        val hideForWidth = width > 0 && width < UIScale.scale(300)
        val wanted = !hideForWidth
        if (primaryTag.isVisible != wanted) {
            primaryTag.isVisible = wanted
            headerIdentity.revalidate()
        }
    }

    override fun doLayout() {
        super.doLayout()
        updateReadableCap()
    }

    private var lastReadableCap: Int = -1

    private fun updateReadableCap() {
        val primaryMain = lastRenderKey?.role == ProviderRole.PRIMARY &&
            lastRenderKey?.presentation == ProviderPresentation.MAIN
        if (!primaryMain || bodyRow.width <= 0) {
            if (readableSpacer.isVisible) {
                readableSpacer.isVisible = false
                readableSpacer.preferredSize = Dimension(0, 0)
            }
            lastReadableCap = -1
            return
        }
        val cap = UIScale.scale(READABLE_BODY_MAX)
        val want = (bodyRow.width - cap).coerceAtLeast(0)
        if (want != lastReadableCap) {
            lastReadableCap = want
            readableSpacer.isVisible = want > 0
            readableSpacer.preferredSize = Dimension(want, 0)
            revalidate()
        }
    }

    private fun refreshRail() {
        rail.background = UIManager.getColor("Component.focusedBorderColor")
            ?: UIManager.getColor("Component.accentColor")
            ?: UIManager.getColor("Label.foreground")
    }

    override fun updateUI() {
        super.updateUI()
        if (initialized) {
            refreshRail()
            primaryTag.foreground = UIManager.getColor("Label.disabledForeground")
                ?: UIManager.getColor("Label.foreground")
        }
    }

    /** Test accessors: header controls without pixel assertions. */
    fun copyButtonForTest(): JButton = copyButton
    fun listenButtonForTest(): JButton = listenButton
    fun providerIconForTest(): JLabel = providerIcon
    fun providerNameForTest(): JLabel = providerName
    fun primaryTagForTest(): JLabel = primaryTag
    fun statusLabelForTest(): JLabel = statusLabel
    fun headerActionsForTest(): JPanel = headerActions
    fun selectorForTest(): TranslatorPopupButton? = selector

    private fun addFindInDictionaryItem(menu: JPopupMenu, clickPosition: Point) {
        dictMenuItem?.let(menu::remove)
        dictMenuSeparator?.let(menu::remove)
        imageMenuItem?.let(menu::remove)
        dictMenuItem = null
        dictMenuSeparator = null
        imageMenuItem = null

        val clickOffset = textPane.viewToModel(clickPosition)
        val word = (textPane.selectedText?.trim() ?: "")
            .takeIf { it.isNotBlank() && !it.contains(' ') }
            ?: wordAtOffset(clickOffset)
        if (word.isEmpty()) return

        val sep = JSeparator()
        dictMenuSeparator = sep
        menu.add(sep)

        onFindInDictionaryRef?.let { lookup ->
            if (findInDictionaryLabelRef.isNotBlank()) {
                dictMenuItem = JMenuItem(findInDictionaryLabelRef).apply {
                    addActionListener { lookup(word) }
                }.also(menu::add)
            }
        }
        onSearchImagesRef?.let { search ->
            if (searchImagesLabelRef.isNotBlank()) {
                imageMenuItem = JMenuItem(searchImagesLabelRef).apply {
                    addActionListener { search(word) }
                }.also(menu::add)
            }
        }
    }

    private fun addSetAsInputItem(menu: JPopupMenu) {
        setAsInputMenuItem?.let(menu::remove)
        setAsInputSeparator?.let(menu::remove)
        setAsInputMenuItem = null
        setAsInputSeparator = null
        if (setAsInputLabelRef.isBlank()) return

        val text = (textPane.selectedText?.trim()?.takeIf { it.isNotBlank() }
            ?: textPane.text?.trim()).takeIf { !it.isNullOrBlank() } ?: return

        val sep = JSeparator()
        val item = JMenuItem(setAsInputLabelRef).apply {
            addActionListener { onSetAsInputRef?.invoke(text) }
        }
        setAsInputSeparator = sep
        setAsInputMenuItem = item
        menu.add(sep)
        menu.add(item)
    }

    private fun wordAtOffset(offset: Int): String {
        val text = textPane.text ?: return ""
        if (offset < 0 || offset >= text.length) return ""
        var start = offset
        var end = offset
        while (start > 0 && text[start - 1].isLetterOrDigit()) start--
        while (end < text.length && text[end].isLetterOrDigit()) end++
        return text.substring(start, end)
    }

    private data class RenderKey(
        val serviceId: String,
        val serviceName: String?,
        val iconPath: String?,
        val role: ProviderRole,
        val presentation: ProviderPresentation,
        val status: ProviderStatus,
        val text: String,
        val errorMessage: String?,
        val loadingText: String,
        val failureText: String,
        val copyLabel: String,
        val detailsLabel: String,
        val listenLabel: String,
        val stopLabel: String,
        val isTtsPlaying: Boolean,
        val primaryLabel: String,
        val placeholderTitle: String,
        val placeholderSubtitle: String,
        val definition: String,
        val findInDictionaryLabel: String,
        val searchImagesLabel: String,
        val setAsInputLabel: String,
        val fontConfig: FontConfig,
        val fallbackFontConfig: FontConfig,
        val selectorState: TranslatorSelectorState?
    )

    companion object {
        const val READABLE_BODY_MAX = 760
        const val WIDE_BOARD_MIN = 880
        const val WIDE_BOARD_RELEASE = 848
        const val MEDIUM_BOARD_MIN = 640
        private const val BODY_CARD = "body"
        private const val PLACEHOLDER_CARD = "placeholder"
        private const val LOADING_CARD = "loading"
    }
}
