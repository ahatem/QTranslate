package com.pnix.qtranslate.presentation.components

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*


/**
 * The ComponentResizer allows you to resize a component by dragging a border
 * of the component.
 */
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

class ComponentMover : MouseAdapter {
  /**
   * Get the drag insets
   *
   * @return  the drag insets
   */
  /**
   * Set the drag insets. The insets specify an area where mouseDragged
   * events should be ignored and therefore the component will not be moved.
   * This will prevent these events from being confused with a
   * MouseMotionListener that supports component resizing.
   *
   * @param  dragInsets
   */
  var dragInsets = Insets(0, 0, 0, 0)
  private var snapSize = Dimension(1, 1)
  /**
   * Get the bounds insets
   *
   * @return  the bounds insets
   */
  /**
   * Set the edge insets. The insets specify how close to each edge of the parent
   * component that the child component can be moved. Positive values means the
   * component must be contained within the parent. Negative values means the
   * component can be moved outside the parent.
   *
   * @param  edgeInsets
   */
  var edgeInsets = Insets(0, 0, 0, 0)
  /**
   * Get the change cursor property
   *
   * @return  the change cursor property
   */
  /**
   * Set the change cursor property
   *
   * @param  changeCursor when true the cursor will be changed to the
   * Cursor.MOVE_CURSOR while the mouse is pressed
   */
  var isChangeCursor = true
  /**
   * Get the auto layout property
   *
   * @return  the auto layout property
   */
  /**
   * Set the auto layout property
   *
   * @param  autoLayout when true layout will be invoked on the parent container
   */
  var isAutoLayout = false
  private var destinationClass: Class<*>? = null
  private var destinationComponent: Component? = null
  private var destination: Component? = null
  private var source: Component? = null
  private var pressed: Point? = null
  private var location: Point? = null
  private var originalCursor: Cursor? = null
  private var autoscrolls = false
  private var potentialDrag = false

  /**
   * Constructor for moving individual components. The components must be
   * regisetered using the registerComponent() method.
   */
  constructor()

  /**
   * Constructor to specify a Class of Component that will be moved when
   * drag events are generated on a registered child component. The events
   * will be passed to the first ancestor of this specified class.
   *
   * @param destinationClass  the Class of the ancestor component
   * @param component         the Components to be registered for forwarding
   * drag events to the ancestor Component.
   */
  constructor(destinationClass: Class<*>?, vararg components: Component?) {
    this.destinationClass = destinationClass
    registerComponent(*components)
  }

  /**
   * Constructor to specify a parent component that will be moved when drag
   * events are generated on a registered child component.
   *
   * @param destinationComponent  the component drage events should be forwareded to
   * @param components    the Components to be registered for forwarding drag
   * events to the parent component to be moved
   */
  constructor(destinationComponent: Component?, vararg components: Component?) {
    this.destinationComponent = destinationComponent
    registerComponent(*components)
  }

  /**
   * Remove listeners from the specified component
   *
   * @param component  the component the listeners are removed from
   */
  fun deregisterComponent(vararg components: Component) {
    for (component in components) component.removeMouseListener(this)
  }

  /**
   * Add the required listeners to the specified component
   *
   * @param component  the component the listeners are added to
   */
  fun registerComponent(vararg components: Component?) {
    for (component in components) component?.addMouseListener(this)
  }

  /**
   * Get the snap size
   *
   * @return the snap size
   */
  fun getSnapSize(): Dimension {
    return snapSize
  }

  /**
   * Set the snap size. Forces the component to be snapped to
   * the closest grid position. Snapping will occur when the mouse is
   * dragged half way.
   */
  fun setSnapSize(snapSize: Dimension) {
    require(
      !(snapSize.width < 1
          || snapSize.height < 1)
    ) { "Snap sizes must be greater than 0" }
    this.snapSize = snapSize
  }

  /**
   * Setup the variables used to control the moving of the component:
   *
   * source - the source component of the mouse event
   * destination - the component that will ultimately be moved
   * pressed - the Point where the mouse was pressed in the destination
   * component coordinates.
   */
  override fun mousePressed(e: MouseEvent) {
    source = e.component
    val width = (source?.size?.width ?: 0) - dragInsets.left - dragInsets.right
    val height = (source?.size?.height ?: 0) - dragInsets.top - dragInsets.bottom
    val r = Rectangle(dragInsets.left, dragInsets.top, width, height)
    if (r.contains(e.point)) setupForDragging(e)
  }

