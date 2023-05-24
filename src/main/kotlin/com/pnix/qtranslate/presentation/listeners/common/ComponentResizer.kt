package com.pnix.qtranslate.presentation.listeners.common

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

class ComponentResizer(dragInsets: Insets?, snapSize: Dimension?, vararg components: Component?) : MouseAdapter() {
  init {
    cursors[1] = Cursor.N_RESIZE_CURSOR
    cursors[2] = Cursor.W_RESIZE_CURSOR
    cursors[4] = Cursor.S_RESIZE_CURSOR
    cursors[8] = Cursor.E_RESIZE_CURSOR
    cursors[3] = Cursor.NW_RESIZE_CURSOR
    cursors[9] = Cursor.NE_RESIZE_CURSOR
    cursors[6] = Cursor.SW_RESIZE_CURSOR
    cursors[12] = Cursor.SE_RESIZE_CURSOR
  }

  private var dragInsets: Insets? = null
  /**
   * Get the snap size.
   *
   * @return the snap size.
   */
  /**
   * Control how many pixels a border must be dragged before the size of
   * the component is changed. The border will snap to the size once
   * dragging has passed the halfway mark.
   *
   * @param snapSize Dimension object allows you to separately spcify a
   * horizontal and vertical snap size.
   */
  var snapSize: Dimension? = null
  private var direction = 0
  private var sourceCursor: Cursor? = null
  private var resizing = false
  private var bounds: Rectangle? = null
  private var pressed: Point? = null
  private var autoscrolls = false
  private var minimumSize = MINIMUM_SIZE
  /**
   * Get the components maximum size.
   *
   * @return the maximum size
   */
  /**
   * Specify the maximum size for the component. The component will still
   * be constrained by the size of its parent.
   *
   * @param maximumSize the maximum size for a component.
   */
  var maximumSize = MAXIMUM_SIZE

  /**
   * Convenience contructor. All borders are resizable in increments of
   * a single pixel. Components must be registered separately.
   */
  constructor() : this(Insets(5, 5, 5, 5), Dimension(1, 1))

  /**
   * Convenience contructor. All borders are resizable in increments of
   * a single pixel. Components can be registered when the class is created
   * or they can be registered separately afterwards.
   *
   * @param components components to be automatically registered
   */
  constructor(vararg components: Component?) : this(Insets(5, 5, 5, 5), Dimension(1, 1), *components)

  /**
   * Convenience contructor. Eligible borders are resisable in increments of
   * a single pixel. Components can be registered when the class is created
   * or they can be registered separately afterwards.
   *
   * @param dragInsets Insets specifying which borders are eligible to be
   * resized.
   * @param components components to be automatically registered
   */
  constructor(dragInsets: Insets?, vararg components: Component?) : this(dragInsets, Dimension(1, 1), *components)

  /**
   * Create a ComponentResizer.
   *
   * @param dragInsets Insets specifying which borders are eligible to be
   * resized.
   * @param snapSize Specify the dimension to which the border will snap to
   * when being dragged. Snapping occurs at the halfway mark.
   * @param components components to be automatically registered
   */
  init {
    setDragInsets(dragInsets)
    this.snapSize = snapSize
    registerComponent(*components)
  }

  /**
   * Get the drag insets
   *
   * @return  the drag insets
   */
  fun getDragInsets(): Insets? {
    return dragInsets
  }

  /**
   * Set the drag dragInsets. The insets specify an area where mouseDragged
   * events are recognized from the edge of the border inwards. A value of
   * 0 for any size will imply that the border is not resizable. Otherwise
   * the appropriate drag cursor will appear when the mouse is inside the
   * resizable border area.
   *
   * @param  dragInsets Insets to control which borders are resizeable.
   */
  fun setDragInsets(dragInsets: Insets?) {
    validateMinimumAndInsets(minimumSize, dragInsets)
    this.dragInsets = dragInsets
  }

  /**
   * Get the components minimum size.
   *
   * @return the minimum size
   */
  fun getMinimumSize(): Dimension {
    return minimumSize
  }

  /**
   * Specify the minimum size for the component. The minimum size is
   * constrained by the drag insets.
   *
   * @param minimumSize the minimum size for a component.
   */
  fun setMinimumSize(minimumSize: Dimension) {
    validateMinimumAndInsets(minimumSize, dragInsets)
    this.minimumSize = minimumSize
  }

  /**
   * Remove listeners from the specified component
   *
   * @param component  the component the listeners are removed from
   */
  fun deregisterComponent(vararg components: Component) {
    for (component in components) {
      component.removeMouseListener(this)
      component.removeMouseMotionListener(this)
    }
  }

  /**
   * Add the required listeners to the specified component
   *
   * @param component  the component the listeners are added to
   */
  fun registerComponent(vararg components: Component?) {
    for (component in components) {
      component?.addMouseListener(this)
      component?.addMouseMotionListener(this)
    }
  }

  /**
   * When the components minimum size is less than the drag insets then
   * we can't determine which border should be resized so we need to
   * prevent this from happening.
   */
  private fun validateMinimumAndInsets(minimum: Dimension, drag: Insets?) {
    val minimumWidth = drag!!.left + drag.right
    val minimumHeight = drag.top + drag.bottom
    if (minimum.width < minimumWidth
      || minimum.height < minimumHeight
    ) {
      val message = "Minimum size cannot be less than drag insets"
      throw IllegalArgumentException(message)
    }
  }

