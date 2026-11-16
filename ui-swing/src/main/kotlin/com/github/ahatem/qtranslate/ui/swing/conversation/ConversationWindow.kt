package com.github.ahatem.qtranslate.ui.swing.conversation

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.conversation.AudioApplication
import com.github.ahatem.qtranslate.core.conversation.AudioBackend
import com.github.ahatem.qtranslate.core.conversation.AudioDevice
import com.github.ahatem.qtranslate.core.conversation.AudioLevels
import com.github.ahatem.qtranslate.core.conversation.AudioSource
import com.github.ahatem.qtranslate.core.conversation.ConversationFeature
import com.github.ahatem.qtranslate.core.conversation.ConversationSession
import com.github.ahatem.qtranslate.core.conversation.DeviceCheckHeuristics
import com.github.ahatem.qtranslate.core.conversation.LiveRecognitionStatus
import com.github.ahatem.qtranslate.core.conversation.RecorderNotice
import com.github.ahatem.qtranslate.core.conversation.RecorderPhase
import com.github.ahatem.qtranslate.core.conversation.RecorderState
import com.github.ahatem.qtranslate.core.conversation.RecordingOptions
import com.github.ahatem.qtranslate.core.conversation.SessionStatus
import com.github.ahatem.qtranslate.core.conversation.StreamState
import com.github.ahatem.qtranslate.core.conversation.StreamStatus
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.core.conversation.TranscriptSegment
import com.github.ahatem.qtranslate.core.conversation.formatTimestamp
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.text.DefaultHighlighter

/**
 * Conversation Sessions: records a call (your microphone and the call's audio as two streams),
 * shows the live transcript and translates any word you point at.
 *
 * Left: saved sessions with search. Right: device and language pickers, the record controls with
 * a status light per stream, and the transcript. Hovering a word shows its translation and the
 * sentence's; selecting a phrase translates the phrase; clicking a speaker label replays that
 * stretch of audio.
 */