  private fun setupForDragging(e: MouseEvent) {
    source!!.addMouseMotionListener(this)
    potentialDrag = true

    //  Determine the component that will ultimately be moved
    destination = if (destinationComponent != null) {
      destinationComponent
    } else if (destinationClass == null) {
      source
    } else  //  forward events to destination component
    {
      SwingUtilities.getAncestorOfClass(destinationClass, source)
    }
    pressed = e.locationOnScreen
    location = destination!!.location
    if (isChangeCursor) {
      originalCursor = source!!.cursor
      source!!.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
    }

    //  Making sure autoscrolls is false will allow for smoother dragging of
    //  individual components
    if (destination is JComponent) {
      val jc = destination as JComponent
      autoscrolls = jc.autoscrolls
      jc.autoscrolls = false
    }
  }

  /**
   * Move the component to its new location. The dragged Point must be in
   * the destination coordinates.
   */
  override fun mouseDragged(e: MouseEvent) {
    val dragged = e.locationOnScreen
    val dragX = getDragDistance(dragged.x, pressed!!.x, snapSize.width)
    val dragY = getDragDistance(dragged.y, pressed!!.y, snapSize.height)
    var locationX = location!!.x + dragX
    var locationY = location!!.y + dragY

    //  Mouse dragged events are not generated for every pixel the mouse
    //  is moved. Adjust the location to make sure we are still on a
    //  snap value.
    while (locationX < edgeInsets.left) locationX += snapSize.width
    while (locationY < edgeInsets.top) locationY += snapSize.height
    val d = getBoundingSize(destination)
    while (locationX + destination!!.size.width + edgeInsets.right > d.width) locationX -= snapSize.width
    while (locationY + destination!!.size.height + edgeInsets.bottom > d.height) locationY -= snapSize.height

    //  Adjustments are finished, move the component
    destination!!.setLocation(locationX, locationY)
  }

  /*
	 *  Determine how far the mouse has moved from where dragging started
	 *  (Assume drag direction is down and right for positive drag distance)
	 */
  private fun getDragDistance(larger: Int, smaller: Int, snapSize: Int): Int {
    val halfway = snapSize / 2
    var drag = larger - smaller
    drag += if (drag < 0) -halfway else halfway
    drag = drag / snapSize * snapSize
    return drag
  }

  /*
	 *  Get the bounds of the parent of the dragged component.
	 */
  private fun getBoundingSize(source: Component?): Dimension {
    return if (source is Window) {
      val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
      val bounds = env.maximumWindowBounds
      Dimension(bounds.width, bounds.height)
    } else {
      source!!.parent.size
    }
  }

  /**
   * Restore the original state of the Component
   */
  override fun mouseReleased(e: MouseEvent) {
    if (!potentialDrag) return
    source!!.removeMouseMotionListener(this)
    potentialDrag = false
    if (isChangeCursor) source!!.cursor = originalCursor
    if (destination is JComponent) {
      (destination as JComponent).autoscrolls = autoscrolls
    }

    //  Layout the components on the parent container
    if (isAutoLayout) {
      if (destination is JComponent) {
        (destination as JComponent).revalidate()
      } else {
        destination!!.validate()
      }
    }
  }
}


