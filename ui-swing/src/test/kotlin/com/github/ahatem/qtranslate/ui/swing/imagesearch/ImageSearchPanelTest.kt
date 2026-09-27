package com.github.ahatem.qtranslate.ui.swing.imagesearch

import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The picture search content works the same wherever it is mounted, because it owns none of the
 * window around it.
 */
class ImageSearchPanelTest {
    private val panels = mutableListOf<ImageSearchPanel>()

    @AfterTest
    fun tearDown() = panels.forEach { it.dispose() }

    private val strings = ImageSearchStrings(
        title = "Image Search", hintMessage = "Type a word", loadingMessage = "Searching",
        notFoundMessage = "Nothing found", errorMessage = "Failed", searchButtonLabel = "Search",
        openTooltip = "Open", openSourceLabel = "Open source", backLabel = "Back",
        pinTooltip = "Pin", unpinTooltip = "Unpin", closeTooltip = "Close"
    )

    private fun results(count: Int) = List(count) {
        // Unreachable on purpose: the fetch fails at once and the tile simply stays blank.
        ImageResult("http://127.0.0.1:1/t$it.png", "http://127.0.0.1:1/f$it.png", title = "Picture $it", license = "CC BY")
    }

    private fun state(
        term: String = "",
        found: List<ImageResult> = emptyList(),
        loading: Boolean = false,
        failed: Boolean = false,
        onSearch: (String) -> Unit = {},
    ) = ImageSearchPanelState(loading, found, term, failed, strings, onSearch, onImageOpened = {})

    private fun panel() = ImageSearchPanel().also { panels += it }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun size(panel: ImageSearchPanel, width: Int, height: Int = 500) {
        onEdt {
            panel.setSize(width, height)
            layoutTree(panel)
        }
        // The column count follows the viewport's resize event, which is delivered after the layout.
        onEdt { }
        onEdt { layoutTree(panel) }
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    @Test
    fun `it shows one tile for each result`() {
        val panel = panel()
        onEdt { panel.render(state(term = "cat", found = results(5))) }
        assertEquals(5, panel.tileCountForTest())
    }

    @Test
    fun `columns follow the width it is given`() {
        val panel = panel()
        onEdt { panel.render(state(term = "cat", found = results(8))) }
        size(panel, 200)
        assertEquals(1, panel.columnCountForTest(), "a narrow dock shows one usable picture")
        size(panel, 1400)
        assertTrue(panel.columnCountForTest() >= 3, "a wide host shows several across")
    }

    @Test
    fun `the search field shows a term that arrives from outside and keeps what is being typed`() {
        val panel = panel()
        onEdt { panel.render(state(term = "cat")) }
        assertEquals("cat", panel.searchText)

        onEdt { (panel.searchFieldComponent as JTextField).text = "cate" }
        onEdt { panel.render(state(term = "cat", loading = true)) }
        assertEquals("cate", panel.searchText, "an unrelated render does not overwrite typing")

        onEdt { panel.render(state(term = "dog")) }
        assertEquals("dog", panel.searchText, "a new term from a selection does")
    }

    @Test
    fun `pressing Enter searches for the trimmed text`() {
        val searched = mutableListOf<String>()
        val panel = panel()
        onEdt { panel.render(state(onSearch = { searched += it })) }
        onEdt {
            (panel.searchFieldComponent as JTextField).text = "  bridge  "
            (panel.searchFieldComponent as JTextField).postActionEvent()
        }
        assertEquals(listOf("bridge"), searched)
    }

    @Test
    fun `opening a picture enlarges it and stepping back returns to the grid`() {
        val panel = panel()
        onEdt { panel.render(state(term = "cat", found = results(3))) }
        size(panel, 700)

        val tile = descendants(panel).first { it.cursor.type == java.awt.Cursor.HAND_CURSOR }
        onEdt {
            tile.dispatchEvent(MouseEvent(tile, MouseEvent.MOUSE_CLICKED, 0L, 0, 5, 5, 1, false))
        }
        assertTrue(panel.isShowingPreview)
        assertTrue(onEdtResult { panel.stepBack() }, "Escape steps out of the picture first")
        assertFalse(panel.isShowingPreview)
        assertFalse(onEdtResult { panel.stepBack() }, "and then has nothing left to step out of")
    }

    private fun <T> onEdtResult(block: () -> T): T {
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun `an empty result shows the state message, not tiles`() {
        val panel = panel()
        onEdt { panel.render(state(term = "zzz")) }
        assertEquals(0, panel.tileCountForTest())
        val labels = descendants(panel).filterIsInstance<javax.swing.JLabel>().mapNotNull { it.text }
        assertTrue("Nothing found" in labels)
    }

    // 24
    @Test
    fun `the same panel is what the dock and the popup both mount`() {
        val root = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing")
        val dialog = File(root, "imagesearch/ImageSearchDialog.kt").readText()
        val main = File(root, "main/MainContentView.kt").readText()

        assertTrue("ImageSearchPanel()" in dialog, "the popup mounts the panel")
        assertTrue("ImageSearchPanel()" in main, "the dock mounts the exact same panel, with no host-supplied padding flag")
        // The popup is only the window around it: nothing of the content stayed behind.
        listOf("GridLayout", "ScaledImage", "ElidingLabel", "ThumbnailLoader", "JScrollPane").forEach {
            assertFalse(it in dialog, "ImageSearchDialog no longer owns $it")
        }
        // And the panel knows nothing of either shell.
        val panel = File(root, "imagesearch/ImageSearchPanel.kt").readText()
        listOf("JDialog", "FloatingPopupBehavior", "WorkspaceDockHost", "LookupDock", "isDocked").forEach {
            assertFalse(it in panel.substringAfter("class ImageSearchPanel"), "the panel does not mention $it")
        }
    }

    @Test
    fun `the panel owns one content rhythm, with no host-supplied padding switch`() {
        val panel = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/imagesearch/ImageSearchPanel.kt").readText()
        assertFalse("padded" in panel.substringAfter("class ImageSearchPanel"), "there is no per-host padding flag left to keep in sync")
    }

    @Test
    fun `the search field and the tile grid share the same leading and trailing edge`() {
        val panel = panel()
        onEdt { panel.render(state(term = "cat", found = results(2))) }
        size(panel, 600)
        val grid = descendants(panel).first { it is java.awt.Container && it.layout is java.awt.GridLayout } as java.awt.Container
        val tile = grid.getComponent(0)
        val fieldPoint = SwingUtilities.convertPoint(panel.searchFieldComponent, 0, 0, panel)
        val tilePoint = SwingUtilities.convertPoint(tile, 0, 0, panel)
        assertTrue(kotlin.math.abs(fieldPoint.x - tilePoint.x) <= 1, "the search field and the first tile start at the same left edge (field=${fieldPoint.x} tile=${tilePoint.x})")
    }
}
