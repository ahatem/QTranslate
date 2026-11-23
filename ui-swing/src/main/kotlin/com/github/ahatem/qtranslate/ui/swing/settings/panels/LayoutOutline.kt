package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.SecondResultPlacement
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.UIManager

/** A block of the outline: one part of the main window. */
enum class OutlineBlock {
    HISTORY, LANGUAGES, SERVICES, INPUT, RESULT, BOARD, SECOND_RESULT, STATUS_BAR;

    /** The region this block stands for, or null for the panes, the second result and the status bar. */
    val region: WindowRegion?
        get() = when (this) {
            HISTORY -> WindowRegion.HISTORY
            LANGUAGES -> WindowRegion.LANGUAGES
            SERVICES -> WindowRegion.SERVICES
            else -> null
        }

    /** Whether clicking the block arranges something. The panes themselves are not arranged here. */
    val isArrangeable: Boolean get() = this !in setOf(INPUT, RESULT, BOARD)

    companion object {
        fun of(region: WindowRegion): OutlineBlock = when (region) {
            WindowRegion.HISTORY -> HISTORY
            WindowRegion.LANGUAGES -> LANGUAGES
            WindowRegion.SERVICES -> SERVICES
        }
    }
}

/** The outline as a tree: blocks stacked top to bottom, or side by side in reading order. */
sealed interface OutlineNode {
    data class Leaf(val block: OutlineBlock) : OutlineNode
    data class Column(val children: List<OutlineNode>) : OutlineNode
    data class Row(val children: List<OutlineNode>) : OutlineNode

    /** Every block under this node, in reading order. */
    val blocks: List<OutlineBlock>
        get() = when (this) {
            is Leaf -> listOf(block)
            is Column -> children.flatMap { it.blocks }
            is Row -> children.flatMap { it.blocks }
        }
}

/**
 * How the window is arranged for [layout], as a tree of blocks.
 *
 * Follows [WindowLayout.rowPlan] and [WindowLayout.effectiveSecondResult], the two descriptions the
 * real window is built from, so the outline and the window cannot disagree about where a region or
 * the second result goes. A region that is hidden (or, under Comparison, the service selector) has
 * no block; the second result has one only while a second result type is chosen.
 */
fun outlinePlan(layout: WindowLayout, effectivePanes: PaneLayout, extraOutputType: ExtraOutputType): OutlineNode {
    val plan = layout.rowPlan(effectivePanes)
    fun rows(slot: RegionSlot): List<OutlineNode> = plan[slot].orEmpty().map { row ->
        if (row.size == 1) OutlineNode.Leaf(OutlineBlock.of(row.single()))
        else OutlineNode.Row(row.map { OutlineNode.Leaf(OutlineBlock.of(it)) })
    }

    fun leaf(block: OutlineBlock) = OutlineNode.Leaf(block)
    val hasSecond = extraOutputType != ExtraOutputType.None
    val placement = layout.effectiveSecondResult(effectivePanes)
    val second = leaf(OutlineBlock.SECOND_RESULT)
    val below = if (hasSecond && placement == SecondResultPlacement.BELOW) listOf(second) else emptyList()
    val above = rows(RegionSlot.ABOVE_RESULTS)

    val panes: List<OutlineNode> = when (effectivePanes) {
        PaneLayout.STACKED -> {
            val results = if (hasSecond && placement == SecondResultPlacement.WITH_RESULT) {
                OutlineNode.Row(listOf(leaf(OutlineBlock.RESULT), second))
            } else {
                leaf(OutlineBlock.RESULT)
            }
            listOf(leaf(OutlineBlock.INPUT)) + above + results + below
        }
        PaneLayout.SIDE_BY_SIDE -> {
            val resultColumn: OutlineNode = if (hasSecond && placement == SecondResultPlacement.WITH_RESULT) {
                OutlineNode.Column(listOf(leaf(OutlineBlock.RESULT), second))
            } else {
                leaf(OutlineBlock.RESULT)
            }
            val columns = buildList {
                add(leaf(OutlineBlock.INPUT))
                add(resultColumn)
                if (hasSecond && placement == SecondResultPlacement.OWN_COLUMN) add(second)
            }
            above + OutlineNode.Row(columns) + below
        }
        PaneLayout.COMPARISON ->
            listOf(leaf(OutlineBlock.INPUT)) + above + leaf(OutlineBlock.BOARD) + below
    }

    return OutlineNode.Column(
        rows(RegionSlot.TOP) + panes + rows(RegionSlot.BOTTOM) +
            if (layout.showStatusBar) listOf(leaf(OutlineBlock.STATUS_BAR)) else emptyList()
    )
}

