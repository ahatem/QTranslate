package com.github.ahatem.qtranslate.ui.swing.imagesearch

import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JTextField
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
        previousLabel = "Previous", nextLabel = "Next",
        pinTooltip = "Pin", unpinTooltip = "Unpin", closeTooltip = "Close"
    )

    private fun results(count: Int) = List(count) {
        // Unreachable on purpose: the fetch fails at once and the tile simply stays blank.
        ImageResult("http://127.0.0.1:1/t$it.png", "http://127.0.0.1:1/f$it.png", title = "Picture $it", license = "CC BY")
    }

    private fun service(id: String, name: String = id) = ServiceInfo(id, name, iconPath = null, type = ServiceRole.IMAGE_SEARCH)

    private fun state(
        term: String = "",
        found: List<ImageResult> = emptyList(),
        loading: Boolean = false,
        failed: Boolean = false,
        onSearch: (String) -> Unit = {},
        availableServices: List<ServiceInfo> = emptyList(),
        selectedServiceId: String? = null,
        onServiceSelected: (String) -> Unit = {},
    ) = ImageSearchPanelState(
        loading, found, term, failed, strings, onSearch,
        availableServices = availableServices,
        selectedServiceId = selectedServiceId,
        onServiceSelected = onServiceSelected,
        onImageOpened = {}
    )

    private fun panel() = ImageSearchPanel(TestIcons.iconManager()).also { panels += it }

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
        assertEquals(1, panel.columnCountForTest(), "narrow width")
        size(panel, 1400)
        assertTrue(panel.columnCountForTest() >= 3, "wide width")
    }

    @Test
    fun `the search field shows a term that arrives from outside and keeps what is being typed`() {
        val panel = panel()
        onEdt { panel.render(state(term = "cat")) }
        assertEquals("cat", panel.searchText)

        onEdt { (panel.searchFieldComponent as JTextField).text = "cate" }
        onEdt { panel.render(state(term = "cat", loading = true)) }
        assertEquals("cate", panel.searchText, "unrelated render overwrote typing")

        onEdt { panel.render(state(term = "dog")) }
        assertEquals("dog", panel.searchText, "new external term was ignored")
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
        assertTrue(onEdt { panel.stepBack() }, "first step leaves the picture")
        assertFalse(panel.isShowingPreview)
        assertFalse(onEdt { panel.stepBack() }, "nothing left to step out of")
    }

    @Test
    fun `an empty result shows the state message, not tiles`() {
        val panel = panel()
        onEdt { panel.render(state(term = "zzz")) }
        assertEquals(0, panel.tileCountForTest())
        val labels = descendants(panel).filterIsInstance<javax.swing.JLabel>().mapNotNull { it.text }
        assertTrue("Nothing found" in labels)
    }

    @Test
    fun `the provider picker stays hidden with zero or one Image Search service`() {
        val panel = panel()
        onEdt { panel.render(state()) }
        assertFalse(panel.isServicePickerVisibleForTest(), "no services")

        onEdt { panel.render(state(availableServices = listOf(service("wikimedia")))) }
        assertFalse(panel.isServicePickerVisibleForTest(), "one service")
        assertEquals("wikimedia", panel.serviceIdentityForTest(), "sole provider is not named")
    }

    @Test
    fun `the provider picker appears once a second Image Search service exists, and reports a selection`() {
        val reported = mutableListOf<String>()
        val panel = panel()
        val services = listOf(service("wikimedia", "Wikimedia Commons"), service("other-provider", "Other Provider"))
        onEdt {
            panel.render(
                state(
                    availableServices = services,
                    selectedServiceId = "wikimedia",
                    onServiceSelected = { reported += it }
                )
            )
        }
        assertTrue(panel.isServicePickerVisibleForTest(), "two services")
        assertEquals("wikimedia", panel.selectedServiceForTest()?.id)

        onEdt { panel.selectServiceForTest(services[1]) }
        assertEquals(listOf("other-provider"), reported)
    }

    @Test
    fun `opening a picture shows Previous and Next only when there is somewhere to browse`() {
        val panel = panel()
        onEdt { panel.render(state(term = "cat", found = results(1))) }
        size(panel, 700)
        val soleTile = descendants(panel).first { it.cursor.type == java.awt.Cursor.HAND_CURSOR }
        onEdt { soleTile.dispatchEvent(MouseEvent(soleTile, MouseEvent.MOUSE_CLICKED, 0L, 0, 5, 5, 1, false)) }
        assertFalse("Previous" in panel.previewActionLabelsForTest(), "one result")
        onEdt { panel.stepBack() }

        onEdt { panel.render(state(term = "cat", found = results(3))) }
        size(panel, 700)
        val tiles = descendants(panel).filter { it.cursor.type == java.awt.Cursor.HAND_CURSOR }
        onEdt { tiles[0].dispatchEvent(MouseEvent(tiles[0], MouseEvent.MOUSE_CLICKED, 0L, 0, 5, 5, 1, false)) }
        val labelsAtFirst = panel.previewActionLabelsForTest()
        assertTrue("Next" in labelsAtFirst, "results ahead")
        assertTrue("Previous" in labelsAtFirst, "Previous hidden at the first result")
    }
}