class ConversationWindow(
    private val feature: ConversationFeature,
    private val strings: ConversationStrings,
    /** Languages the active translator offers, for the target picker. */
    private val languages: () -> List<LanguageCode>,
    /** The main window's target language, used until the user picks one here. */
    private val defaultTarget: () -> LanguageCode,
    private val isRtl: () -> Boolean,
    /** Opens Settings on the Plugins page, where Speech Recognition is configured. */
    private val onOpenSpeechSettings: () -> Unit = {}
) : JFrame(strings.title) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val recorder get() = feature.recorder

    /** A device picker entry; [device] null is the "None" choice. */
    private data class DeviceItem(val device: AudioDevice?, val label: String, val app: AudioApplication? = null) {
        override fun toString() = label
    }

    private val microphoneCombo = JComboBox<DeviceItem>()
    private val systemCombo = JComboBox<DeviceItem>()
    private val deviceModeButton = JToggleButton(strings.captureModeDevice)
    private val appModeButton = JToggleButton(strings.captureModeApp)
    private val captureModeGroup = ButtonGroup().apply {
        add(deviceModeButton)
        add(appModeButton)
    }
    private var isAppMode = false
    private var cachedOutputs = emptyList<AudioDevice>()
    private var cachedApps = emptyList<AudioApplication>()

    private val refreshDevicesButton = JButton(FlatSVGIcon(Icons.RETRY, 14, 14)).apply {
        toolTipText = strings.refreshDevices
        putClientProperty("JButton.buttonType", "toolBarButton")
        margin = Insets(2, 6, 2, 6)
    }
    private val myLanguageCombo = JComboBox<LanguageCode>()
    private val callLanguageCombo = JComboBox<LanguageCode>()
    private val targetCombo = JComboBox<LanguageCode>()
    private val keepAudioCheck = JCheckBox(strings.keepAudio).apply { toolTipText = strings.keepAudioTooltip }
    private val chatLayoutCheck = JCheckBox(strings.chatLayout, true).apply { toolTipText = strings.chatLayoutTooltip }

    private val recordButton = JButton()
    private val stopButton = JButton("■ ${strings.stop}")
    private val timerLabel = JLabel("0:00")
    private val microphoneLight = StreamLight(strings.speakerMe, AudioSource.MICROPHONE)
    private val systemLight = StreamLight(strings.speakerCall, AudioSource.SYSTEM)
    private val pendingLabel = JLabel()
    private val retryFailedButton = JButton()
    private val speechLabel = JLabel()
    private val speechSettingsButton = JButton(strings.speechSettings)

    private var fillingLanguages = false

    private val testDevicesButton = JButton(strings.testDevices)
    private val meGroup = JPanel()
    private val themGroup = JPanel()
    private val translateGroup = JPanel()
    private val row2Audio = JPanel()

    private fun relayoutAudioRow(windowWidth: Int) {
        row2Audio.removeAll()
        if (windowWidth >= 1000) {
            row2Audio.layout = MigLayout("fillx, insets 0, gap 10", "[grow,fill][grow,fill][grow,fill]")
            row2Audio.add(meGroup, "growx")
            row2Audio.add(themGroup, "growx")
            row2Audio.add(translateGroup, "growx")
        } else {
            row2Audio.layout = MigLayout("fillx, wrap 1, insets 0, gap 6", "[grow,fill]")
            row2Audio.add(meGroup, "growx")
            row2Audio.add(themGroup, "growx")
            row2Audio.add(translateGroup, "growx")
        }
        row2Audio.revalidate()
        row2Audio.repaint()
    }
    private val deviceWarningText = wrappingText("")
    internal val deviceWarningBar: JPanel by lazy {
        banner(deviceWarningText, UIManager.getColor("Actions.Yellow")) {
            deviceWarningDismissed = true
            deviceWarningBar.isVisible = false
        }.apply { isVisible = false }
    }
    internal var deviceWarningDismissed = false
    private var testingDevices = false

    private val noticeText = wrappingText("")
    internal val noticeBar: JPanel by lazy {
        banner(noticeText, UIManager.getColor("Actions.Red")) {
            recorder.clearNotice()
            noticeBar.isVisible = false
        }.apply { isVisible = false }
    }
    internal val privacyBar: JPanel by lazy {
        banner(wrappingText(strings.privacyNotice), UIManager.getColor("Actions.Yellow")) {
            feature.preferences = feature.preferences.copy(privacyNoticeDismissed = true)
            privacyBar.isVisible = false
        }
    }

    internal val autoTranslateCheck = JCheckBox(strings.autoTranslate, false).apply {
        toolTipText = strings.autoTranslateTooltip
        addActionListener {
            feature.preferences = feature.preferences.copy(autoTranslate = isSelected)
        }
    }

    private val sessionsModel = DefaultListModel<ConversationSession>()
    private val sessionsList = JList(sessionsModel)
    private val searchField = JTextField()
    private val newButton = JButton("+ ${strings.newSession}")
    private val renameButton = JButton(strings.rename)
    private val exportButton = JButton(strings.export)
    private val deleteButton = JButton(strings.delete)

    internal val transcript: TranscriptSurface = TranscriptListView.createSurface(strings, defaultCardList = true)
    internal val liveSlotsPanel = LiveSlotsPanel(strings)
    private val transcriptInnerPanel = JPanel(BorderLayout())
    private val micMuteButton = JButton(FlatSVGIcon(Icons.MIC, 15, 15)).apply {
        toolTipText = strings.micUnmutedTooltip
        putClientProperty("JButton.buttonType", "toolBarButton")
        margin = Insets(2, 6, 2, 6)
        addActionListener {
            recorder.isMicrophoneMuted = !recorder.isMicrophoneMuted
            val muted = recorder.isMicrophoneMuted
            toolTipText = if (muted) strings.micMutedTooltip else strings.micUnmutedTooltip
            (icon as? FlatSVGIcon)?.colorFilter = if (muted) FlatSVGIcon.ColorFilter { Color(0xE05555) } else null
            repaint()
        }
    }
    private val callMuteButton = JButton(FlatSVGIcon(Icons.SPEAK, 15, 15)).apply {
        toolTipText = strings.callUnmutedTooltip
        putClientProperty("JButton.buttonType", "toolBarButton")
        margin = Insets(2, 6, 2, 6)
        addActionListener {
            recorder.isSystemMuted = !recorder.isSystemMuted
            val muted = recorder.isSystemMuted
            toolTipText = if (muted) strings.callMutedTooltip else strings.callUnmutedTooltip
            (icon as? FlatSVGIcon)?.colorFilter = if (muted) FlatSVGIcon.ColorFilter { Color(0xE05555) } else null
            repaint()
        }
    }
    private val transcriptScroll = JScrollPane(transcript.asComponent)
    private val orderButton = JButton(strings.orderNewestAtTop).apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        toolTipText = strings.orderTooltip
    }
    internal val pausePill = JButton().apply {
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        putClientProperty("JButton.buttonType", "roundRect")
        putClientProperty("FlatLaf.styleClass", "small")
        background = UIManager.getColor("Notifications.background")
            ?: UIManager.getColor("ToolTip.background")
            ?: Color(0x2D333B)
        foreground = UIManager.getColor("Actions.Yellow")
            ?: UIManager.getColor("Actions.Blue")
            ?: Color(0xE5C07B)
        isOpaque = true
        margin = Insets(4, 12, 4, 12)
        isVisible = false
        addActionListener { flushBufferedUpdates() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                renderGate.onPointerEntered()
                freezeGraceTimer.stop()
            }
            override fun mouseExited(e: MouseEvent) {
                syncRenderGateState()
                checkAndFlushIfIdle()
            }
        })
    }
    internal val transcriptOverlayPane = TranscriptOverlayPane(
        transcriptScroll,
        pausePill,
        banners = listOf(privacyBar, deviceWarningBar, noticeBar)
    )
    private val popup = HoverTranslationPopup(this, strings)

    /** Gate deciding whether transcript DOM updates are frozen while reading/hovering. */
    private val renderGate = RenderGate<ConversationSession?>()
    private val freezeGraceTimer = Timer((RenderGate.DEFAULT_GRACE_MILLIS + 25).toInt()) { checkAndFlushIfIdle() }.apply { isRepeats = false }

    /** The saved session on screen when nothing is recording. */
    private var viewed: ConversationSession? = null
    private var lastPhase = RecorderPhase.IDLE
    private var loadingDevices = false

    // Hover state: which word the pointer rests on, and the timers that delay showing and hiding
    // the card so sweeping across text does not flash a card per word.
    private var hoverRange: IntRange? = null
    private var hoverSpan: TranscriptView.SegmentSpan? = null
    private var hoverCard = false
    private var popupHovered = false
    private var popupJob: Job? = null
    private var popupContent: HoverTranslationPopup.Content? = null
    private var activePopupSegment: TranscriptSegment? = null
    private var activePopupWordRange: Pair<Long, Long>? = null
    private val showTimer = Timer(SHOW_DELAY_MILLIS) { showWordCard() }.apply { isRepeats = false }
    private val hideTimer = Timer(HIDE_DELAY_MILLIS) { if (!popupHovered) hideCard() }.apply { isRepeats = false }
    private val searchTimer = Timer(250) { reloadSessions() }.apply { isRepeats = false }

    init {
        com.github.ahatem.qtranslate.ui.swing.shared.util.AppIcons.applyTo(this)
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(780, 480)
        // Never larger than the screen it opens on: a 1080p laptop at 150% has ~1280x700 to give.
        val screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        size = Dimension(minOf(1120, screen.width - 40), minOf(740, screen.height - 40))
        setLocationRelativeTo(null)

        contentPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildSidebar(), buildMain()).apply {
            dividerLocation = 230
            resizeWeight = 0.0
            border = null
        }
        wireEvents()
        scope.launch { recorder.state.collect(::render) }
        scope.launch {
            feature.player.playing.collect { tag ->
                withContext(Dispatchers.Swing) {
                    transcript.playingTag = tag
                    updateHoverPopupPlaying(tag)
                }
            }
        }
        rootPane.registerKeyboardAction(
            { feature.stop() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )
        addComponentListener(object : ComponentAdapter() {
            private var lastW = -1
            override fun componentResized(e: ComponentEvent) {
                val w = width
                val isWide = w >= 1000
                val wasWide = lastW >= 1000
                if (lastW < 0 || isWide != wasWide) {
                    lastW = w
                    relayoutAudioRow(w)
                }
            }
        })
    }

    /** Brings the window up, refreshing devices and sessions. */
    fun open() {
        if (!isVisible) {
            val rtl = isRtl()
            applyComponentOrientation(if (rtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT)
            // The transcript keeps its own direction per paragraph, whatever the UI language.
            transcript.asComponent.componentOrientation = ComponentOrientation.LEFT_TO_RIGHT
            transcript.rtlLayout = rtl
            loadPreferences()
            loadDevices()
            reloadSessions()
        }
        isVisible = true
        state = NORMAL
        toFront()
    }

    override fun dispose() {
        popup.dispose()
        scope.cancel()
        super.dispose()
    }

    // ---------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------

    // Wide enough for its three buttons; the split takes any shortfall from the transcript side.
    private fun buildSidebar(): JPanel = shrinkable(MigLayout("fill, wrap 1, insets 10, gap 6", "[grow,fill]", "[][][grow,fill][]"), minWidth = 220).apply {
        searchField.putClientProperty("JTextField.placeholderText", strings.searchPlaceholder)
        searchField.putClientProperty("JTextField.showClearButton", true)
        sessionsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionsList.cellRenderer = SessionCellRenderer()
        add(newButton)
        add(searchField)
        add(JScrollPane(sessionsList))
        add(shrinkable(MigLayout("insets 0, gap 4, fillx", "[grow,fill][grow,fill][grow,fill]")).apply {
            listOf(renameButton, exportButton, deleteButton).forEach { button ->
                button.margin = java.awt.Insets(2, 6, 2, 6)
                add(button, "wmin 40")
            }
        })
    }

    private fun buildMain(): JPanel = JPanel(BorderLayout()).apply {
        listOf(microphoneCombo, systemCombo).forEach { it.prototypeDisplayValue = DeviceItem(null, "SteelSeries Sonar - Chat (Virtual)") }

        // Row 1 — session
        val row1Session = shrinkable(MigLayout("fillx, insets 6 12 4 12, gap 8", "[][][][][grow,fill]push[][][][]")).apply {
            recordButton.putClientProperty("JButton.buttonType", "default")
            add(recordButton)
            add(stopButton)
            add(timerLabel.apply { font = font.deriveFont(font.size2D + 4f) })
            add(pendingLabel)
            add(retryFailedButton)
            add(speechLabel, "wmin 0")
            add(microphoneLight, "wmin 0")
            add(systemLight, "wmin 0")
            add(speechSettingsButton)
        }

        // Row 2 — audio (ME, THEM, TRANSLATE blocks)
        meGroup.apply {
            isOpaque = false
            layout = MigLayout("insets 0, gap 4", "[][grow,fill][grow,fill]")
            val label = JLabel(strings.speakerMe.uppercase() + ":").apply { font = font.deriveFont(Font.BOLD) }
            val micPanel = JPanel(BorderLayout(2, 0)).apply {
                isOpaque = false
                add(microphoneCombo, BorderLayout.CENTER)
                add(micMuteButton, BorderLayout.EAST)
            }
            add(label)
            add(micPanel, "wmin 80, growx")
            add(myLanguageCombo, "wmin 70, growx")
        }

        themGroup.apply {
            isOpaque = false
            layout = MigLayout("insets 0, gap 4", "[][grow,fill][grow,fill]")
            val label = JLabel(strings.speakerCall.uppercase() + ":").apply {
                font = font.deriveFont(Font.BOLD)
                toolTipText = strings.callAudioTooltip
            }
            val callPanel = JPanel(BorderLayout(2, 0)).apply {
                isOpaque = false
                add(systemCombo.apply { toolTipText = strings.callAudioTooltip }, BorderLayout.CENTER)
                val modeAndMute = JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0)).apply {
                    isOpaque = false
                    add(deviceModeButton.apply {
                        putClientProperty("JButton.buttonType", "toolBarButton")
                        toolTipText = strings.captureModeTooltip
                        margin = Insets(1, 3, 1, 3)
                        font = font.deriveFont((font.size2D - 2f).coerceAtLeast(10f))
                    })
                    add(appModeButton.apply {
                        putClientProperty("JButton.buttonType", "toolBarButton")
                        toolTipText = strings.captureModeTooltip
                        margin = Insets(1, 3, 1, 3)
                        font = font.deriveFont((font.size2D - 2f).coerceAtLeast(10f))
                    })
                    add(callMuteButton)
                }
                add(modeAndMute, BorderLayout.EAST)
            }
            add(label)
            add(callPanel, "wmin 90, growx")
            add(callLanguageCombo, "wmin 70, growx")
        }

        translateGroup.apply {
            isOpaque = false
            layout = MigLayout("insets 0, gap 4", "[][grow,fill][][][]")
            val label = JLabel(strings.translateTo.uppercase() + ":").apply { font = font.deriveFont(Font.BOLD) }
            add(label)
            add(targetCombo, "wmin 80, growx")
            add(autoTranslateCheck)
            add(testDevicesButton)
            add(refreshDevicesButton)
        }

        relayoutAudioRow(width.takeIf { it > 0 } ?: 1120)

        // Row 3 — view & privacy options
        val row3View = shrinkable(MigLayout("fillx, insets 2 12 4 12, gap 10", "[]push[][][]")).apply {
            add(keepAudioCheck, "wmin 0")
            add(chatLayoutCheck, "wmin 0")
            add(orderButton)
        }

        val top = JPanel(MigLayout("fillx, wrap 1, insets 0, gap 0", "[grow,fill]")).apply {
            add(row1Session)
            add(row2Audio, "gapleft 12, gapright 12, gaptop 2, gapbottom 2")
            add(row3View)
        }
        add(top, BorderLayout.NORTH)
        transcriptInnerPanel.apply {
            add(liveSlotsPanel, BorderLayout.NORTH)
            add(transcriptOverlayPane, BorderLayout.CENTER)
        }
        val bg = UIManager.getColor("TextPane.background") ?: Color(0x1e1e1e)
        transcriptScroll.viewport.background = bg
        transcriptScroll.background = bg
        transcriptScroll.border = BorderFactory.createMatteBorder(1, 0, 1, 0, UIManager.getColor("Component.borderColor"))
        transcriptScroll.verticalScrollBar.unitIncrement = 16
        add(transcriptInnerPanel, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(JLabel(strings.interactionHint).apply {
                foreground = UIManager.getColor("Label.disabledForeground")
                border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
            }, BorderLayout.CENTER)
        }, BorderLayout.SOUTH)
    }

    /**
     * A panel that never asks for more width than it is given. MigLayout reports its preferred
     * width as the minimum, which made the parent clip the panel instead of shrinking it; the
     * children's own minimums and wmin constraints still apply inside.
     */
    private fun shrinkable(layout: MigLayout, minWidth: Int = 0): JPanel = object : JPanel(layout) {
        override fun getMinimumSize(): Dimension = Dimension(minWidth, super.getMinimumSize().height)
    }

    /** Read-only text that wraps to the window's width, which a JLabel does not do on its own. */
    private fun wrappingText(text: String) = object : javax.swing.JTextArea(text) {
        // A wrapping text area reports the height for the width it last had; sized to its current
        // width first, it reports the height it needs now, so the last line is never cut off.
        override fun getPreferredSize(): Dimension {
            if (width > 0) setSize(width, Short.MAX_VALUE.toInt())
            return super.getPreferredSize()
        }
    }.apply {
        // The height it needs is only known once it has its new width, so lay out once more then.
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            private var lastWidth = -1
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (width != lastWidth) {
                    lastWidth = width
                    SwingUtilities.invokeLater { revalidate() }
                }
            }
        })
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = null
        font = UIManager.getFont("Label.font")
    }

    private fun banner(text: javax.swing.JTextArea, accent: Color?, onDismiss: () -> Unit): JPanel =
        JPanel(MigLayout("fillx, insets 6 12 6 12", "[grow,fill][]")).apply {
            border = BorderFactory.createMatteBorder(0, 4, 0, 0, accent ?: Color.ORANGE)
            add(text, "wmin 100")
            add(JButton("✕").apply {
                putClientProperty("JButton.buttonType", "toolBarButton")
                toolTipText = strings.dismiss
                addActionListener { this@apply.parent.isVisible = false; onDismiss() }
            })
        }

    // ---------------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------------

    private fun wireEvents() {
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = onClose()
            override fun windowDeactivated(e: WindowEvent) = hideCard()
            // Coming back from Settings: the speech service may have just been set up.
            override fun windowActivated(e: WindowEvent) = renderSpeechStatus()
        })
        recordButton.addActionListener {
            when (recorder.state.value.phase) {
                RecorderPhase.IDLE -> startRecording()
                RecorderPhase.RECORDING -> recorder.pause()
                RecorderPhase.PAUSED -> recorder.resume()
                RecorderPhase.STOPPING -> Unit
            }
        }
        stopButton.addActionListener {
            scope.launch {
                val current = recorder.state.value.session
                recorder.stop()
                if (current != null) {
                    val choice = JOptionPane.showConfirmDialog(
                        this@ConversationWindow,
                        strings.confirmSaveSession,
                        strings.confirmSaveTitle,
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                    )
                    if (choice == JOptionPane.NO_OPTION) {
                        feature.repository.delete(current.id)
                        viewed = null
                        transcript.render(null)
                        reloadSessions()
                    }
                }
            }
        }
        refreshDevicesButton.addActionListener { loadDevices() }
        testDevicesButton.addActionListener { testDevices() }
        deviceModeButton.addActionListener {
            if (isAppMode) {
                isAppMode = false
                deviceModeButton.isSelected = true
                appModeButton.isSelected = false
                feature.preferences = feature.preferences.copy(systemAudioMode = "device")
                populateSystemItems()
                deviceWarningDismissed = false
                checkDeviceWarnings()
            }
        }
        appModeButton.addActionListener {
            if (!isAppMode) {
                isAppMode = true
                appModeButton.isSelected = true
                deviceModeButton.isSelected = false
                feature.preferences = feature.preferences.copy(systemAudioMode = "app")
                populateSystemItems()
                deviceWarningDismissed = false
                checkDeviceWarnings()
            }
        }
        microphoneCombo.addActionListener {
            deviceWarningDismissed = false
            checkDeviceWarnings()
        }
        systemCombo.addActionListener {
            deviceWarningDismissed = false
            checkDeviceWarnings()
        }
        myLanguageCombo.addActionListener {
            if (fillingLanguages) return@addActionListener
            (myLanguageCombo.selectedItem as? LanguageCode)?.let { lang ->
                if (feature.preferences.spokenLanguage == lang.tag) return@let
                feature.preferences = feature.preferences.copy(spokenLanguage = lang.tag)
                if (recorder.state.value.isActive) {
                    recorder.updateSpokenLanguage(lang)
                }
            }
        }
        callLanguageCombo.addActionListener {
            if (fillingLanguages) return@addActionListener
            (callLanguageCombo.selectedItem as? LanguageCode)?.let { lang ->
                if (feature.preferences.callLanguage == lang.tag) return@let
                feature.preferences = feature.preferences.copy(callLanguage = lang.tag)
                if (recorder.state.value.isActive) {
                    recorder.updateCallLanguage(lang)
                }
            }
        }
        speechSettingsButton.addActionListener {
            onOpenSpeechSettings()
            loadPreferences()
        }
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowActivated(e: java.awt.event.WindowEvent) = loadPreferences()
        })
        retryFailedButton.addActionListener { retryAllFailed() }
        chatLayoutCheck.addActionListener {
            val enabled = chatLayoutCheck.isSelected
            transcript.chatLayout = enabled
            feature.preferences = feature.preferences.copy(chatLayout = enabled)
        }
        autoTranslateCheck.addActionListener {
            val enabled = autoTranslateCheck.isSelected
            feature.preferences = feature.preferences.copy(autoTranslate = enabled)
            val anchor = transcript.captureScrollAnchor()
            transcript.autoTranslate = enabled
            transcript.restoreScrollAnchor(anchor)
            if (enabled) {
                if (!feature.preferences.autoTranslateNoticeDismissed) {
                    feature.preferences = feature.preferences.copy(autoTranslateNoticeDismissed = true)
                    JOptionPane.showMessageDialog(this@ConversationWindow, strings.onlineTranslationNotice)
                }
                displayedSession()?.let { checkAutoTranslate(it) }
            }
        }
        orderButton.addActionListener {
            val anchor = transcript.captureScrollAnchor()
            transcript.newestFirst = !transcript.newestFirst
            orderButton.text = if (transcript.newestFirst) strings.orderNewestAtTop else strings.orderOldestAtTop
            transcriptInnerPanel.remove(liveSlotsPanel)
            transcriptInnerPanel.add(liveSlotsPanel, if (transcript.newestFirst) BorderLayout.NORTH else BorderLayout.SOUTH)
            transcriptInnerPanel.validate()
            transcriptInnerPanel.doLayout()
            transcriptOverlayPane.doLayout()
            transcriptScroll.doLayout()
            transcriptScroll.viewport.doLayout()
            transcript.restoreScrollAnchor(anchor)
            transcriptInnerPanel.repaint()
        }
        newButton.addActionListener {
            if (!recorder.state.value.isActive) {
                sessionsList.clearSelection()
                viewed = null
                recorder.show(null)
                transcript.render(null)
            }
        }
        targetCombo.addActionListener {
            val target = targetCombo.selectedItem as? LanguageCode
            target?.let { feature.preferences = feature.preferences.copy(targetLanguage = it.tag) }
            hideCard()
            if (autoTranslateCheck.isSelected) {
                displayedSession()?.let { checkAutoTranslate(it) }
            }
        }
        sessionsList.addListSelectionListener { e ->
            if (e.valueIsAdjusting || recorder.state.value.isActive) return@addListSelectionListener
            val selected = sessionsList.selectedValue ?: return@addListSelectionListener
            viewed = feature.repository.load(selected.id) ?: selected
            transcript.render(viewed)
            highlightSearch()
            updateSessionButtons()
            renderRetryButton()
        }
        sessionsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) renameSelected() }
        })
        renameButton.addActionListener { renameSelected() }
        exportButton.addActionListener { exportSelected() }
        deleteButton.addActionListener { deleteSelected() }
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = searchTimer.restart()
            override fun removeUpdate(e: DocumentEvent) = searchTimer.restart()
            override fun changedUpdate(e: DocumentEvent) = searchTimer.restart()
        })

        var pressPoint: Point? = null
        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                syncRenderGateState()
            }
            override fun mousePressed(e: MouseEvent) {
                pressPoint = e.point
            }
            override fun mouseMoved(e: MouseEvent) {
                syncRenderGateState()
                onTranscriptHover(e)
            }
            override fun mouseExited(e: MouseEvent) {
                hoverCard = false
                syncRenderGateState()
                showTimer.stop()
                hideTimer.restart()
                checkAndFlushIfIdle()
            }
            override fun mouseClicked(e: MouseEvent) = onTranscriptClick(e)
            override fun mouseReleased(e: MouseEvent) {
                val pressed = pressPoint
                pressPoint = null
                val didDrag = pressed != null && pressed.distance(e.point) > 5
                if (!didDrag && SwingUtilities.isLeftMouseButton(e)) {
                    val hit = transcript.hitTest(e.point)
                    if (hit is TranscriptView.Hit.PlayButton || hit is TranscriptView.Hit.RetryButton || hit is TranscriptView.Hit.DeleteButton) {
                        onTranscriptClick(e)
                        return
                    }
                }
                onTranscriptSelection()
            }
        }
        transcript.asComponent.addMouseListener(mouse)
        transcript.asComponent.addMouseMotionListener(mouse)
        transcriptScroll.viewport.addChangeListener {
            if (popup.isVisible && !transcript.isProgrammaticScroll) {
                hideCard()
            }
        }

        popup.onHoverChanged = { inside ->
            popupHovered = inside
            if (inside) hideTimer.stop() else hideTimer.restart()
            checkAndFlushIfIdle()
        }
    }

    private fun onClose() {
        if (!recorder.state.value.isActive) {
            hideCard()
            isVisible = false
            return
        }
        val options = arrayOf(strings.closeStop, strings.closeKeep, strings.cancel)
        when (JOptionPane.showOptionDialog(this, strings.closeWhileRecording, strings.title,
            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0])) {
            0 -> scope.launch {
                val current = recorder.state.value.session
                recorder.stop()
                if (current != null) {
                    val saveChoice = JOptionPane.showConfirmDialog(
                        this@ConversationWindow,
                        strings.confirmSaveSession,
                        strings.confirmSaveTitle,
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                    )
                    if (saveChoice == JOptionPane.NO_OPTION) {
                        feature.repository.delete(current.id)
                        viewed = null
                        transcript.render(null)
                        reloadSessions()
                    }
                }
                isVisible = false
            }
            1 -> { hideCard(); isVisible = false } // keeps recording; the menu brings the window back
        }
    }

    // ---------------------------------------------------------------------------------------
    // Recording
    // ---------------------------------------------------------------------------------------

    private fun startRecording() {
        val microphone = (microphoneCombo.selectedItem as? DeviceItem)?.device
        val systemItem = systemCombo.selectedItem as? DeviceItem
        val system = systemItem?.device
        if (microphone == null && system == null) return
        if (!feature.hasSpeechService) {
            val answer = JOptionPane.showConfirmDialog(this, html(strings.noSpeechServiceConfirm), strings.title,
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
            if (answer != JOptionPane.YES_OPTION) return
        }
        val myLanguage = myLanguageCombo.selectedItem as? LanguageCode ?: LanguageCode.AUTO
        val callLanguage = callLanguageCombo.selectedItem as? LanguageCode ?: LanguageCode.AUTO

        // C6: Pre-recording check for missing offline models in Local only / Vosk live mode
        val missingLangs = listOf(myLanguage, callLanguage)
            .filter { it != LanguageCode.AUTO && !feature.isModelInstalled(it) }
            .distinct()
        if (missingLangs.isNotEmpty()) {
            val names = missingLangs.joinToString(", ") { it.getDisplayName() }
            val options = arrayOf(strings.speechSettings, strings.cancel, strings.record)
            val choice = JOptionPane.showOptionDialog(
                this,
                strings.missingModelWarning(names),
                strings.title,
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]
            )
            when (choice) {
                0 -> { onOpenSpeechSettings(); return }
                1, JOptionPane.CLOSED_OPTION -> return
                else -> Unit // 2: record anyway
            }
        }
        feature.preferences = feature.preferences.copy(
            microphoneId = microphone?.id,
            systemOutputId = system?.id,
            systemAudioMode = if (isAppMode) "app" else "device",
            preferredProcessName = systemItem?.app?.processName ?: feature.preferences.preferredProcessName,
            spokenLanguage = myLanguage.tag,
            callLanguage = callLanguage.tag,
            keepAudio = keepAudioCheck.isSelected
        )
        sessionsList.clearSelection()
        viewed = null
        recorder.start(
            RecordingOptions(
                title = strings.defaultTitle(SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())),
                microphone = microphone,
                systemOutput = system,
                microphoneLanguage = myLanguage,
                systemLanguage = callLanguage,
                keepAudio = keepAudioCheck.isSelected
            )
        )
    }

    private fun render(state: RecorderState) {
        val active = state.isActive
        recordButton.text = when (state.phase) {
            RecorderPhase.RECORDING -> "❚❚ ${strings.pause}"
            RecorderPhase.PAUSED -> "▶ ${strings.resume}"
            else -> "● ${strings.record}"
        }
        recordButton.isEnabled = state.phase != RecorderPhase.STOPPING && !loadingDevices && !testingDevices
        stopButton.isEnabled = active
        listOf(refreshDevicesButton, testDevicesButton, newButton)
            .forEach { it.isEnabled = state.phase == RecorderPhase.IDLE && !loadingDevices && !testingDevices }
        listOf(deviceModeButton, appModeButton, keepAudioCheck)
            .forEach { it.isEnabled = !loadingDevices && !testingDevices }
        // During active recording, devices are locked to protect the streams, but languages remain freely switchable
        listOf(microphoneCombo, systemCombo)
            .forEach { it.isEnabled = state.phase == RecorderPhase.IDLE && !loadingDevices && !testingDevices }
        listOf(myLanguageCombo, callLanguageCombo, targetCombo)
            .forEach { it.isEnabled = !loadingDevices && !testingDevices }
        sessionsList.isEnabled = !active
        timerLabel.text = formatTimestamp(if (active || state.phase == RecorderPhase.STOPPING) state.elapsedMillis else viewed?.durationMillis ?: 0)
        microphoneLight.render(state.streams.getValue(AudioSource.MICROPHONE))
        systemLight.render(state.streams.getValue(AudioSource.SYSTEM))

        val pending = state.session?.segments?.count { it.pending } ?: 0
        pendingLabel.text = if (active && pending > 0) strings.pending(pending) else ""
        renderRetryButton()

        noticeBar.isVisible = state.notice != null
        noticeText.text = state.notice?.let(::noticeMessage).orEmpty()

        val shouldShowLive = active || state.phase == RecorderPhase.STOPPING
        if (liveSlotsPanel.isVisible != shouldShowLive) {
            val anchor = transcript.captureScrollAnchor()
            liveSlotsPanel.render(state.live, visible = shouldShowLive)
            transcriptInnerPanel.validate()
            transcriptInnerPanel.doLayout()
            transcriptOverlayPane.doLayout()
            transcriptScroll.doLayout()
            transcriptScroll.viewport.doLayout()
            transcript.restoreScrollAnchor(anchor)
            transcriptInnerPanel.repaint()
        } else if (shouldShowLive) {
            liveSlotsPanel.render(state.live, visible = true)
        } else {
            liveSlotsPanel.render(emptyMap(), visible = false)
        }

        if (active || state.phase == RecorderPhase.STOPPING) {
            val sessionToRender = state.session

            syncRenderGateState()
            if (renderGate.submit(sessionToRender)) {
                updatePausePill(visible = false)
                // When actively following live, trim back to standard window N
                (transcript as? TranscriptListView)?.loadedPagesCount = 0
                transcript.render(sessionToRender)
            } else if (renderGate.hasPendingUpdates()) {
                updatePausePill(visible = true, pendingCount = renderGate.pendingCount)
            }
        }
        renderSpeechStatus()
        checkDeviceWarnings()

        if (lastPhase != RecorderPhase.IDLE && state.phase == RecorderPhase.IDLE) {
            viewed = state.session
            reloadSessions(selectId = state.session?.id)
        }
        if (autoTranslateCheck.isSelected) {
            displayedSession()?.let { checkAutoTranslate(it) }
        }
        lastPhase = state.phase
        updateSessionButtons()
    }

    // Service messages usually end with their own full stop, and the templates add one.
    private fun noticeMessage(notice: RecorderNotice): String = when (notice) {
        RecorderNotice.NoSpeechService -> strings.noticeNoSpeechService
        is RecorderNotice.TranscriptionFailed -> strings.noticeTranscriptionFailed(notice.error.message.trimEnd('.'))
        is RecorderNotice.DeviceLost -> strings.noticeDeviceLost(sourceName(notice.source), notice.message.trimEnd('.'))
        is RecorderNotice.SaveFailed -> strings.noticeSaveFailed(notice.message.trimEnd('.'))
        is RecorderNotice.LiveFailed -> strings.noticeLiveFailed(sourceName(notice.source), notice.message.trimEnd('.'))
        is RecorderNotice.LiveUnavailable -> strings.noticeLiveUnavailable(sourceName(notice.source), notice.message.trimEnd('.'))
        RecorderNotice.EchoDetected -> strings.noticeEchoDetected
    }

    private fun sourceName(source: AudioSource) = if (source == AudioSource.MICROPHONE) strings.speakerMe else strings.speakerCall

    /** The session on screen: the live one while recording, otherwise the one picked in the list. */
    private fun displayedSession(): ConversationSession? =
        if (recorder.state.value.isActive) recorder.state.value.session else viewed

    // ---------------------------------------------------------------------------------------
    // Devices and preferences
    // ---------------------------------------------------------------------------------------

    private fun loadPreferences() {
        val preferences = feature.preferences
        privacyBar.isVisible = !preferences.privacyNoticeDismissed
        keepAudioCheck.isSelected = preferences.keepAudio
        chatLayoutCheck.isSelected = preferences.chatLayout
        transcript.chatLayout = preferences.chatLayout
        autoTranslateCheck.isSelected = preferences.autoTranslate
        transcript.autoTranslate = preferences.autoTranslate
        transcript.windowSize = preferences.visibleCardWindow
        isAppMode = preferences.systemAudioMode == "app"
        deviceModeButton.isSelected = !isAppMode
        appModeButton.isSelected = isAppMode

        val translationAvailable = languages().filter { it != LanguageCode.AUTO }
        val speechAvailable = feature.speechLanguages()?.filter { it != LanguageCode.AUTO }
        val catalogue = feature.catalogueLanguages()?.filter { it != LanguageCode.AUTO }
        val spokenAvailable = catalogue ?: speechAvailable ?: translationAvailable
        val spokenRenderer = LanguageRenderer(checkModelInstalled = true)
        val targetRenderer = LanguageRenderer(checkModelInstalled = false)
        val spokenChoices = (listOf(LanguageCode.AUTO) + spokenAvailable).toTypedArray()
        fun JComboBox<LanguageCode>.fill(tag: String) {
            model = DefaultComboBoxModel(spokenChoices)
            this.renderer = spokenRenderer
            selectedItem = LanguageCode(tag).takeIf { it == LanguageCode.AUTO || it in spokenAvailable } ?: LanguageCode.AUTO
        }
        fillingLanguages = true
        try {
            myLanguageCombo.fill(preferences.spokenLanguage)
            callLanguageCombo.fill(preferences.callLanguage)
            targetCombo.model = DefaultComboBoxModel(translationAvailable.toTypedArray())
            targetCombo.renderer = targetRenderer
            targetCombo.selectedItem = preferences.targetLanguage?.let(::LanguageCode)?.takeIf { it in translationAvailable }
                ?: defaultTarget().takeIf { it in translationAvailable } ?: translationAvailable.firstOrNull()
        } finally {
            fillingLanguages = false
        }
        renderSpeechStatus()
    }

    private fun loadDevices() {
        loadingDevices = true
        val loading = arrayOf(DeviceItem(null, strings.loadingDevices))
        microphoneCombo.model = DefaultComboBoxModel(loading)
        systemCombo.model = DefaultComboBoxModel(loading)
        render(recorder.state.value)
        scope.launch {
            // Listing system outputs and apps starts PowerShell and takes a second or two.
            val (microphones, outputs, apps) = withContext(Dispatchers.IO) {
                Triple(feature.microphones(), feature.systemOutputs(), feature.systemApplications())
            }
            cachedOutputs = outputs
            cachedApps = apps

            val preferences = feature.preferences
            val none = DeviceItem(null, strings.none)

            val micItems = listOf(none) + microphones.filterNot { it.looksLikeSystemAudio }.map { DeviceItem(it, it.name) }
            microphoneCombo.model = DefaultComboBoxModel(micItems.toTypedArray())
            // Matched only when something was saved: a null id would otherwise match "None" itself.
            val micDevices = micItems.mapNotNull { it.device }
            val preferredMic = DeviceCheckHeuristics.pickDefaultMicrophone(micDevices)
            microphoneCombo.selectedItem = preferences.microphoneId?.let { id -> micItems.firstOrNull { it.device?.id == id } }
                ?: preferredMic?.let { dev -> micItems.firstOrNull { it.device?.id == dev.id } }
                ?: micItems.firstOrNull { it.device?.name?.contains("microphone", ignoreCase = true) == true }
                ?: micItems.getOrNull(1) ?: none

            populateSystemItems()

            loadingDevices = false
            render(recorder.state.value)
        }
    }

    private fun populateSystemItems() {
        val none = DeviceItem(null, strings.none)
        val preferences = feature.preferences

        if (isAppMode) {
            val appItems = if (cachedApps.isEmpty()) {
                listOf(none, DeviceItem(null, "(${strings.noActiveApps})"))
            } else {
                listOf(none) + cachedApps.map { app ->
                    DeviceItem(device = app.device, label = app.displayName, app = app)
                }
            }
            systemCombo.model = DefaultComboBoxModel(appItems.toTypedArray())

            // Try to match preferred process name first
            val preferredProc = preferences.preferredProcessName
            val selected = preferredProc?.let { name ->
                appItems.firstOrNull { it.app?.processName.equals(name, ignoreCase = true) }
            } ?: appItems.firstOrNull { it.app?.processName?.contains("discord", ignoreCase = true) == true }
              ?: appItems.firstOrNull { it.app != null } ?: none

            systemCombo.selectedItem = selected
        } else {
            val systemItems = listOf(none) + cachedOutputs.map { DeviceItem(it, it.name) }
            systemCombo.model = DefaultComboBoxModel(systemItems.toTypedArray())
            val systemDevices = systemItems.mapNotNull { it.device }
            val preferredSys = DeviceCheckHeuristics.pickDefaultCallOutput(systemDevices)
            systemCombo.selectedItem = preferences.systemOutputId?.let { id -> systemItems.firstOrNull { it.device?.id == id } }
                ?: preferredSys?.let { dev -> systemItems.firstOrNull { it.device?.id == dev.id } }
                ?: systemItems.firstOrNull { it.device?.name?.contains("chat", ignoreCase = true) == true }
                ?: systemItems.getOrNull(1) ?: none
        }
    }

    private fun checkDeviceWarnings() {
        if (deviceWarningDismissed || recorder.state.value.isActive) {
            deviceWarningBar.isVisible = false
            return
        }
        val micItem = microphoneCombo.selectedItem as? DeviceItem
        val micName = micItem?.device?.name
        val sysItem = systemCombo.selectedItem as? DeviceItem
        val sysName = sysItem?.device?.name
        val allOutputs = (0 until systemCombo.itemCount).mapNotNull { (systemCombo.getItemAt(it) as? DeviceItem)?.device?.name }

        val warnings = mutableListOf<String>()
        if (DeviceCheckHeuristics.isSuspiciousMicrophone(micName)) {
            warnings.add(strings.warningSuspiciousMic(micName ?: ""))
        }
        val suggestedChat = DeviceCheckHeuristics.findSuggestedChatOutput(sysName, allOutputs)
        if (suggestedChat != null) {
            warnings.add(strings.warningSuggestedChat(suggestedChat))
        }

        if (warnings.isNotEmpty()) {
            deviceWarningText.text = warnings.joinToString("\n")
            deviceWarningBar.isVisible = true
        } else {
            deviceWarningBar.isVisible = false
        }
    }

    private fun testDevices() {
        if (testingDevices || recorder.state.value.isActive) return
        val micDevice = (microphoneCombo.selectedItem as? DeviceItem)?.device
        val sysDevice = (systemCombo.selectedItem as? DeviceItem)?.device
        if (micDevice == null && sysDevice == null) return

        testingDevices = true
        testDevicesButton.isEnabled = false
        val originalText = strings.testDevices

        scope.launch {
            val startTime = System.currentTimeMillis()
            val durationMillis = 3_000L

            fun monitorDeviceLevel(backend: AudioBackend, dev: AudioDevice, light: StreamLight) = launch(Dispatchers.IO) {
                runCatching {
                    val stream = backend.open(dev)
                    try {
                        while (System.currentTimeMillis() - startTime < durationMillis && testingDevices) {
                            val chunk = stream.read() ?: break
                            val level = AudioLevels.rmsLevel(chunk)
                            withContext(Dispatchers.Swing) {
                                light.render(StreamStatus(state = StreamState.LIVE, level = level, deviceName = dev.name))
                            }
                        }
                    } finally {
                        runCatching { stream.close() }
                    }
                }
            }

            val micJob = micDevice?.let { monitorDeviceLevel(feature.microphoneBackend, it, microphoneLight) }
            val sysJob = sysDevice?.let { monitorDeviceLevel(feature.systemBackend, it, systemLight) }

            while (System.currentTimeMillis() - startTime < durationMillis && testingDevices) {
                val remainingSec = ((durationMillis - (System.currentTimeMillis() - startTime) + 999) / 1000).toInt()
                withContext(Dispatchers.Swing) {
                    testDevicesButton.text = strings.testingDevices(remainingSec)
                }
                kotlinx.coroutines.delay(200)
            }

            micJob?.join()
            sysJob?.join()

            withContext(Dispatchers.Swing) {
                testingDevices = false
                testDevicesButton.text = originalText
                testDevicesButton.isEnabled = !loadingDevices && recorder.state.value.phase == RecorderPhase.IDLE
                render(recorder.state.value)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Sessions list
    // ---------------------------------------------------------------------------------------

    private fun reloadSessions(selectId: String? = sessionsList.selectedValue?.id) {
        val query = searchField.text
        scope.launch {
            val hits = withContext(Dispatchers.IO) { feature.repository.search(query) }
            sessionsModel.clear()
            hits.forEach { sessionsModel.addElement(it.session) }
            val index = (0 until sessionsModel.size).firstOrNull { sessionsModel[it].id == selectId } ?: -1
            if (index >= 0) sessionsList.selectedIndex = index
            highlightSearch()
            updateSessionButtons()
        }
    }

    private fun highlightSearch() {
        val query = searchField.text.trim()
        transcript.highlightSearch(query)
        if (query.isNotEmpty()) {
            displayedSession()?.segments?.firstOrNull { it.text.contains(query, ignoreCase = true) }?.let { hitSeg ->
                transcript.ensureSegmentVisible(hitSeg.id)
                transcript.scrollToSegment(hitSeg.id)
            }
        }
    }

    private fun updateSessionButtons() {
        val selectable = !recorder.state.value.isActive && sessionsList.selectedValue != null
        renameButton.isEnabled = selectable
        exportButton.isEnabled = selectable
        deleteButton.isEnabled = selectable
    }

    private fun renameSelected() {
        val session = sessionsList.selectedValue ?: return
        if (recorder.state.value.isActive) return
        val title = JOptionPane.showInputDialog(this, strings.renamePrompt, session.title)?.trim()
        if (title.isNullOrEmpty() || title == session.title) return
        scope.launch {
            withContext(Dispatchers.IO) { feature.repository.load(session.id)?.let { feature.repository.save(it.copy(title = title)) } }
            reloadSessions(selectId = session.id)
        }
    }

    private fun exportSelected() {
        val session = viewed ?: return
        val includeTransBox = JCheckBox(strings.exportIncludeTranslations, true)
        val chooser = JFileChooser().apply {
            selectedFile = File(session.title.replace(Regex("""[\\/:*?"<>|]"""), "_") + ".txt")
            addChoosableFileFilter(FileNameExtensionFilter("Text Document (*.txt)", "txt"))
            addChoosableFileFilter(FileNameExtensionFilter("SubRip Subtitle (*.srt)", "srt"))
            fileFilter = choosableFileFilters.first()
            accessory = JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
                add(includeTransBox, BorderLayout.NORTH)
            }
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val ext = chooser.fileFilter?.description?.let {
            if (it.contains(".srt", ignoreCase = true)) "srt" else "txt"
        } ?: chooser.selectedFile.extension.ifEmpty { "txt" }
        val target = chooser.selectedFile.let {
            if (it.extension.isEmpty()) File(it.path + ".$ext") else it
        }
        val includeTranslations = includeTransBox.isSelected
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                if (target.extension.equals("srt", ignoreCase = true)) {
                    feature.repository.exportSrt(session, includeTranslations, ::sourceName)
                } else {
                    feature.repository.exportText(session, includeTranslations, ::sourceName)
                }
            }
            withContext(Dispatchers.IO) { target.writeText(content, Charsets.UTF_8) }
            JOptionPane.showMessageDialog(this@ConversationWindow, strings.exported(target.absolutePath))
        }
    }

    private fun deleteSelected() {
        val session = sessionsList.selectedValue ?: return
        if (recorder.state.value.isActive) return
        if (JOptionPane.showConfirmDialog(this, strings.deleteConfirm(session.title), strings.delete,
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return
        feature.player.stop()
        scope.launch {
            withContext(Dispatchers.IO) { feature.repository.delete(session.id) }
            if (viewed?.id == session.id) {
                viewed = null
                recorder.show(null)
                transcript.render(null)
            }
            reloadSessions(selectId = null)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Transcript interaction
    // ---------------------------------------------------------------------------------------

    private fun onTranscriptHover(e: MouseEvent) {
        val hit = transcript.hitTest(e.point)
        hoverCard = hit != null
        transcript.updateCursor(hit)
        transcript.asComponent.toolTipText = transcript.tooltipForHit(hit)
        // A selection owns the card until it is cleared.
        if (transcript.selectionStart != transcript.selectionEnd) return

        val text = hit as? TranscriptView.Hit.Text
        val range = text?.takeIf { !it.span.segment.pending && !it.span.segment.failed }
            ?.let { transcript.wordRangeAt(it.span, it.offset) }
        if (range == hoverRange) {
            if (range != null) hideTimer.stop() else checkAndFlushIfIdle()
            return
        }
        hoverRange = range
        hoverSpan = text?.span
        showTimer.stop()
        if (range == null) { hideTimer.restart(); checkAndFlushIfIdle() } else { hideTimer.stop(); showTimer.restart() }
    }

    private fun onTranscriptClick(e: MouseEvent) {
        if (e.clickCount != 1 || !SwingUtilities.isLeftMouseButton(e)) return
        when (val hit = transcript.hitTest(e.point)) {
            is TranscriptView.Hit.PlayButton -> playRange(hit.span.segment, hit.span.segment.startMillis, hit.span.segment.endMillis)
            is TranscriptView.Hit.StopButton -> feature.stop()
            is TranscriptView.Hit.RetryButton -> retry(hit.span.segment)
            is TranscriptView.Hit.TranslationRetry -> retryTranslation(hit.span.segment)
            is TranscriptView.Hit.DeleteButton -> deleteSingleSegment(hit.span.segment)
            is TranscriptView.Hit.LoadMore -> transcript.loadMore()
            is TranscriptView.Hit.VerificationBadge -> Unit
            is TranscriptView.Hit.Header -> Unit // Clicking speaker label or timestamp does not trigger playback
            is TranscriptView.Hit.Text -> if (hit.span.segment.failed) retry(hit.span.segment)
            null -> Unit
        }
    }

    private fun onTranscriptSelection() {
        val start = transcript.selectionStart
        val end = transcript.selectionEnd
        if (start == end) return

        // Must overlap actual spoken text, never the header row (play button, speaker, timestamp)
        val span = transcript.spans.firstOrNull { it.textEnd > start && it.textStart < end } ?: return
        if (end <= span.textStart || start >= span.textEnd) return

        val effectiveStart = maxOf(start, span.textStart)
        val effectiveEnd = minOf(end, span.textEnd)
        if (effectiveStart >= effectiveEnd) return

        val phrase = transcript.textOf(effectiveStart until effectiveEnd).trim()
        if (phrase.isEmpty() || phrase.length > MAX_PHRASE_CHARS || phrase.none { it.isLetterOrDigit() }) return

        val timed = span.words.filter { (chars, _) -> chars.first < effectiveEnd && chars.last >= effectiveStart }.map { it.second }
        val anchor = transcript.boundsOf(effectiveStart until effectiveEnd) ?: return
        val session = displayedSession() ?: return

        activePopupWordRange = timed.takeIf { it.isNotEmpty() }?.let { Pair(it.first().startMillis, it.last().endMillis) }
        showCard(anchor, phrase, contextSentence = null, onPlay = timed.takeIf { it.isNotEmpty() }?.let {
            { playWordRange(span.segment, it.first().startMillis, it.last().endMillis) }
        }, segment = span.segment, session = session)
    }

    private fun showWordCard() {
        val range = hoverRange ?: return
        val span = hoverSpan ?: return
        val session = displayedSession() ?: return
        val word = transcript.textOf(range)
        val anchor = transcript.boundsOf(range) ?: return
        val timed = transcript.timedWordAt(span, range)
        val sentence = span.segment.text.takeIf { it.trim().contains(' ') }
        activePopupWordRange = timed?.let { Pair(it.startMillis, it.endMillis) }
        showCard(anchor, word, sentence, timed?.let { { playWordRange(span.segment, it.startMillis, it.endMillis) } }, span.segment, session)
    }

    private fun showCard(
        anchorInTranscript: Rectangle,
        text: String,
        contextSentence: String?,
        onPlay: (() -> Unit)?,
        segment: TranscriptSegment?,
        session: ConversationSession
    ) {
        val target = targetCombo.selectedItem as? LanguageCode ?: return
        val hasAudio = segment != null && feature.hasAudio(session, segment.source)
        val hasContext = contextSentence != null && contextSentence.trim() != text.trim()
        val content = HoverTranslationPopup.Content(
            text = text,
            translation = feature.translations.peek(text, target),
            context = if (!hasContext) contextSentence?.let { feature.translations.peek(it, target) } else null,
            hasContextSentence = hasContext,
            canPlayWord = onPlay != null && hasAudio,
            canPlaySentence = hasAudio
        )
        activePopupSegment = segment
        popup.onPlayWord = {
            val tag = feature.player.playing.value
            if (isWordPlaying(tag, segment?.id, activePopupWordRange)) {
                feature.stop()
            } else {
                onPlay?.invoke()
            }
        }
        popup.onPlaySentence = {
            val tag = feature.player.playing.value
            if (isSentencePlaying(tag, segment?.id)) {
                feature.stop()
            } else {
                segment?.let { playRange(it, it.startMillis, it.endMillis) }
            }
        }
        popup.onShowSentence = {
            if (contextSentence != null) {
                updateCard(text) { it.copy(contextLoading = true) }
                scope.launch {
                    feature.translations.translate(contextSentence, target).fold(
                        success = { sentence -> updateCard(text) { it.copy(context = sentence, contextLoading = false) } },
                        failure = { updateCard(text) { it.copy(contextLoading = false) } }
                    )
                }
            }
        }
        popup.onCopy = { popupContent?.translation?.let { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null) } }
        popup.onMeanings = { loadMeanings(text, segment, session, target) }

        val screen = Rectangle(anchorInTranscript).apply {
            val origin = java.awt.Point(x, y)
            SwingUtilities.convertPointToScreen(origin, transcript.asComponent)
            location = origin
        }
        popupContent = content
        popup.show(screen, content)
        updateHoverPopupPlaying(feature.player.playing.value)

        popupJob?.cancel()
        popupJob = scope.launch {
            val translated = feature.translations.translate(text, target)
            updateCard(text) { current ->
                translated.fold(
                    success = { current.copy(translation = it, error = null) },
                    failure = { current.copy(error = it.message) }
                )
            }
        }
    }

    private fun loadMeanings(text: String, segment: TranscriptSegment?, session: ConversationSession, target: LanguageCode) {
        updateCard(text) { it.copy(meaningsLoading = true) }
        val source = segment?.language?.let(::LanguageCode)
            ?: LanguageCode(segment?.let { session.languageOf(it.source) } ?: session.spokenLanguage)
        scope.launch {
            val result = feature.meanings(text, source, target)
            updateCard(text) { current ->
                result.fold(
                    success = { entries ->
                        current.copy(meaningsLoading = false, meanings = entries.flatMap { entry ->
                            entry.definitions.take(2).map { definition ->
                                listOf(entry.partOfSpeech, definition.text).filter { it.isNotBlank() }.joinToString(" — ")
                            }
                        }.take(MAX_MEANINGS))
                    },
                    failure = { current.copy(meaningsLoading = false, meanings = listOf(strings.failed(it.message))) }
                )
            }
        }
    }

    /** Updates the card only if it still shows [text]; the user may have moved on. */
    private fun updateCard(text: String, transform: (HoverTranslationPopup.Content) -> HoverTranslationPopup.Content) {
        val current = popupContent ?: return
        if (current.text != text || !popup.isVisible) return
        val updated = transform(current)
        popupContent = updated
        popup.update(updated)
    }

    private fun hideCard() {
        showTimer.stop()
        hideTimer.stop()
        popupJob?.cancel()
        popup.hide()
        popupContent = null
        popupHovered = false
        hoverRange = null
        activePopupSegment = null
        activePopupWordRange = null
        checkAndFlushIfIdle()
    }

    private fun updatePausePill(visible: Boolean, pendingCount: Int = 0) {
        if (visible) {
            pausePill.text = strings.updatesPaused(pendingCount)
            pausePill.isVisible = true
        } else {
            pausePill.isVisible = false
        }
        transcriptOverlayPane.doLayout()
        transcriptOverlayPane.repaint()
    }

    private fun isPointerOverCardsOrPill(): Boolean {
        if (pausePill.isVisible && pausePill.mousePosition != null) return true
        val pt = transcript.asComponent.mousePosition ?: transcript.pointerPosition ?: return false
        val span = transcript.spanAtPoint(pt) ?: return false
        val xBounds = TranscriptView.cardHorizontalBounds(
            source = span.segment.source,
            totalWidth = transcript.asComponent.width,
            chatLayout = transcript.chatLayout,
            rtl = transcript.rtlLayout
        )
        return pt.x in xBounds
    }

    private fun syncRenderGateState() {
        if (isPointerOverCardsOrPill()) {
            renderGate.onPointerEntered()
            freezeGraceTimer.stop()
        } else if (renderGate.pointerInside) {
            renderGate.onPointerExited()
            freezeGraceTimer.initialDelay = (RenderGate.DEFAULT_GRACE_MILLIS + 25).toInt()
            freezeGraceTimer.restart()
        }
        renderGate.popupVisible = popup.isVisible || popupHovered
        renderGate.hasSelection = transcript.selectionStart != transcript.selectionEnd
    }

    private fun checkAndFlushIfIdle() {
        syncRenderGateState()
        if (renderGate.isWithinGracePeriod()) {
            if (!freezeGraceTimer.isRunning) {
                freezeGraceTimer.initialDelay = 50
                freezeGraceTimer.restart()
            }
            return
        }
        val session = renderGate.tryFlushIfUnfrozen() ?: return
        updatePausePill(visible = false)
        transcript.render(session)
    }

    private fun flushBufferedUpdates() {
        val session = renderGate.forceFlush() ?: return
        transcript.pointerPosition = null
        updatePausePill(visible = false)
        transcript.render(session)
        scrollToLiveEdge()
    }

    private fun scrollToLiveEdge() {
        transcript.isProgrammaticScroll = true
        try {
            val scrollBar = transcriptScroll.verticalScrollBar
            val viewport = transcriptScroll.viewport
            if (transcript.newestFirst) {
                viewport.viewPosition = Point(viewport.viewPosition.x, 0)
                scrollBar.value = 0
            } else {
                val bottomY = (transcript.asComponent.height - viewport.extentSize.height).coerceAtLeast(0)
                viewport.viewPosition = Point(viewport.viewPosition.x, bottomY)
                scrollBar.value = bottomY
            }
        } finally {
            transcript.isProgrammaticScroll = false
        }
    }

    private fun playRange(segment: TranscriptSegment, startMillis: Long, endMillis: Long) {
        val session = displayedSession() ?: return
        scope.launch {
            // A little padding either side: word timings are tight, and a clipped first consonant
            // makes a word harder to recognise.
            val played = feature.play(
                session = session,
                source = segment.source,
                startMillis = (startMillis - 120).coerceAtLeast(0),
                endMillis = endMillis + 120,
                tag = com.github.ahatem.qtranslate.core.conversation.PlaybackTag.Line(segment.id)
            )
            if (!played) JOptionPane.showMessageDialog(this@ConversationWindow, strings.noAudio)
        }
    }

    private fun playWordRange(segment: TranscriptSegment, startMillis: Long, endMillis: Long) {
        val session = displayedSession() ?: return
        scope.launch {
            val played = feature.play(
                session = session,
                source = segment.source,
                startMillis = (startMillis - 120).coerceAtLeast(0),
                endMillis = endMillis + 120,
                tag = com.github.ahatem.qtranslate.core.conversation.PlaybackTag.Word(segment.id, startMillis, endMillis)
            )
            if (!played) JOptionPane.showMessageDialog(this@ConversationWindow, strings.noAudio)
        }
    }

    private fun isSentencePlaying(tag: Any?, segmentId: String?): Boolean {
        if (tag == null || segmentId == null) return false
        return when (tag) {
            is com.github.ahatem.qtranslate.core.conversation.PlaybackTag.Line -> tag.segmentId == segmentId
            is String -> tag == segmentId
            else -> false
        }
    }

    private fun isWordPlaying(tag: Any?, segmentId: String?, range: Pair<Long, Long>?): Boolean {
        if (tag == null || segmentId == null || range == null) return false
        return when (tag) {
            is com.github.ahatem.qtranslate.core.conversation.PlaybackTag.Word ->
                tag.segmentId == segmentId && tag.startMillis == range.first && tag.endMillis == range.second
            else -> false
        }
    }

    private fun updateHoverPopupPlaying(tag: Any?) {
        val segId = activePopupSegment?.id
        val wordPlaying = isWordPlaying(tag, segId, activePopupWordRange)
        val sentencePlaying = isSentencePlaying(tag, segId)
        popup.setPlayingState(playingWord = wordPlaying, playingSentence = sentencePlaying)
    }

    private fun renderSpeechStatus() {
        val name = feature.speechServiceName
        val live = feature.liveServiceName
        val baseText = when {
            name != null && live != null -> strings.speechHybrid(live, name)
            name != null -> strings.speechService(name)
            live != null -> strings.speechLiveOnly(live)
            else -> strings.speechNone
        }
        val statusText = if (recorder.state.value.isActive && live != null) {
            val status = when (recorder.state.value.liveStatus) {
                LiveRecognitionStatus.CONNECTED -> strings.liveStatusConnected
                LiveRecognitionStatus.RECONNECTING -> strings.liveStatusReconnecting
                LiveRecognitionStatus.OFF -> strings.liveStatusOff
            }
            strings.liveStatusTag(status)
        } else ""
        speechLabel.text = baseText + statusText
        speechLabel.foreground = when {
            name == null && live == null -> UIManager.getColor("Actions.Red") ?: Color.RED
            recorder.state.value.isActive && recorder.state.value.liveStatus == LiveRecognitionStatus.RECONNECTING ->
                UIManager.getColor("Actions.Yellow") ?: Color(200, 150, 0)
            else -> UIManager.getColor("Label.disabledForeground")
        }
    }

    private fun renderLive(state: RecorderState) {
        liveSlotsPanel.render(
            state.live,
            visible = state.isActive || state.live.values.any { it.text.isNotBlank() }
        )
    }

    private fun renderRetryButton() {
        val failed = displayedSession()?.segments?.count { it.failed } ?: 0
        retryFailedButton.isVisible = failed > 0
        retryFailedButton.text = strings.retryFailed(failed)
    }

    private fun retryAllFailed() {
        val session = displayedSession() ?: return
        retryFailedButton.isEnabled = false
        scope.launch {
            val updated = recorder.retryFailed(session)
            if (!recorder.state.value.isActive) {
                viewed = updated
                transcript.render(updated)
            }
            retryFailedButton.isEnabled = true
            renderRetryButton()
        }
    }

    private val inFlightTranslations = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun checkAutoTranslate(session: ConversationSession) {
        val target = targetCombo.selectedItem as? LanguageCode ?: return
        val targetTag = target.tag
        session.segments.forEach { seg ->
            if (seg.id.startsWith("live-") || seg.draft || seg.text.isBlank()) return@forEach
            if (seg.language != null && seg.language.equals(targetTag, ignoreCase = true)) return@forEach
            if (seg.translation != null && inFlightTranslations[seg.id] == seg.text) return@forEach
            if (inFlightTranslations[seg.id] == seg.text) return@forEach

            val textVersion = seg.text
            inFlightTranslations[seg.id] = textVersion
            scope.launch {
                feature.translations.translate(textVersion, target).fold(
                    success = { translated ->
                        val current = displayedSession() ?: return@fold
                        val curSeg = current.segments.firstOrNull { it.id == seg.id }
                        if (curSeg != null && curSeg.text == textVersion) {
                            val updatedSeg = curSeg.copy(translation = translated, translationError = null)
                            applyUpdatedSegment(updatedSeg)
                        }
                    },
                    failure = { err ->
                        val current = displayedSession() ?: return@fold
                        val curSeg = current.segments.firstOrNull { it.id == seg.id }
                        if (curSeg != null && curSeg.text == textVersion) {
                            val updatedSeg = curSeg.copy(translationError = err.message)
                            applyUpdatedSegment(updatedSeg)
                        }
                    }
                )
            }
        }
    }

    private fun applyUpdatedSegment(segment: TranscriptSegment) {
        if (recorder.state.value.isActive) {
            recorder.updateLiveSession { it.withSegment(segment) }
        } else {
            val current = viewed ?: return
            val updated = current.withSegment(segment)
            viewed = updated
            transcript.render(updated)
            scope.launch(Dispatchers.IO) { runCatching { feature.repository.save(updated) } }
        }
    }

    private fun retryTranslation(segment: TranscriptSegment) {
        inFlightTranslations.remove(segment.id)
        val cleared = segment.copy(translationError = null)
        applyUpdatedSegment(cleared)
        displayedSession()?.let { checkAutoTranslate(it) }
    }

    private fun retry(segment: TranscriptSegment) {
        val session = displayedSession() ?: return
        val pendingSegment = segment.copy(pending = true, draft = false, failed = false, text = "")
        val interim = session.withSegment(pendingSegment)
        if (recorder.state.value.isActive) {
            recorder.updateLiveSession { it.withSegment(pendingSegment) }
        } else {
            viewed = interim
            transcript.render(interim)
        }
        scope.launch {
            val updated = recorder.retry(session, segment)
            if (updated != null) {
                if (recorder.state.value.isActive) {
                    val finalSeg = updated.segments.firstOrNull { s -> s.id == segment.id } ?: segment
                    recorder.updateLiveSession { it.withSegment(finalSeg) }
                } else {
                    viewed = updated
                    transcript.render(updated)
                }
            }
        }
    }

    private fun deleteSingleSegment(segment: TranscriptSegment) {
        val session = displayedSession() ?: return
        scope.launch {
            val updated = recorder.deleteSegment(session, segment.id)
            if (recorder.state.value.isActive) {
                recorder.updateLiveSession { it.withoutSegment(segment.id) }
            } else {
                viewed = updated
                transcript.render(updated)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Small components
    // ---------------------------------------------------------------------------------------

    /** "● Call · Live" with a level bar: one per audio source. */
    private inner class StreamLight(private val name: String, private val source: AudioSource) :
        JPanel(MigLayout("insets 0, gap 4")) {
        private val label = JLabel()
        private val level = JProgressBar(0, 100).apply { preferredSize = Dimension(44, 6) }

        init {
            isOpaque = false
            add(label)
            add(level)
        }

        fun render(status: StreamStatus) {
            val (color, text) = when (status.state) {
                StreamState.OFF -> (UIManager.getColor("Label.disabledForeground") ?: Color.GRAY) to strings.statusOff
                StreamState.CONNECTING -> (UIManager.getColor("Actions.Yellow") ?: Color.ORANGE) to strings.statusConnecting
                StreamState.LIVE -> TranscriptView.sourceColor(source) to strings.statusLive
                StreamState.FAILED -> (UIManager.getColor("Actions.Red") ?: Color.RED) to strings.statusFailed
            }
            label.foreground = color
            label.text = "● $name · $text"
            toolTipText = listOfNotNull(status.deviceName.ifEmpty { null }, status.error).joinToString(" — ").ifEmpty { null }
            level.isVisible = status.state == StreamState.LIVE
            // Speech sits far below full scale; a square root makes normal talking fill the bar.
            level.value = (kotlin.math.sqrt(status.level.toDouble()) * 160).toInt().coerceIn(0, 100)
        }
    }

    private inner class SessionCellRenderer : DefaultListCellRenderer() {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val session = value as? ConversationSession ?: return this
            val status = when (session.status) {
                SessionStatus.RECORDING -> " · ${strings.statusRecording}"
                SessionStatus.INTERRUPTED -> " · ${strings.statusInterrupted}"
                SessionStatus.COMPLETED -> ""
            }
            val details = "${dateFormat.format(Date(session.createdAt))} · ${formatTimestamp(session.durationMillis)} · " +
                strings.lines(session.segments.count { it.text.isNotBlank() }) + status
            text = "<html><b>${escape(session.title)}</b><br><span style='font-size:90%'>${escape(details)}</span></html>"
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            return this
        }
    }

    private inner class LanguageRenderer(private val checkModelInstalled: Boolean = false) : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val lang = value as? LanguageCode
            val baseLabel = lang?.getDisplayName(autoDetectLabel = strings.autoDetect) ?: value.toString()
            val label = if (checkModelInstalled && lang != null && lang != LanguageCode.AUTO && !feature.isModelInstalled(lang)) {
                strings.modelNotInstalledTag(baseLabel)
            } else {
                baseLabel
            }
            return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
        }
    }

    private fun html(text: String) = "<html>${escape(text).replace("\n", "<br>")}</html>"

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val SHOW_DELAY_MILLIS = 320
        const val HIDE_DELAY_MILLIS = 450
        const val MAX_PHRASE_CHARS = 600
        const val MAX_MEANINGS = 6
    }
}