  /**
   */
  override fun mouseMoved(e: MouseEvent) {
    val source = e.component
    val location = e.point
    direction = 0
    if (location.x < dragInsets!!.left) direction += WEST
    if (location.x > source.width - dragInsets!!.right - 1) direction += EAST
    if (location.y < dragInsets!!.top) direction += NORTH
    if (location.y > source.height - dragInsets!!.bottom - 1) direction += SOUTH

    //  Mouse is no longer over a resizable border
    if (direction == 0) {
      source.cursor = sourceCursor
    } else  // use the appropriate resizable cursor
    {
      val cursorType = cursors[direction]!!
      val cursor = Cursor.getPredefinedCursor(cursorType)
      source.cursor = cursor
    }
  }

  override fun mouseEntered(e: MouseEvent) {
    if (!resizing) {
      val source = e.component
      sourceCursor = source.cursor
    }
  }

  override fun mouseExited(e: MouseEvent) {
    if (!resizing) {
      val source = e.component
      source.cursor = sourceCursor
    }
  }

  override fun mousePressed(e: MouseEvent) {
    //	The mouseMoved event continually updates this variable
    if (direction == 0) return

    //  Setup for resizing. All future dragging calculations are done based
    //  on the original bounds of the component and mouse pressed location.
    resizing = true
    val source = e.component
    pressed = e.point
    SwingUtilities.convertPointToScreen(pressed, source)
    bounds = source.bounds

    //  Making sure autoscrolls is false will allow for smoother resizing
    //  of components
    if (source is JComponent) {
      val jc = source
      autoscrolls = jc.autoscrolls
      jc.autoscrolls = false
    }
  }

  /**
   * Restore the original state of the Component
   */
  override fun mouseReleased(e: MouseEvent) {
    resizing = false
    val source = e.component
    source.cursor = sourceCursor
    if (source is JComponent) {
      source.autoscrolls = autoscrolls
    }
  }

  /**
   * Resize the component ensuring location and size is within the bounds
   * of the parent container and that the size is within the minimum and
   * maximum constraints.
   *
   * All calculations are done using the bounds of the component when the
   * resizing started.
   */
  override fun mouseDragged(e: MouseEvent) {
    if (resizing == false) return
    val source = e.component
    val dragged = e.point
    SwingUtilities.convertPointToScreen(dragged, source)
    changeBounds(source, direction, bounds, pressed, dragged)
  }

  protected fun changeBounds(source: Component, direction: Int, bounds: Rectangle?, pressed: Point?, current: Point) {
    //  Start with original locaton and size
    var x = bounds!!.x
    var y = bounds.y
    var width = bounds.width
    var height = bounds.height

    //  Resizing the West or North border affects the size and location
    if (WEST == direction and WEST) {
      var drag = getDragDistance(pressed!!.x, current.x, snapSize!!.width)
      val maximum = Math.min(width + x, maximumSize.width)
      drag = getDragBounded(drag, snapSize!!.width, width, minimumSize.width, maximum)
      x -= drag
      width += drag
    }
    if (NORTH == direction and NORTH) {
      var drag = getDragDistance(pressed!!.y, current.y, snapSize!!.height)
      val maximum = Math.min(height + y, maximumSize.height)
      drag = getDragBounded(drag, snapSize!!.height, height, minimumSize.height, maximum)
      y -= drag
      height += drag
    }

    //  Resizing the East or South border only affects the size
    if (EAST == direction and EAST) {
      var drag = getDragDistance(current.x, pressed!!.x, snapSize!!.width)
      val boundingSize = getBoundingSize(source)
      val maximum = Math.min(boundingSize.width - x, maximumSize.width)
      drag = getDragBounded(drag, snapSize!!.width, width, minimumSize.width, maximum)
      width += drag
    }
    if (SOUTH == direction and SOUTH) {
      var drag = getDragDistance(current.y, pressed!!.y, snapSize!!.height)
      val boundingSize = getBoundingSize(source)
      val maximum = Math.min(boundingSize.height - y, maximumSize.height)
      drag = getDragBounded(drag, snapSize!!.height, height, minimumSize.height, maximum)
      height += drag
    }
    source.setBounds(x, y, width, height)
    source.validate()
  }

  /*
	 *  Determine how far the mouse has moved from where dragging started
	 */
  private fun getDragDistance(larger: Int, smaller: Int, snapSize: Int): Int {
    val halfway = snapSize / 2
    var drag = larger - smaller
    drag += if (drag < 0) -halfway else halfway
    drag = drag / snapSize * snapSize
    return drag
  }

  /*
	 *  Adjust the drag value to be within the minimum and maximum range.
	 */
  private fun getDragBounded(drag: Int, snapSize: Int, dimension: Int, minimum: Int, maximum: Int): Int {
    var drag = drag
    while (dimension + drag < minimum) drag += snapSize
    while (dimension + drag > maximum) drag -= snapSize
    return drag
  }

  /*
	 *  Keep the size of the component within the bounds of its parent.
	 */
  private fun getBoundingSize(source: Component): Dimension {
    return if (source is Window) {
      val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
      val bounds = env.maximumWindowBounds
      Dimension(bounds.width, bounds.height)
    } else {
      source.parent.size
    }
  }

  companion object {
    private val MINIMUM_SIZE = Dimension(10, 10)
    private val MAXIMUM_SIZE = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    private val cursors: MutableMap<Int, Int> = HashMap()
    protected const val NORTH = 1
    protected const val WEST = 2
    protected const val SOUTH = 4
    protected const val EAST = 8
  }
}
