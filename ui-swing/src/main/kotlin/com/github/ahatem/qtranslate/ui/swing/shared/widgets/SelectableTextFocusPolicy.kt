package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import java.awt.Component
import java.awt.Container
import java.awt.FocusTraversalPolicy
import javax.swing.JComponent
import javax.swing.LayoutFocusTraversalPolicy

/**
 * Client property marking a component as skipped by the ordinary Tab cycle.
 *
 * Applied to read-only selectable text: it has to be able to take focus so the keyboard shortcut
 * for copy reaches it, but it is not somewhere a reader tabs to.
 */
const val SKIP_FOCUS_TRAVERSAL_PROPERTY = "qtranslate.skipFocusTraversal"

/** Marks this component as skipped by the ordinary Tab cycle. See [SKIP_FOCUS_TRAVERSAL_PROPERTY]. */
internal fun JComponent.skipFocusTraversal() {
    putClientProperty(SKIP_FOCUS_TRAVERSAL_PROPERTY, true)
}

/** Whether this component is marked by [skipFocusTraversal]. */
internal fun Component.skipsFocusTraversal(): Boolean =
    this is JComponent && getClientProperty(SKIP_FOCUS_TRAVERSAL_PROPERTY) == true

/**
 * Runs a window's own [FocusTraversalPolicy] and steps over components marked by
 * [skipFocusTraversal]. The delegate alone still decides the order, so a host's existing cycle is
 * preserved; this only removes the marked components from the candidates.
 */
internal class SkipMarkedFocusPolicy(
    private val delegate: FocusTraversalPolicy = LayoutFocusTraversalPolicy()
) : FocusTraversalPolicy() {

    override fun getComponentAfter(container: Container, component: Component): Component? =
        walk(container, component, delegate::getComponentAfter)

    override fun getComponentBefore(container: Container, component: Component): Component? =
        walk(container, component, delegate::getComponentBefore)

    override fun getFirstComponent(container: Container): Component? =
        delegate.getFirstComponent(container)?.let { first ->
            first.takeUnless { it.skipsFocusTraversal() }
                ?: walk(container, first, delegate::getComponentAfter)
        }

    override fun getLastComponent(container: Container): Component? =
        delegate.getLastComponent(container)?.let { last ->
            last.takeUnless { it.skipsFocusTraversal() }
                ?: walk(container, last, delegate::getComponentBefore)
        }

    override fun getDefaultComponent(container: Container): Component? =
        delegate.getDefaultComponent(container)?.takeUnless { it.skipsFocusTraversal() }
            ?: getFirstComponent(container)

    /**
     * Repeatedly asks [next] for the neighbour of the previous answer until one of them is not
     * marked. [MAX_HOPS] bounds a cycle of nothing but marked components, which would otherwise keep
     * returning members of that cycle.
     */
    private fun walk(
        container: Container,
        start: Component,
        next: (Container, Component) -> Component?,
    ): Component? {
        var current = start
        repeat(MAX_HOPS) {
            val candidate = next(container, current) ?: return null
            if (!candidate.skipsFocusTraversal()) return candidate
            current = candidate
        }
        return null
    }

    private companion object {
        const val MAX_HOPS = 1000
    }
}
