package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.*
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Enables interactive resizing of Swing components by dragging their edges and corners.
 *
 * This class provides smooth, constrained component resizing with configurable snap-to-grid,
 * minimum/maximum size constraints, and cursor feedback.
 *
 * **Thread Safety:** All public methods must be called from the Event Dispatch Thread.
 *
 * **Usage Example:**
 * ```kotlin
 * val resizer = ComponentResizer.builder()
 *     .dragInsets(Insets(5, 5, 5, 5))
 *     .snapSize(10, 10)
 *     .minimumSize(Dimension(100, 100))
 *     .build()
 *
 * resizer.register(myComponent)
 *
 * // When done:
 * resizer.dispose()
 * ```
 *
 * @property config The configuration settings for this component resizer
 */
class ComponentResizer private constructor(
    private val config: Config
) : java.awt.event.MouseAdapter(), AutoCloseable {

    // Thread-safe state
    @Volatile
    private var resizing = AtomicBoolean(false)
    private val isDisposed = AtomicBoolean(false)

    // Resize state (accessed only from EDT)
    private var direction = ResizeDirection.NONE
    private var sourceCursor: Cursor? = null
    private var bounds: Rectangle? = null
    private var pressed: Point? = null
    private var autoscrolls = false

    // Track registered components for cleanup
    private val registeredComponents = mutableSetOf<Component>()

    /**
     * Configuration for ComponentResizer behavior.
     */
    data class Config(
        val dragInsets: Insets = Insets(5, 5, 5, 5),
        val snapSize: Dimension = Dimension(1, 1),
        val minimumSize: Dimension = DEFAULT_MINIMUM_SIZE,
        val maximumSize: Dimension = DEFAULT_MAXIMUM_SIZE,
        val constraints: List<ResizeConstraint> = emptyList(),
        val onResizeStart: (() -> Unit)? = null,
        val onResizeEnd: (() -> Unit)? = null
    ) {
        init {
            require(snapSize.width > 0 && snapSize.height > 0) {
                "Snap size must be positive (width=${snapSize.width}, height=${snapSize.height})"
            }
            validateMinimumSize(minimumSize, dragInsets)
        }

        private fun validateMinimumSize(min: Dimension, insets: Insets) {
            val minWidth = insets.left + insets.right
            val minHeight = insets.top + insets.bottom
            require(min.width >= minWidth && min.height >= minHeight) {
                "Minimum size ($min) cannot be less than drag insets ($minWidth x $minHeight)"
            }
        }
    }

    /**
     * Represents resize direction using bitwise flags.
     */
    @JvmInline
    private value class ResizeDirection(val value: Int) {
        operator fun plus(other: ResizeDirection) = ResizeDirection(value or other.value)
        infix fun and(other: ResizeDirection) = value and other.value != 0
        fun isNone() = value == 0

        companion object {
            val NONE = ResizeDirection(0)
            val NORTH = ResizeDirection(1)
            val WEST = ResizeDirection(2)
            val SOUTH = ResizeDirection(4)
            val EAST = ResizeDirection(8)

            val CURSOR_MAP = mapOf(
                NORTH.value to Cursor.N_RESIZE_CURSOR,
                WEST.value to Cursor.W_RESIZE_CURSOR,
                SOUTH.value to Cursor.S_RESIZE_CURSOR,
                EAST.value to Cursor.E_RESIZE_CURSOR,
                (NORTH + WEST).value to Cursor.NW_RESIZE_CURSOR,
                (NORTH + EAST).value to Cursor.NE_RESIZE_CURSOR,
                (SOUTH + WEST).value to Cursor.SW_RESIZE_CURSOR,
                (SOUTH + EAST).value to Cursor.SE_RESIZE_CURSOR
            )
        }
    }

    /**
     * Functional interface for custom resize constraints.
     */
    fun interface ResizeConstraint {
        fun constrain(proposedBounds: Rectangle, component: Component): Rectangle
    }

    fun register(vararg components: Component) {
        requireEDT()
        checkNotDisposed()

        components.forEach { component ->
            component.addMouseListener(this)
            component.addMouseMotionListener(this)
            registeredComponents += component
        }
    }

    fun deregister(vararg components: Component) {
        requireEDT()

        components.forEach { component ->
            component.removeMouseListener(this)
            component.removeMouseMotionListener(this)
            registeredComponents -= component
        }
    }

    override fun mouseMoved(e: MouseEvent) {
        requireEDT()
        if (isDisposed.get()) return

        val component = e.component
        val point = e.point

        direction = calculateResizeDirection(point, component)

        component.cursor = when {
            direction.isNone() -> sourceCursor
            else -> Cursor.getPredefinedCursor(
                ResizeDirection.CURSOR_MAP[direction.value] ?: Cursor.DEFAULT_CURSOR
            )
        }
    }

    override fun mouseEntered(e: MouseEvent) {
        requireEDT()
        if (!resizing.get()) {
            sourceCursor = e.component.cursor
        }
    }

    override fun mouseExited(e: MouseEvent) {
        requireEDT()
        if (!resizing.get()) {
            e.component.cursor = sourceCursor
        }
    }

    override fun mousePressed(e: MouseEvent) {
        requireEDT()
        if (direction.isNone() || isDisposed.get()) return

        val component = e.component
        resizing.set(true)

        // ⚡ Invoke resize start callback
        config.onResizeStart?.invoke()

        pressed = e.point.apply {
            SwingUtilities.convertPointToScreen(this, component)
        }
        bounds = component.bounds

        (component as? JComponent)?.let { jc ->
            autoscrolls = jc.autoscrolls
            jc.autoscrolls = false
        }
    }

    override fun mouseReleased(e: MouseEvent) {
        requireEDT()
        if (!resizing.get()) return

        resizing.set(false)

        // ⚡ Invoke resize end callback
        config.onResizeEnd?.invoke()

        val component = e.component
        component.cursor = sourceCursor

        (component as? JComponent)?.let { jc ->
            jc.autoscrolls = autoscrolls
        }

        bounds = null
        pressed = null
    }

    override fun mouseDragged(e: MouseEvent) {
        requireEDT()
        if (!resizing.get()) return

        val component = e.component
        val currentBounds = bounds ?: return
        val pressPoint = pressed ?: return

        val currentPoint = e.point.apply {
            SwingUtilities.convertPointToScreen(this, component)
        }

        val newBounds = calculateNewBounds(
            component,
            direction,
            currentBounds,
            pressPoint,
            currentPoint
        )

        val finalBounds = config.constraints.fold(newBounds) { rect, constraint ->
            constraint.constrain(rect, component)
        }

        component.bounds = finalBounds
        component.validate()
    }

    private fun calculateResizeDirection(point: Point, component: Component): ResizeDirection {
        var dir = ResizeDirection.NONE

        if (point.x < config.dragInsets.left) {
            dir += ResizeDirection.WEST
        }
        if (point.x > component.width - config.dragInsets.right - 1) {
            dir += ResizeDirection.EAST
        }
        if (point.y < config.dragInsets.top) {
            dir += ResizeDirection.NORTH
        }
        if (point.y > component.height - config.dragInsets.bottom - 1) {
            dir += ResizeDirection.SOUTH
        }

        return dir
    }

    private fun calculateNewBounds(
        component: Component,
        dir: ResizeDirection,
        startBounds: Rectangle,
        startPoint: Point,
        currentPoint: Point
    ): Rectangle {
        var x = startBounds.x
        var y = startBounds.y
        var width = startBounds.width
        var height = startBounds.height

        if (dir and ResizeDirection.WEST) {
            val drag = calculateDragDistance(startPoint.x, currentPoint.x, config.snapSize.width)
            val maxWidth = minOf(width + x, config.maximumSize.width)
            val constrainedDrag = constrainDrag(
                drag,
                config.snapSize.width,
                width,
                config.minimumSize.width,
                maxWidth
            )
            x -= constrainedDrag
            width += constrainedDrag
        }

        if (dir and ResizeDirection.NORTH) {
            val drag = calculateDragDistance(startPoint.y, currentPoint.y, config.snapSize.height)
            val maxHeight = minOf(height + y, config.maximumSize.height)
            val constrainedDrag = constrainDrag(
                drag,
                config.snapSize.height,
                height,
                config.minimumSize.height,
                maxHeight
            )
            y -= constrainedDrag
            height += constrainedDrag
        }

        if (dir and ResizeDirection.EAST) {
            val drag = calculateDragDistance(currentPoint.x, startPoint.x, config.snapSize.width)
            val parentBounds = getParentSize(component)
            val maxWidth = minOf(parentBounds.width - x, config.maximumSize.width)
            val constrainedDrag = constrainDrag(
                drag,
                config.snapSize.width,
                width,
                config.minimumSize.width,
                maxWidth
            )
            width += constrainedDrag
        }

        if (dir and ResizeDirection.SOUTH) {
            val drag = calculateDragDistance(currentPoint.y, startPoint.y, config.snapSize.height)
            val parentBounds = getParentSize(component)
            val maxHeight = minOf(parentBounds.height - y, config.maximumSize.height)
            val constrainedDrag = constrainDrag(
                drag,
                config.snapSize.height,
                height,
                config.minimumSize.height,
                maxHeight
            )
            height += constrainedDrag
        }

        return Rectangle(x, y, width, height)
    }

    private fun calculateDragDistance(larger: Int, smaller: Int, snap: Int): Int {
        if (snap <= 1) return larger - smaller

        val halfway = snap / 2
        var drag = larger - smaller
        drag += if (drag < 0) -halfway else halfway
        return (drag / snap) * snap
    }

    private fun constrainDrag(
        drag: Int,
        snap: Int,
        currentSize: Int,
        minSize: Int,
        maxSize: Int
    ): Int {
        var constrainedDrag = drag

        while (currentSize + constrainedDrag < minSize) {
            constrainedDrag += snap
        }

        while (currentSize + constrainedDrag > maxSize) {
            constrainedDrag -= snap
        }

        return constrainedDrag
    }

    private fun getParentSize(component: Component): Dimension {
        return when (component) {
            is Window -> {
                val screenBounds = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .maximumWindowBounds
                Dimension(screenBounds.width, screenBounds.height)
            }
            else -> component.parent?.size ?: Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    override fun close() {
        if (isDisposed.compareAndSet(false, true)) {
            requireEDT()

            deregister(*registeredComponents.toTypedArray())
            registeredComponents.clear()

            resizing.set(false)
            bounds = null
            pressed = null
            sourceCursor = null
        }
    }

    fun dispose() = close()

    private fun requireEDT() {
        require(SwingUtilities.isEventDispatchThread()) {
            "ComponentResizer must be accessed from the Event Dispatch Thread"
        }
    }

    private fun checkNotDisposed() {
        check(!isDisposed.get()) {
            "ComponentResizer has been disposed and cannot be reused"
        }
    }

    companion object {
        private val DEFAULT_MINIMUM_SIZE = Dimension(10, 10)
        private val DEFAULT_MAXIMUM_SIZE = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        @JvmStatic
        fun builder() = Builder()

        @JvmStatic
        fun create() = Builder().build()
    }

    class Builder {
        private var dragInsets = Insets(5, 5, 5, 5)
        private var snapSize = Dimension(1, 1)
        private var minimumSize = DEFAULT_MINIMUM_SIZE
        private var maximumSize = DEFAULT_MAXIMUM_SIZE
        private val constraints = mutableListOf<ResizeConstraint>()
        private var onResizeStart: (() -> Unit)? = null
        private var onResizeEnd: (() -> Unit)? = null

        fun dragInsets(insets: Insets) = apply {
            this.dragInsets = insets
        }

        fun dragInsets(top: Int, left: Int, bottom: Int, right: Int) = apply {
            this.dragInsets = Insets(top, left, bottom, right)
        }

        fun snapSize(size: Dimension) = apply {
            this.snapSize = size
        }

        fun snapSize(width: Int, height: Int) = apply {
            this.snapSize = Dimension(width, height)
        }

        fun minimumSize(size: Dimension) = apply {
            this.minimumSize = size
        }

        fun minimumSize(width: Int, height: Int) = apply {
            this.minimumSize = Dimension(width, height)
        }

        fun maximumSize(size: Dimension) = apply {
            this.maximumSize = size
        }

        fun maximumSize(width: Int, height: Int) = apply {
            this.maximumSize = Dimension(width, height)
        }

        fun addConstraint(constraint: ResizeConstraint) = apply {
            this.constraints += constraint
        }

        fun addConstraints(vararg constraints: ResizeConstraint) = apply {
            this.constraints += constraints
        }

        /**
         * Sets callback for when resize starts
         */
        fun onResizeStart(callback: () -> Unit) = apply {
            this.onResizeStart = callback
        }

        /**
         * Sets callback for when resize ends
         */
        fun onResizeEnd(callback: () -> Unit) = apply {
            this.onResizeEnd = callback
        }

        fun build() = ComponentResizer(
            Config(
                dragInsets = dragInsets,
                snapSize = snapSize,
                minimumSize = minimumSize,
                maximumSize = maximumSize,
                constraints = constraints.toList(),
                onResizeStart = onResizeStart,
                onResizeEnd = onResizeEnd
            )
        )
    }
}

// Extension functions
fun Component.makeResizable(
    configurator: ComponentResizer.Builder.() -> Unit = {}
): ComponentResizer {
    val resizer = ComponentResizer.builder()
        .apply(configurator)
        .build()
    resizer.register(this)
    return resizer
}

fun Collection<Component>.makeResizable(
    configurator: ComponentResizer.Builder.() -> Unit = {}
): ComponentResizer {
    val resizer = ComponentResizer.builder()
        .apply(configurator)
        .build()
    resizer.register(*this.toTypedArray())
    return resizer
}

fun Component.makeDraggableAndResizable(
    moverConfig: ComponentMover.Builder.() -> Unit = {},
    resizerConfig: ComponentResizer.Builder.() -> Unit = {}
): Pair<ComponentMover, ComponentResizer> {
    val mover = makeDraggable(moverConfig)
    val resizer = makeResizable(resizerConfig)
    return mover to resizer
}

data class InteractiveComponent(
    val mover: ComponentMover,
    val resizer: ComponentResizer
) : AutoCloseable {
    override fun close() {
        mover.close()
        resizer.close()
    }
}

fun Component.makeInteractive(
    moverConfig: ComponentMover.Builder.() -> Unit = {},
    resizerConfig: ComponentResizer.Builder.() -> Unit = {}
): InteractiveComponent {
    return InteractiveComponent(
        mover = makeDraggable(moverConfig),
        resizer = makeResizable(resizerConfig)
    )
}