class ScrollablePanel @JvmOverloads constructor(layout: LayoutManager? = FlowLayout()) : JPanel(layout),
  Scrollable, SwingConstants {
  enum class ScrollableSizeHint {
    NONE,
    FIT,
    STRETCH
  }

  enum class IncrementType {
    PERCENT,
    PIXELS
  }

  private var scrollableHeight = ScrollableSizeHint.NONE
  private var scrollableWidth = ScrollableSizeHint.NONE
  private var horizontalBlock: IncrementInfo? = null
  private var horizontalUnit: IncrementInfo? = null
  private var verticalBlock: IncrementInfo? = null
  private var verticalUnit: IncrementInfo? = null
  /**
   * Constuctor for specifying the LayoutManager of the panel.
   *
   * @param layout the LayountManger for the panel
   */
  /**
   * Default constructor that uses a FlowLayout
   */
  init {
    val block = IncrementInfo(IncrementType.PERCENT, 100)
    val unit = IncrementInfo(IncrementType.PERCENT, 10)
    setScrollableBlockIncrement(SwingConstants.HORIZONTAL, block)
    setScrollableBlockIncrement(SwingConstants.VERTICAL, block)
    setScrollableUnitIncrement(SwingConstants.HORIZONTAL, unit)
    setScrollableUnitIncrement(SwingConstants.VERTICAL, unit)
  }

  /**
   * Get the height ScrollableSizeHint enum
   *
   * @return the ScrollableSizeHint enum for the height
   */
  fun getScrollableHeight(): ScrollableSizeHint {
    return scrollableHeight
  }

  /**
   * Set the ScrollableSizeHint enum for the height. The enum is used to
   * determine the boolean value that is returned by the
   * getScrollableTracksViewportHeight() method. The valid values are:
   *
   * ScrollableSizeHint.NONE - return "false", which causes the height
   * of the panel to be used when laying out the children
   * ScrollableSizeHint.FIT - return "true", which causes the height of
   * the viewport to be used when laying out the children
   * ScrollableSizeHint.STRETCH - return "true" when the viewport height
   * is greater than the height of the panel, "false" otherwise.
   *
   * @param scrollableHeight as represented by the ScrollableSizeHint enum.
   */
  fun setScrollableHeight(scrollableHeight: ScrollableSizeHint) {
    this.scrollableHeight = scrollableHeight
    revalidate()
  }

  /**
   * Get the width ScrollableSizeHint enum
   *
   * @return the ScrollableSizeHint enum for the width
   */
  fun getScrollableWidth(): ScrollableSizeHint {
    return scrollableWidth
  }

  /**
   * Set the ScrollableSizeHint enum for the width. The enum is used to
   * determine the boolean value that is returned by the
   * getScrollableTracksViewportWidth() method. The valid values are:
   *
   * ScrollableSizeHint.NONE - return "false", which causes the width
   * of the panel to be used when laying out the children
   * ScrollableSizeHint.FIT - return "true", which causes the width of
   * the viewport to be used when laying out the children
   * ScrollableSizeHint.STRETCH - return "true" when the viewport width
   * is greater than the width of the panel, "false" otherwise.
   *
   * @param scrollableWidth as represented by the ScrollableSizeHint enum.
   */
  fun setScrollableWidth(scrollableWidth: ScrollableSizeHint) {
    this.scrollableWidth = scrollableWidth
    revalidate()
  }

  /**
   * Get the block IncrementInfo for the specified orientation
   *
   * @return the block IncrementInfo for the specified orientation
   */
  fun getScrollableBlockIncrement(orientation: Int): IncrementInfo? {
    return if (orientation == SwingConstants.HORIZONTAL) horizontalBlock else verticalBlock
  }

  /**
   * Specify the information needed to do block scrolling.
   *
   * @param orientation  specify the scrolling orientation. Must be either:
   * SwingContants.HORIZONTAL or SwingContants.VERTICAL.
   * @paran type  specify how the amount parameter in the calculation of
   * the scrollable amount. Valid values are:
   * IncrementType.PERCENT - treat the amount as a % of the viewport size
   * IncrementType.PIXEL - treat the amount as the scrollable amount
   * @param amount  a value used with the IncrementType to determine the
   * scrollable amount
   */
  fun setScrollableBlockIncrement(orientation: Int, type: IncrementType, amount: Int) {
    val info = IncrementInfo(type, amount)
    setScrollableBlockIncrement(orientation, info)
  }

  /**
   * Specify the information needed to do block scrolling.
   *
   * @param orientation  specify the scrolling orientation. Must be either:
   * SwingContants.HORIZONTAL or SwingContants.VERTICAL.
   * @param info  An IncrementInfo object containing information of how to
   * calculate the scrollable amount.
   */
  fun setScrollableBlockIncrement(orientation: Int, info: IncrementInfo?) {
    when (orientation) {
      SwingConstants.HORIZONTAL -> horizontalBlock = info
      SwingConstants.VERTICAL -> verticalBlock = info
      else -> throw java.lang.IllegalArgumentException("Invalid orientation: $orientation")
    }
  }

  /**
   * Get the unit IncrementInfo for the specified orientation
   *
   * @return the unit IncrementInfo for the specified orientation
   */
  fun getScrollableUnitIncrement(orientation: Int): IncrementInfo? {
    return if (orientation == SwingConstants.HORIZONTAL) horizontalUnit else verticalUnit
  }

  /**
   * Specify the information needed to do unit scrolling.
   *
   * @param orientation  specify the scrolling orientation. Must be either:
   * SwingContants.HORIZONTAL or SwingContants.VERTICAL.
   * @paran type  specify how the amount parameter in the calculation of
   * the scrollable amount. Valid values are:
   * IncrementType.PERCENT - treat the amount as a % of the viewport size
   * IncrementType.PIXEL - treat the amount as the scrollable amount
   * @param amount  a value used with the IncrementType to determine the
   * scrollable amount
   */
  fun setScrollableUnitIncrement(orientation: Int, type: IncrementType, amount: Int) {
    val info = IncrementInfo(type, amount)
    setScrollableUnitIncrement(orientation, info)
  }

  /**
   * Specify the information needed to do unit scrolling.
   *
   * @param orientation  specify the scrolling orientation. Must be either:
   * SwingContants.HORIZONTAL or SwingContants.VERTICAL.
   * @param info  An IncrementInfo object containing information of how to
   * calculate the scrollable amount.
   */
  fun setScrollableUnitIncrement(orientation: Int, info: IncrementInfo?) {
    when (orientation) {
      SwingConstants.HORIZONTAL -> horizontalUnit = info
      SwingConstants.VERTICAL -> verticalUnit = info
      else -> throw java.lang.IllegalArgumentException("Invalid orientation: $orientation")
    }
  }

  //  Implement Scrollable interface
  override fun getPreferredScrollableViewportSize(): Dimension {
    return preferredSize
  }

  override fun getScrollableUnitIncrement(
    visible: Rectangle, orientation: Int, direction: Int
  ): Int {
    return when (orientation) {
      SwingConstants.HORIZONTAL -> getScrollableIncrement(horizontalUnit, visible.width)
      SwingConstants.VERTICAL -> getScrollableIncrement(verticalUnit, visible.height)
      else -> throw java.lang.IllegalArgumentException("Invalid orientation: $orientation")
    }
  }

  override fun getScrollableBlockIncrement(
    visible: Rectangle, orientation: Int, direction: Int
  ): Int {
    return when (orientation) {
      SwingConstants.HORIZONTAL -> getScrollableIncrement(horizontalBlock, visible.width)
      SwingConstants.VERTICAL -> getScrollableIncrement(verticalBlock, visible.height)
      else -> throw java.lang.IllegalArgumentException("Invalid orientation: $orientation")
    }
  }

  protected fun getScrollableIncrement(info: IncrementInfo?, distance: Int): Int {
    return if (info!!.increment == IncrementType.PIXELS) info.amount else distance * info.amount / 100
  }

  override fun getScrollableTracksViewportWidth(): Boolean {
    if (scrollableWidth == ScrollableSizeHint.NONE) return false
    if (scrollableWidth == ScrollableSizeHint.FIT) return true

    return if (parent is JViewport) {
      (parent as JViewport).width > preferredSize.width
    } else false
  }

  override fun getScrollableTracksViewportHeight(): Boolean {
    if (scrollableHeight == ScrollableSizeHint.NONE) return false
    if (scrollableHeight == ScrollableSizeHint.FIT) return true

    return if (parent is JViewport) {
      (parent as JViewport).height > preferredSize.height
    } else false
  }

  /**
   * Helper class to hold the information required to calculate the scroll amount.
   */
  class IncrementInfo(val increment: IncrementType, val amount: Int) {

    override fun toString(): String {
      return "ScrollablePanel[" +
          increment + ", " +
          amount + "]"
    }
  }
}