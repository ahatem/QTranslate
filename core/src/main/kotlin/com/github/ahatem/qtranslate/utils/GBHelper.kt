package com.github.ahatem.qtranslate.utils

import java.awt.GridBagConstraints
import java.awt.Insets

class GBHelper : GridBagConstraints() {
    //============================================================== constructor
    /* Creates helper at top left, component always fills cells. */
    init {
        gridx = 0
        gridy = 0
        fill = BOTH // Component fills area
    }

    //================================================================== nextCol
    /* Moves the helper's cursor to the right one column. */
    fun nextCol(): GBHelper {
        gridx++
        return this
    }

    //================================================================== nextRow
    /* Moves the helper's cursor to first col in next row. */
    fun nextRow(): GBHelper {
        gridx = 0
        gridy++
        return this
    }

    //================================================================== expandW
    /* Expandable Width.  Returns new helper allowing horizontal expansion.
         A new helper is created so the expansion values don't
         pollute the origin helper. */
    fun expandW(by: Double = 1.0): GBHelper {
        val duplicate = clone() as GBHelper
        duplicate.weightx = by
        return duplicate
    }

    //================================================================== expandH
    /* Expandable Height. Returns new helper allowing vertical expansion. */
    fun expandH(): GBHelper {
        val duplicate = clone() as GBHelper
        duplicate.weighty = 1.0
        return duplicate
    }

    //==================================================================== width
    /* Sets the width of the area in terms of number of columns. */
    fun width(colsWide: Int): GBHelper {
        val duplicate = clone() as GBHelper
        duplicate.gridwidth = colsWide
        gridx += colsWide - 1
        return duplicate
    }

    //==================================================================== width
    /* Width is set to all remaining columns of the grid. */
    fun width(): GBHelper {
        val duplicate = clone() as GBHelper
        duplicate.gridwidth = REMAINDER
        return duplicate
    }

    //=================================================================== height
    /* Sets the height of the area in terms of rows. */
    fun height(rowsHigh: Int): GBHelper {
        val duplicate = clone() as GBHelper
        duplicate.gridheight = rowsHigh
        return duplicate
    }

    //=================================================================== height
    /* Height is set to all remaining rows. */
    fun height(): GBHelper {
        val duplicate = clone() as GBHelper
        duplicate.gridheight = REMAINDER
        return duplicate
    }

    //==================================================================== align
    /* Alignment is set by parameter. */
    fun align(alignment: Int): GBHelper {
        val duplicate = clone() as GBHelper
        duplicate.fill = NONE
        duplicate.anchor = alignment
        return duplicate
    }

    fun fill(direction: Int): GBHelper {
        val duplicate = clone() as GBHelper
        duplicate.fill = direction
        return duplicate
    }

    fun padding(top: Int = 0, left: Int = 0, bottom: Int = 0, right: Int = 0): GBHelper {
        val duplicate = clone() as GBHelper
        duplicate.insets = Insets(top, left, bottom, right)
        return duplicate
    }

}
