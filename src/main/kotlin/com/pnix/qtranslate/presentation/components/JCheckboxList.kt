package com.pnix.qtranslate.presentation.components

import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*


open class FixedColumnWidthList<T> : JList<T> {
  var visibleColumnCount = -1
    set(visibleColumnCount) {
      if (this.visibleColumnCount == visibleColumnCount) {
        return
      }
      field = visibleColumnCount
      revalidate()
      repaint()
    }

  constructor() {
    configureDefaults()
  }

  constructor(dataModel: ListModel<T>?) : super(dataModel) {
    configureDefaults()
  }

  constructor(listData: Array<T>?) : super(listData) {
    configureDefaults()
  }

  constructor(listData: Vector<T>?) : super(listData) {
    configureDefaults()
  }

  protected fun configureDefaults() {
    layoutOrientation = HORIZONTAL_WRAP
    visibleRowCount = 1
  }

  override fun getPreferredScrollableViewportSize(): Dimension {
    val visibleColumnCount = visibleColumnCount
    val original = super.getPreferredScrollableViewportSize()
    if (visibleColumnCount <= 0) {
      return original
    }
    val model = model
    var desirableWidth = fixedCellWidth
    if (desirableWidth <= 0) {
      val testValue = prototypeCellValue
      if (testValue != null) {
        desirableWidth = cellRenderer.getListCellRendererComponent(this, testValue, 0, false, false).preferredSize.width
        desirableWidth *= visibleColumnCount
      } else if (getModel().size == 0) {
        desirableWidth = 256 // Just pick a number :/
      } else {
        desirableWidth = 0
        // You could, instead, just take the first value and mutiply
        // it's rendered width by the number of visible columns,
        // but this could mean you're not actually getting the
        // first three columns ... but this whole thing is a bag
        // of ambiguity
        var column = 0
        while (column < visibleColumnCount && column < model.size) {
          val width = cellRenderer.getListCellRendererComponent(
            this,
            model.getElementAt(column),
            column,
            false,
            false
          ).preferredSize.width
//          println("$column = $width")
          desirableWidth += width
          column++
        }
        // I can't find the "horizontal insets", so I'm guessing...
        desirableWidth += 8 * (visibleColumnCount - 1)
        // If we have less data then visible columns, just
        // average the available information and multiply it by
        // the remaining number of columns as a guess
        if (visibleColumnCount > model.size) {
          val averageWidth = desirableWidth / model.size
          desirableWidth += visibleColumnCount - model.size
        }
      }
    } else {
      desirableWidth *= visibleColumnCount
    }
    return Dimension(desirableWidth, original.height)
  }
}

class JCheckboxList<T> : FixedColumnWidthList<CheckListItem> {

  constructor(listData: Array<out T>?) : super(listData?.map { CheckListItem(it as Any).apply { isSelected = true } }?.toTypedArray())

  init {
    cellRenderer = CheckBoxListRenderer()
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(event: MouseEvent) {
        selectItem(event.point)
      }
    })

    val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)
    val mapKey: Any = keyStroke.toString()
    inputMap.put(keyStroke, mapKey)
    actionMap.put(mapKey, object : AbstractAction() {
      override fun actionPerformed(event: ActionEvent) {
        toggleSelectedItem()
      }
    })
  }

  private fun selectItem(point: Point) {
    val index: Int = locationToIndex(point)
    if (index >= 0) {
      val item = model.getElementAt(index) as CheckListItem
      item.isSelected = !item.isSelected
      repaint(getCellBounds(index, index))
    }
  }

  private fun toggleSelectedItem() {
    val index: Int = selectedIndex
    if (index >= 0) {
      val item = model.getElementAt(index) as CheckListItem
      item.isSelected = !item.isSelected
      repaint(getCellBounds(index, index))
    }
  }

  fun checkAll(check: Boolean) {
    for (i in 0 until model.size) {
      val item = model.getElementAt(i) as CheckListItem
      item.isSelected = check
    }
    repaint()
  }
}


class CheckListItem(val item: Any) {
  var isSelected = false

  override fun toString(): String {
    return item.toString()
  }
}


private class CheckBoxListRenderer : JCheckBox(), ListCellRenderer<Any?> {
  override fun getListCellRendererComponent(
    comp: JList<*>, value: Any?,
    index: Int, isSelected: Boolean, hasFocus: Boolean
  ): Component {
    isEnabled = comp.isEnabled
    setSelected((value as CheckListItem?)!!.isSelected)
    font = comp.font
    text = value.toString()
    if (isSelected) {
      background = comp.selectionBackground
      foreground = comp.selectionForeground
    } else {
      background = comp.background
      foreground = comp.foreground
    }
    return this
  }
}