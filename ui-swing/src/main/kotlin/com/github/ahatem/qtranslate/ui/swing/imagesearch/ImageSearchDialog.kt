package com.github.ahatem.qtranslate.ui.swing.imagesearch

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.util.toDimension
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ComponentMover
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ComponentResizer
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Frame
import java.awt.GridLayout
import java.awt.Image
import java.awt.Insets
import java.awt.MouseInfo
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

/**
 * Floating popup showing pictures for a term.
 *
 * The visual sibling of the quick dictionary popup — undecorated, always on top, movable,
 * resizable, dismissed with Escape — because it answers the same question from the same gesture
 * on the same selection, and behaving differently would make it feel like a different app.
 *
 * It deliberately does *not* copy that dialog's idle-hide timer. A definition is read in a couple
 * of seconds; a grid of pictures is looked over, compared, and clicked through, and a popup that
 * vanishes mid-comparison would be worse than one the reader closes themselves.
 */
class ImageSearchDialog(
    private val owner: Frame,
    private val iconManager: IconManager
) : JDialog(owner, ModalityType.MODELESS), Renderable<ImageSearchDialogState> {

    private companion object {
        const val RESIZE_HANDLE_SIZE = 8
        const val PINNED_BORDER_WIDTH = 4
        const val COLUMNS = 3
        const val TILE_HEIGHT = 132
        const val GRID_GAP = 8
    }

    private val borderColor: Color? = UIManager.getColor("Component.borderColor")
    private val accentColor: Color? = UIManager.getColor("Component.focusedBorderColor")
        ?: UIManager.getColor("Component.accentColor")
        ?: borderColor

    private val thumbnails = ThumbnailLoader()

    private val titleLabel = JLabel("").apply { putClientProperty("FlatLaf.styleClass", "h4") }
    private val pinButton = createButtonWithIcon(iconManager, "icons/lucide/pin.svg", 14)
    private val closeButton = createButtonWithIcon(iconManager, "icons/lucide/close.svg", 16)

    private val searchField = JTextField().apply {
        addActionListener { currentState?.onSearch?.invoke(text.trim()) }
    }

    private val hintLabel = JLabel("", SwingConstants.CENTER).apply {
        foreground = UIManager.getColor("Label.disabledForeground")
        border = EmptyBorder(24, 12, 24, 12)
    }

    private val grid = JPanel(GridLayout(0, COLUMNS, GRID_GAP, GRID_GAP)).apply {
        border = EmptyBorder(8, 8, 8, 8)
    }

    private val scroll = JScrollPane(grid).apply {
        border = null
        viewportBorder = null
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = 16
    }

    private val body = JPanel(BorderLayout())

    /** Kept as a field because it is both the drag handle and the title row. */
    private lateinit var header: JComponent

    private var currentState: ImageSearchDialogState? = null
    private var isPinned = false
    private var wasManuallyMoved = false

    /**
     * The results the grid was last built from.
     *
     * Rebuilding tiles on every render would restart every thumbnail fetch and lose the reader's
     * scroll position, and render runs on any state change — including ones this dialog does not
     * care about.
     */
    private var renderedResults: List<ImageResult> = emptyList()

    init {
        isUndecorated = true
        isAlwaysOnTop = true
        focusableWindowState = true
        defaultCloseOperation = DO_NOTHING_ON_CLOSE

        pinButton.addActionListener { currentState?.onPinToggled?.invoke() }
        closeButton.addActionListener { currentState?.onClose?.invoke() }

        header = buildHeader()
        contentPane = JPanel(BorderLayout()).apply {
            border = BorderFactory.createLineBorder(borderColor ?: Color.GRAY, 1)
            add(header, BorderLayout.NORTH)
            add(body.apply { add(scroll, BorderLayout.CENTER) }, BorderLayout.CENTER)
        }

        installEscapeToClose()
        installMoveAndResize()
    }

    private fun buildHeader(): JComponent {
        val buttons = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(pinButton)
            add(Box.createHorizontalStrut(2))
            add(closeButton)
        }

        val titleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.CENTER)
            add(buttons, BorderLayout.LINE_END)
        }

        return JPanel(BorderLayout(0, 6)).apply {
            border = EmptyBorder(8, 10, 8, 8)
            add(titleRow, BorderLayout.NORTH)
            add(searchField, BorderLayout.CENTER)
        }
    }

    override fun render(state: ImageSearchDialogState) {
        val visibilityChanged = isVisible != state.isVisible

        if (visibilityChanged) {
            if (state.isVisible) {
                currentState = state
                wasManuallyMoved = false
                applyText(state)
                if (state.searchedTerm.isNotBlank() && searchField.text != state.searchedTerm) {
                    searchField.text = state.searchedTerm
                }
                rebuildGridIfChanged(state)
                size = state.config.lastKnownSize.toDimension()
                applyPosition(state.config)
                isVisible = true
                searchField.requestFocusInWindow()
            } else {
                saveGeometry()
                isVisible = false
            }
            return
        }

        if (!isVisible) return

        val pinChanged = isPinned != state.isPinned
        currentState = state
        applyText(state)
        rebuildGridIfChanged(state)
        if (pinChanged) applyPinStyle(state.isPinned)
    }

    private fun applyText(state: ImageSearchDialogState) {
        isPinned = state.isPinned
        titleLabel.text = state.strings.title
        pinButton.toolTipText = if (state.isPinned) state.strings.unpinTooltip else state.strings.pinTooltip
        closeButton.toolTipText = state.strings.closeTooltip

        hintLabel.text = when {
            state.isLoading -> state.strings.loadingMessage
            state.hasFailed -> state.strings.errorMessage
            state.searchedTerm.isNotBlank() && state.results.isEmpty() -> state.strings.notFoundMessage
            else -> state.strings.hintMessage
        }
    }

    /**
     * Rebuilds the tiles only when the results themselves changed.
     */
    private fun rebuildGridIfChanged(state: ImageSearchDialogState) {
        val showingPlaceholder = state.results.isEmpty()
        if (showingPlaceholder) {
            if (renderedResults.isNotEmpty() || body.componentCount == 0 || body.getComponent(0) !== hintLabel) {
                renderedResults = emptyList()
                grid.removeAll()
                body.removeAll()
                body.add(hintLabel, BorderLayout.CENTER)
                body.revalidate()
                body.repaint()
            }
            return
        }

        if (state.results == renderedResults) return
        renderedResults = state.results

        grid.removeAll()
        state.results.forEach { grid.add(tileFor(it, state)) }

        body.removeAll()
        body.add(scroll, BorderLayout.CENTER)
        body.revalidate()
        body.repaint()
    }

    /**
     * One picture, its caption, and its credit.
     *
     * The credit is shown rather than tucked into a tooltip because the licences these images
     * carry require it to be visible, and a tooltip is not.
     */
    private fun tileFor(result: ImageResult, state: ImageSearchDialogState): JComponent {
        val picture = JLabel("", SwingConstants.CENTER).apply {
            preferredSize = Dimension(0, UIScale.scale(TILE_HEIGHT))
            border = BorderFactory.createLineBorder(borderColor ?: Color.GRAY, 1)
        }

        val caption = JLabel(result.title.orEmpty()).apply {
            putClientProperty("FlatLaf.styleClass", "small")
            toolTipText = result.title
        }

        val credit = JLabel(creditFor(result)).apply {
            putClientProperty("FlatLaf.styleClass", "mini")
            foreground = UIManager.getColor("Label.disabledForeground")
            toolTipText = creditFor(result)
        }

        thumbnails.load(result.thumbnailUrl) { image ->
            // The grid may have been rebuilt by a newer search while this was in flight.
            if (picture.parent == null) return@load
            picture.icon = ImageIcon(scaleToFit(image, picture.width, picture.height))
        }

        return JPanel(BorderLayout(0, 2)).apply {
            add(picture, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(caption, BorderLayout.NORTH)
                    add(credit, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH
            )
            toolTipText = state.strings.openTooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // The description page, not the bare image: it carries the licence, the full
                    // caption and the context, which is what a reader looking something up wants.
                    state.onImageOpened(result)
                }
            })
        }
    }

    private fun creditFor(result: ImageResult): String =
        listOfNotNull(result.attribution, result.license).joinToString(" · ")

    /**
     * Scales to fit inside the tile without distorting it — a stretched diagram is harder to read
     * than a small one.
     */
    private fun scaleToFit(image: Image, boxWidth: Int, boxHeight: Int): Image {
        val width = image.getWidth(null).takeIf { it > 0 } ?: return image
        val height = image.getHeight(null).takeIf { it > 0 } ?: return image
        val targetWidth = boxWidth.takeIf { it > 0 } ?: return image
        val targetHeight = boxHeight.takeIf { it > 0 } ?: return image

        val scale = minOf(targetWidth.toDouble() / width, targetHeight.toDouble() / height)
        if (scale >= 1.0) return image
        return image.getScaledInstance((width * scale).toInt(), (height * scale).toInt(), Image.SCALE_SMOOTH)
    }

    private fun applyPinStyle(pinned: Boolean) {
        (contentPane as JPanel).border = if (pinned) {
            BorderFactory.createLineBorder(accentColor ?: Color.GRAY, PINNED_BORDER_WIDTH)
        } else {
            BorderFactory.createLineBorder(borderColor ?: Color.GRAY, 1)
        }
        contentPane.revalidate()
        contentPane.repaint()
    }

    private fun applyPosition(config: ImageSearchConfig) {
        if (wasManuallyMoved) return
        val screen = graphicsConfiguration?.bounds ?: run {
            setLocationRelativeTo(owner)
            return
        }
        if (!config.positionNearMouse) {
            setLocationRelativeTo(owner)
            return
        }
        val mouse = MouseInfo.getPointerInfo()?.location ?: run {
            setLocationRelativeTo(owner)
            return
        }
        val x = (mouse.x + UIScale.scale(12))
            .coerceIn(screen.x, (screen.x + screen.width - width).coerceAtLeast(screen.x))
        val y = (mouse.y + UIScale.scale(12))
            .coerceIn(screen.y, (screen.y + screen.height - height).coerceAtLeast(screen.y))
        setLocation(x, y)
    }

    private fun saveGeometry() {
        currentState?.onSavePosition?.invoke(Position(location.x.coerceAtLeast(0), location.y.coerceAtLeast(0)))
        currentState?.onSaveSize?.invoke(Size(size.width, size.height))
    }

    private fun installEscapeToClose() {
        val root = rootPane
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-image-search")
        root.actionMap.put("close-image-search", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                currentState?.onClose?.invoke()
            }
        })
    }

    private fun installMoveAndResize() {
        minimumSize = Dimension(UIScale.scale(340), UIScale.scale(280))

        ComponentMover.builder()
            .destinationComponent(this)
            .build()
            .register(header)

        val handle = UIScale.scale(RESIZE_HANDLE_SIZE)
        ComponentResizer.builder()
            .dragInsets(Insets(handle, handle, handle, handle))
            .minimumSize(minimumSize)
            .onResizeEnd { currentState?.onSaveSize?.invoke(Size(size.width, size.height)) }
            .build()
            .register(this)

        header.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                wasManuallyMoved = true
                currentState?.onSavePosition
                    ?.invoke(Position(location.x.coerceAtLeast(0), location.y.coerceAtLeast(0)))
            }
        })
    }

    /** Releases the thumbnail pool; the dialog is not usable afterwards. */
    fun dispose(shutdownThumbnails: Boolean) {
        if (shutdownThumbnails) thumbnails.shutdown()
        super.dispose()
    }
}