/**
 * A small schematic of the main window, built from ordinary components: a button for each block
 * that can be arranged and a bordered label for each pane. It draws nothing itself.
 *
 * The structure is the window's: rows are stacked with a layout that fills the width, and blocks
 * that sit beside each other share a grid, so a right-to-left interface mirrors them through the
 * component orientation alone.
 *
 * @param label the words on a block.
 * @param tooltip what pointing at a block says; null for none.
 * @param onOpen called with the block and its button when the user clicks one that can be arranged.
 */
class LayoutOutline(
    private val label: (OutlineBlock) -> String,
    private val tooltip: (OutlineBlock) -> String?,
    private val onOpen: (OutlineBlock, JComponent) -> Unit,
) : JPanel(BorderLayout()) {

    /** The block each button or pane was built for, by the name the component carries. */
    private val built = LinkedHashMap<OutlineBlock, JComponent>()

    /** The component drawn for [block], or null when the outline has none. */
    fun componentOf(block: OutlineBlock): JComponent? = built[block]

    /** The blocks shown, in reading order. */
    val blocks: List<OutlineBlock> get() = built.keys.toList()

    /** Rebuilds the outline for [plan]. */
    fun show(plan: OutlineNode) {
        removeAll()
        built.clear()
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
            BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP),
        )
        add(build(plan), BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val natural = super.getPreferredSize()
        return Dimension(maxOf(natural.width, UIScale.scale(MIN_WIDTH)), natural.height)
    }

    private fun build(node: OutlineNode): JComponent = when (node) {
        is OutlineNode.Leaf -> block(node.block)
        is OutlineNode.Row -> JPanel(GridLayout(1, node.children.size, UIScale.scale(GAP), 0)).apply {
            node.children.forEach { add(build(it)) }
        }
        is OutlineNode.Column -> JPanel(GridBagLayout()).apply {
            node.children.forEachIndexed { index, child ->
                add(
                    build(child),
                    GridBagConstraints().apply {
                        gridx = 0
                        gridy = index
                        weightx = 1.0
                        // A pane takes the spare height; a chrome row keeps its own.
                        weighty = if (child.blocks.any { it in PANES }) 1.0 else 0.0
                        fill = GridBagConstraints.BOTH
                        if (index > 0) insets = java.awt.Insets(UIScale.scale(GAP), 0, 0, 0)
                    },
                )
            }
        }
    }

    private fun block(block: OutlineBlock): JComponent {
        val words = label(block)
        val component: JComponent = if (block.isArrangeable) {
            JButton(words).apply {
                horizontalAlignment = SwingConstants.CENTER
                addActionListener { onOpen(block, this) }
            }
        } else {
            JPanel(BorderLayout()).apply {
                border = BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"))
                add(JLabel(words, SwingConstants.CENTER).apply { isEnabled = false }, BorderLayout.CENTER)
            }
        }
        component.name = nameOf(block)
        component.toolTipText = tooltip(block)
        if (block in PANES) {
            component.preferredSize = Dimension(UIScale.scale(40), UIScale.scale(if (block == OutlineBlock.BOARD) 72 else 44))
        } else if (block == OutlineBlock.SECOND_RESULT) {
            component.preferredSize = Dimension(UIScale.scale(40), UIScale.scale(32))
        }
        built[block] = component
        return component
    }

    companion object {
        private const val GAP = 6
        private const val MIN_WIDTH = 440
        private val PANES = setOf(OutlineBlock.INPUT, OutlineBlock.RESULT, OutlineBlock.BOARD, OutlineBlock.SECOND_RESULT)

        /** The name a block's component carries, so a host or a test can find it. */
        fun nameOf(block: OutlineBlock) = "outline:$block"
    }
}
