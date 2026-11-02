package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.ocr.OcrServiceCatalog
import com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceStore
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.ui.swing.ocr.OcrWorkspaceDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.*

/** Real peer disposal and queued WINDOW_CLOSED ordering; no window is shown. */
class OcrWorkspaceLifecycleTest {
    @Test
    fun `closing peer rejects input immediately and delayed close cannot retire replacement`() = runBlocking {
        // Native JDialog lifecycle cannot run without a display; the headless store tests still
        // cover cancellation. This test exercises the native boundary on desktop/Xvfb runners.
        assumeFalse(GraphicsEnvironment.isHeadless(), "Native Swing peer requires a display")
        val directory = Files.createTempDirectory("qtranslate-ocr-lifecycle").toFile()
        val storage = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val configuration = Configuration.DEFAULT.copy(autoCheckForUpdates = false, isGlobalHotkeysEnabled = false)
        val repository = SettingsRepository(directory, Json, Quiet, storageScope = storage)
        // Seed the isolated repository too, so its initial emission cannot replace the test's
        // disabled update/hotkey preferences with fresh-install defaults.
        assertTrue(repository.updateConfiguration(configuration).isOk)
        val dependencies = buildDependencies(directory, Logs, repository, configuration)
        val catalog = OcrServiceCatalog(dependencies.pluginManager.activeServices,
            MutableStateFlow(configuration), dependencies.appScope, Quiet)
        var owned: OcrWorkspaceDialog? = null
        val dialogs = mutableListOf<OcrWorkspaceDialog>()
        var oldCloseNotifications = 0
        fun create(): OcrWorkspaceDialog {
            lateinit var dialog: OcrWorkspaceDialog
            dialog = OcrWorkspaceDialog(null, dependencies.mainStore, dependencies.settingsStore,
                dependencies.localizationManager, dependencies.ocrExtractionUseCase, catalog, {}, {}, {}, {
                    if (dialog === dialogs.firstOrNull()) oldCloseNotifications++
                    if (owned === dialog) owned = null
                })
            dialogs += dialog
            return dialog
        }
        try {
            lateinit var first: OcrWorkspaceDialog
            lateinit var replacement: OcrWorkspaceDialog
            SwingUtilities.invokeAndWait {
                first = create()
                owned = first
                first.addNotify()
                assertTrue(first.isDisplayable)
                first.closeWorkspace()
                assertFalse(first.isDisplayable)
                assertTrue(first.isWorkspaceClosed(), "cannot wait for queued WINDOW_CLOSED to stop reuse")
                first.openImage(ImageData(byteArrayOf(1), "png", 1, 1), "rejected", false)
                assertFalse(store(first).state.value.isLoadingImage)

                replacement = owned?.takeIf { !it.isWorkspaceClosed() } ?: create().also { owned = it }
                assertNotSame(first, replacement)
                replacement.addNotify()
                store(replacement).openWithResult(ImageData(byteArrayOf(2), "png", 2, 2), "B", "source B", emptyList())
            }
            SwingUtilities.invokeAndWait {
                assertEquals(1, oldCloseNotifications)
                assertSame(replacement, owned, "late close from A must not clear B's reference")
                assertFalse(replacement.isWorkspaceClosed())
                assertEquals("source B", store(replacement).state.value.recognizedText)
                // Direct dispose is covered as well as the WINDOW_CLOSING path.
                replacement.dispose()
                assertTrue(replacement.isWorkspaceClosed())
                replacement.openImage(ImageData(byteArrayOf(3), "png", 3, 3), "rejected again", false)
                assertEquals("source B", store(replacement).state.value.recognizedText)
            }
        } finally {
            SwingUtilities.invokeAndWait { dialogs.forEach { it.dispose() } }
            SwingUtilities.invokeAndWait { }
            dependencies.settingsStore.drainAcceptedSaves()
            dependencies.mainStore.onShutdown()
            dependencies.pluginManager.shutdown()
            dependencies.httpClient.close()
            dependencies.appScope.coroutineContext[Job]?.cancelAndJoin()
            storage.coroutineContext[Job]?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    private fun store(dialog: OcrWorkspaceDialog): OcrWorkspaceStore =
        OcrWorkspaceDialog::class.java.getDeclaredField("store").apply { isAccessible = true }
            .get(dialog) as OcrWorkspaceStore

    private object Quiet : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    private object Logs : LoggerFactory { override fun getLogger(name: String) = Quiet }
}
