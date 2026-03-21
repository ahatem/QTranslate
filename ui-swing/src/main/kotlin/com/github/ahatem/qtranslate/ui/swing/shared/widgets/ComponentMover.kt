package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.sign

/**
 * Enables dragging of Swing components within their parent container or to a specified destination.
 *
 * This class provides smooth, constrained component dragging with configurable snap-to-grid,
 * boundary checking, and cursor management.
 *
 * **Thread Safety:** All public methods must be called from the Event Dispatch Thread.
 *
 * **Usage Example:**
 * ```kotlin
 * val mover = ComponentMover.builder()
 *     .dragInsets(Insets(5, 5, 5, 5))
 *     .snapSize(Dimension(10, 10))
 *     .changeCursor(true)
 *     .build()
 *
 * mover.register(myComponent)
 *
 * // When done:
 * mover.dispose()
 * ```
 *
 * @property config The configuration settings for this component mover
 */
class ComponentMover private constructor(
    private val config: Config
) : MouseAdapter(), AutoCloseable {

    // Thread-safe state management
    @Volatile
    private var potentialDrag = AtomicBoolean(false)
    @Volatile
    private var source: Component? = null
    @Volatile
    private var destination: Component? = null

    // Drag state (accessed only from EDT)
    private var pressed: Point? = null
    private var location: Point? = null
    private var originalCursor: Cursor? = null
    private var autoscrolls = false

    // Cache for performance
    private var cachedBounds: Dimension? = null
    private var lastBoundsComponent: Component? = null

    // Track registered components for cleanup
    private val registeredComponents = mutableSetOf<Component>()

    private val isDisposed = AtomicBoolean(false)

    /**
     * Configuration for ComponentMover behavior.
     */
    data class Config(
        val destinationClass: Class<*>? = null,
        val destinationComponent: Component? = null,
        val dragInsets: Insets = Insets(0, 0, 0, 0),
        val edgeInsets: Insets = Insets(0, 0, 0, 0),
        val snapSize: Dimension = Dimension(1, 1),
        val changeCursor: Boolean = true,
        val autoLayout: Boolean = false,
        val constraints: List<DragConstraint> = emptyList()
    ) {
        init {
            require(destinationClass == null || destinationComponent == null) {
                "Cannot specify both destinationClass and destinationComponent"
            }
            require(snapSize.width > 0 && snapSize.height > 0) {
                "Snap size must be positive (width=${snapSize.width}, height=${snapSize.height})"
            }
        }
    }

    /**
     * Functional interface for custom drag constraints.
     */
    fun interface DragConstraint {
        /**
         * Modifies the proposed location based on custom logic.
         *
         * @param proposedLocation The calculated new location
         * @param component The component being dragged
         * @return The constrained location
         */
        fun constrain(proposedLocation: Point, component: Component): Point
    }

    /**
     * Registers components to be draggable.
     *
     * @param components Components to enable dragging for
     */
    fun register(vararg components: Component) {
        requireEDT()
        checkNotDisposed()

        components.forEach { component ->
            component.addMouseListener(this)
            registeredComponents += component
        }
    }

    /**
     * Deregisters components, removing drag capability.
     *
     * @param components Components to disable dragging for
     */
    fun deregister(vararg components: Component) {
        requireEDT()

        components.forEach { component ->
            component.removeMouseListener(this)
            registeredComponents -= component
        }
    }

    override fun mousePressed(e: MouseEvent) {
        requireEDT()
        if (isDisposed.get()) return

        val src = e.component
        val draggableArea = Rectangle(
            config.dragInsets.left,
            config.dragInsets.top,
            src.width - config.dragInsets.left - config.dragInsets.right,
            src.height - config.dragInsets.top - config.dragInsets.bottom
        )

        if (draggableArea.contains(e.point)) {
            setupDragging(e)
        }
    }

    private fun setupDragging(e: MouseEvent) {
        source = e.component
        source?.addMouseMotionListener(this)
        potentialDrag.set(true)

        destination = config.destinationComponent ?: when (config.destinationClass) {
            null -> source
            else -> SwingUtilities.getAncestorOfClass(config.destinationClass, source)
        }

        pressed = e.locationOnScreen
        location = destination?.location

        if (config.changeCursor) {
            originalCursor = source?.cursor
            source?.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        }

        (destination as? JComponent)?.let { jc ->
            autoscrolls = jc.autoscrolls
            jc.autoscrolls = false
        }

        // Clear bounds cache when starting new drag
        cachedBounds = null
        lastBoundsComponent = null
    }

    override fun mouseDragged(e: MouseEvent) {
        requireEDT()
        if (!potentialDrag.get()) return

        val dest = destination ?: return
        val pressPoint = pressed ?: return
        val origLocation = location ?: return

        val dragged = e.locationOnScreen
        val dx = calculateDragDistance(
            dragged.x,
            pressPoint.x,
            config.snapSize.width
        )
        val dy = calculateDragDistance(
            dragged.y,
            pressPoint.y,
            config.snapSize.height
        )

        val bounds = getBoundingSize(dest)
        val proposedLocation = calculateConstrainedLocation(
            origLocation.x + dx,
            origLocation.y + dy,
            dest,
            bounds
        )

        // Apply custom constraints
        val finalLocation = config.constraints.fold(proposedLocation) { loc, constraint ->
            constraint.constrain(loc, dest)
        }

        dest.setLocation(finalLocation.x, finalLocation.y)
    }

    override fun mouseReleased(e: MouseEvent) {
        requireEDT()
        if (!potentialDrag.get()) return

        cleanup()

        if (config.autoLayout) {
            (destination as? JComponent)?.revalidate() ?: destination?.validate()
        }
    }

    /**
     * Calculates drag distance with snap-to-grid behavior.
     */
    private fun calculateDragDistance(current: Int, initial: Int, snap: Int): Int {
        if (snap <= 1) return current - initial

        val delta = current - initial
        val snapCount = delta / snap
        val remainder = abs(delta % snap)

        return snapCount * snap + when {
            remainder >= snap / 2 -> sign(delta.toFloat()).toInt() * snap
            else -> 0
        }
    }

    /**
     * Calculates new location constrained by edge insets and parent bounds.
     */
    private fun calculateConstrainedLocation(
        x: Int,
        y: Int,
        component: Component,
        bounds: Dimension
    ): Point {
        val maxX = (bounds.width - component.width - config.edgeInsets.right)
            .coerceAtLeast(config.edgeInsets.left)
        val maxY = (bounds.height - component.height - config.edgeInsets.bottom)
            .coerceAtLeast(config.edgeInsets.top)

        return Point(
            x.coerceIn(config.edgeInsets.left, maxX),
            y.coerceIn(config.edgeInsets.top, maxY)
        )
    }

    /**
     * Gets the bounding size for drag constraints with caching.
     */
    private fun getBoundingSize(component: Component): Dimension {
        // Use cache if same component
        if (component === lastBoundsComponent && cachedBounds != null) {
            return cachedBounds!!
        }

        val bounds = when (component) {
            is Window -> {
                val screenBounds = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .maximumWindowBounds
                Dimension(screenBounds.width, screenBounds.height)
            }

            else -> component.parent?.size ?: Dimension(0, 0)
        }

        lastBoundsComponent = component
        cachedBounds = bounds
        return bounds
    }

    /**
     * Cleans up drag state and restores original settings.
     */
    private fun cleanup() {
        source?.removeMouseMotionListener(this)
        potentialDrag.set(false)

        if (config.changeCursor) {
            source?.cursor = originalCursor
        }

        (destination as? JComponent)?.let { jc ->
            jc.autoscrolls = autoscrolls
        }

        source = null
        destination = null
        pressed = null
        location = null
        originalCursor = null
    }

    /**
     * Disposes of this ComponentMover, deregistering all components and cleaning up resources.
     */
    override fun close() {
        if (isDisposed.compareAndSet(false, true)) {
            requireEDT()

            cleanup()
            deregister(*registeredComponents.toTypedArray())
            registeredComponents.clear()

            cachedBounds = null
            lastBoundsComponent = null
        }
    }

    /**
     * Alias for close() to match common disposal patterns.
     */
    fun dispose() = close()

    private fun requireEDT() {
        require(SwingUtilities.isEventDispatchThread()) {
            "ComponentMover must be accessed from the Event Dispatch Thread"
        }
    }

    private fun checkNotDisposed() {
        check(!isDisposed.get()) {
            "ComponentMover has been disposed and cannot be reused"
        }
    }

    companion object {
        /**
         * Creates a new builder for configuring a ComponentMover.
         */
        @JvmStatic
        fun builder() = Builder()

        /**
         * Creates a ComponentMover with default configuration.
         */
        @JvmStatic
        fun create() = Builder().build()
    }

    /**
     * Builder for creating ComponentMover instances with fluent API.
     */
    class Builder {
        private var destinationClass: Class<*>? = null
        private var destinationComponent: Component? = null
        private var dragInsets = Insets(0, 0, 0, 0)
        private var edgeInsets = Insets(0, 0, 0, 0)
        private var snapSize = Dimension(1, 1)
        private var changeCursor = true
        private var autoLayout = false
        private val constraints = mutableListOf<DragConstraint>()

        /**
         * Sets the destination ancestor class type to search for.
         */
        fun destinationClass(clazz: Class<*>?) = apply {
            this.destinationClass = clazz
        }

        /**
         * Sets an explicit destination component.
         */
        fun destinationComponent(component: Component?) = apply {
            this.destinationComponent = component
        }

        /**
         * Sets insets defining the draggable area within the component.
         */
        fun dragInsets(insets: Insets) = apply {
            this.dragInsets = insets
        }

        /**
         * Sets insets defining boundaries within the parent container.
         */
        fun edgeInsets(insets: Insets) = apply {
            this.edgeInsets = insets
        }

        /**
         * Sets snap-to-grid size for drag movements.
         */
        fun snapSize(size: Dimension) = apply {
            this.snapSize = size
        }

        /**
         * Sets snap-to-grid size using width and height.
         */
        fun snapSize(width: Int, height: Int) = apply {
            this.snapSize = Dimension(width, height)
        }

        /**
         * Enables/disables cursor change during drag.
         */
        fun changeCursor(change: Boolean) = apply {
            this.changeCursor = change
        }

        /**
         * Enables/disables automatic layout refresh after drag.
         */
        fun autoLayout(auto: Boolean) = apply {
            this.autoLayout = auto
        }

        /**
         * Adds a custom drag constraint.
         */
        fun addConstraint(constraint: DragConstraint) = apply {
            this.constraints += constraint
        }

        /**
         * Adds multiple custom drag constraints.
         */
        fun addConstraints(vararg constraints: DragConstraint) = apply {
            this.constraints += constraints
        }

        /**
         * Builds the ComponentMover instance.
         */
        fun build() = ComponentMover(
            Config(
                destinationClass = destinationClass,
                destinationComponent = destinationComponent,
                dragInsets = dragInsets,
                edgeInsets = edgeInsets,
                snapSize = snapSize,
                changeCursor = changeCursor,
                autoLayout = autoLayout,
                constraints = constraints.toList()
            )
        )
    }
}

// Extension functions for Kotlin-idiomatic usage
/**
 * Extension function to make any Component draggable with default settings.
 */
fun Component.makeDraggable(
    configurator: ComponentMover.Builder.() -> Unit = {}
): ComponentMover {
    val mover = ComponentMover.builder()
        .apply(configurator)
        .build()
    mover.register(this)
    return mover
}

/**
 * Extension function to make multiple Components draggable with shared settings.
 */
fun Collection<Component>.makeDraggable(
    configurator: ComponentMover.Builder.() -> Unit = {}
): ComponentMover {
    val mover = ComponentMover.builder()
        .apply(configurator)
        .build()
    mover.register(*this.toTypedArray())
    return mover
}