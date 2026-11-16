package com.github.ahatem.qtranslate.plugins.speech

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoskModelManagerTest {

    @TempDir
    lateinit var tempDir: File

    private fun createZipWithModel(zipFile: File, modelName: String) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("$modelName/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("$modelName/am/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("$modelName/am/final.mdl"))
            zos.write("fake-model-binary".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("$modelName/conf/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("$modelName/conf/model.conf"))
            zos.write("sample_rate=16000".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("$modelName/graph/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("$modelName/graph/HCLG.fst"))
            zos.write("fake-graph".toByteArray())
            zos.closeEntry()
        }
    }

    private fun writeFakeModelFiles(dir: File, dynamicGraph: Boolean = false) {
        File(dir, "am").mkdirs()
        File(dir, "am/final.mdl").writeText("fake-model-binary")
        File(dir, "conf").mkdirs()
        File(dir, "conf/model.conf").writeText("sample_rate=16000")
        File(dir, "graph").mkdirs()
        if (dynamicGraph) {
            File(dir, "graph/HCLr.fst").writeText("fake-hclr")
            File(dir, "graph/Gr.fst").writeText("fake-gr")
        } else {
            File(dir, "graph/HCLG.fst").writeText("fake-hclg")
        }
    }

    private fun writeFakeFlatModelFiles(dir: File, dynamicGraph: Boolean = false) {
        File(dir, "final.mdl").writeText("fake-flat-model")
        File(dir, "mfcc.conf").writeText("--sample-frequency=16000")
        if (dynamicGraph) {
            File(dir, "HCLr.fst").writeText("fake-flat-hclr")
            File(dir, "Gr.fst").writeText("fake-flat-gr")
        } else {
            File(dir, "HCLG.fst").writeText("fake-flat-hclg")
        }
    }

    @Test
    fun `half-extracted model folder is not reported as installed`() {
        val manager = VoskModelManager(dataDirectory = { tempDir }, persistentDirectoryProvider = { File(tempDir, "persistent") })
        val partial = File(manager.modelsDirectory, "vosk-model-small-en-us-0.15").apply { mkdirs() }
        File(partial, "conf").mkdirs()
        File(partial, "conf/model.conf").writeText("sample_rate=16000")

        assertFalse(manager.isInstalled("vosk-model-small-en-us-0.15"))
        assertTrue(manager.listInstalledModels().isEmpty())
        assertNull(manager.findModelForLanguage("en"))

        // Add acoustic model without graph -> still incomplete
        File(partial, "am").mkdirs()
        File(partial, "am/final.mdl").writeText("fake-model-binary")
        assertFalse(manager.isInstalled("vosk-model-small-en-us-0.15"))

        // Add graph -> now valid
        File(partial, "graph").mkdirs()
        File(partial, "graph/HCLG.fst").writeText("fake-graph")
        assertTrue(manager.isInstalled("vosk-model-small-en-us-0.15"))
    }

    @Test
    fun `reproduce B1 - partial folder for second model does not hide download button`() {
        val manager = VoskModelManager(dataDirectory = { tempDir }, persistentDirectoryProvider = { File(tempDir, "persistent") })
        val settings = SpeechSettings().apply { modelManager = manager }

        // Model A is fully installed
        val modelA = File(manager.modelsDirectory, "vosk-model-small-en-us-0.15").apply { mkdirs() }
        writeFakeModelFiles(modelA)

        // Model B has a broken/partial extraction folder left behind
        val modelB = File(manager.modelsDirectory, "vosk-model-ar-mgb2-0.4").apply { mkdirs() }
        File(modelB, "conf").mkdirs()
        File(modelB, "conf/model.conf").writeText("sample_rate=16000")

        // Setting model A -> already installed, download button should not show
        settings.voskBuiltinModel = "vosk-model-small-en-us-0.15"
        assertFalse(settings.downloadSelectedModelVisible())

        // Switching dropdown to Model B -> incomplete folder must NOT count as installed!
        // Download button MUST be visible!
        settings.voskBuiltinModel = "vosk-model-ar-mgb2-0.4"
        assertTrue(settings.downloadSelectedModelVisible(), "Download button must remain visible when model B is incomplete")
        assertFalse(manager.isInstalled("vosk-model-ar-mgb2-0.4"))

        // Status action must report incomplete folder
        val status = manager.statusAction("vosk-model-ar-mgb2-0.4")
        assertTrue(status.contains("incomplete, delete and download again"), status)
    }

    @Test
    fun `dynamic graph model with HCLr and Gr is recognized as valid`() {
        val manager = VoskModelManager(dataDirectory = { tempDir }, persistentDirectoryProvider = { File(tempDir, "persistent") })
        val modelDir = File(manager.modelsDirectory, "vosk-model-en-us-0.22-lgraph").apply { mkdirs() }
        writeFakeModelFiles(modelDir, dynamicGraph = true)

        assertTrue(manager.isInstalled("vosk-model-en-us-0.22-lgraph"))
    }

    @Test
    fun `old flat layout model is recognized as valid`() {
        val manager = VoskModelManager(dataDirectory = { tempDir }, persistentDirectoryProvider = { File(tempDir, "persistent") })

        // 1. Static graph flat layout (HCLG.fst)
        val trModelDir = File(manager.modelsDirectory, "vosk-model-small-tr-0.3").apply { mkdirs() }
        writeFakeFlatModelFiles(trModelDir, dynamicGraph = false)
        assertTrue(manager.isInstalled("vosk-model-small-tr-0.3"))

        // 2. Dynamic graph flat layout (HCLr.fst + Gr.fst, e.g. pt-0.3)
        val ptModelDir = File(manager.modelsDirectory, "vosk-model-small-pt-0.3").apply { mkdirs() }
        writeFakeFlatModelFiles(ptModelDir, dynamicGraph = true)
        assertTrue(manager.isInstalled("vosk-model-small-pt-0.3"))
    }

    @Test
    fun `incomplete old flat layout model reports incomplete status and does not count as installed`() {
        val manager = VoskModelManager(dataDirectory = { tempDir }, persistentDirectoryProvider = { File(tempDir, "persistent") })
        val ptModelDir = File(manager.modelsDirectory, "vosk-model-small-pt-0.3").apply { mkdirs() }

        // Has final.mdl and mfcc.conf but missing graph files
        File(ptModelDir, "final.mdl").writeText("fake-flat-model")
        File(ptModelDir, "mfcc.conf").writeText("--sample-frequency=16000")

        assertFalse(manager.isInstalled("vosk-model-small-pt-0.3"))
        val status = manager.statusAction("vosk-model-small-pt-0.3")
        assertTrue(status.contains("incomplete, delete and download again"), status)
    }

    @Test
    fun `inferLanguageFromModelDir extracts language from pattern or model-info json`() {
        val manager = VoskModelManager(dataDirectory = { tempDir })

        val standardDir = File(manager.modelsDirectory, "vosk-model-small-ja-0.22").apply { mkdirs() }
        assertEquals("ja", manager.inferLanguageFromModelDir(standardDir))

        val customDir = File(manager.modelsDirectory, "my-custom-model").apply { mkdirs() }
        File(customDir, "model-info.json").writeText("""{"language":"es","description":"custom spanish"}""")
        assertEquals("es", manager.inferLanguageFromModelDir(customDir))

        val fallbackDir = File(manager.modelsDirectory, "unrecognized").apply { mkdirs() }
        assertEquals("unknown", manager.inferLanguageFromModelDir(fallbackDir))
    }

    @Test
    fun `defaultModelForSystem selects small catalogue model for system locale`() {
        assertEquals("vosk-model-small-de-0.15", VoskModelManager.defaultModelForSystem("de"))
        assertEquals("vosk-model-small-fr-0.22", VoskModelManager.defaultModelForSystem("fr"))
        assertEquals("vosk-model-ar-mgb2-0.4", VoskModelManager.defaultModelForSystem("ar"))
        assertEquals("vosk-model-small-en-us-0.15", VoskModelManager.defaultModelForSystem("unknown"))
    }

    @Test
    fun `model manager detects installed models and resolves for language`() {
        val isolatedDir = File(tempDir, "isolated").apply { mkdirs() }
        val manager = VoskModelManager(dataDirectory = { tempDir }, persistentDirectoryProvider = { isolatedDir })
        val modelsRoot = manager.modelsDirectory

        assertFalse(manager.isInstalled("vosk-model-small-en-us-0.15"))
        assertNull(manager.findModelForLanguage("en"))

        // Create a model folder
        val enModelDir = File(modelsRoot, "vosk-model-small-en-us-0.15").apply { mkdirs() }
        writeFakeModelFiles(enModelDir)

        assertTrue(manager.isInstalled("vosk-model-small-en-us-0.15"))
        assertEquals(1, manager.listInstalledModels().size)
        assertEquals(enModelDir.absolutePath, manager.findModelForLanguage("en")?.absolutePath)
        assertEquals(enModelDir.absolutePath, manager.findModelForLanguage("en-US")?.absolutePath)
        assertEquals(enModelDir.absolutePath, manager.findModelForLanguage("auto")?.absolutePath)
        assertNull(manager.findModelForLanguage("ar"))
        assertNull(manager.findModelForLanguage("de"))

        // Create an Arabic model folder
        val arModelDir = File(modelsRoot, "vosk-model-ar-mgb2-0.4").apply { mkdirs() }
        writeFakeModelFiles(arModelDir)
        assertEquals(arModelDir.absolutePath, manager.findModelForLanguage("ar")?.absolutePath)
        // Auto mode prefers English when preferred model is not specified
        assertEquals(enModelDir.absolutePath, manager.findModelForLanguage("auto")?.absolutePath)
        // When preferred model is Arabic, auto mode respects it
        assertEquals(arModelDir.absolutePath, manager.findModelForLanguage("auto", "vosk-model-ar-mgb2-0.4")?.absolutePath)
    }

    @Test
    fun `model manager downloads unzips and verifies model archive`() = runBlocking {
        val fakeModel = VoskModel(
            name = "vosk-model-test-fake",
            language = "en",
            label = "Test Model",
            sizeBytes = 1024L,
            url = "https://example.com/fake.zip"
        )

        val isolatedPersistentDir = File(tempDir, "persistent").apply { mkdirs() }
        val manager = VoskModelManager(
            dataDirectory = { tempDir },
            persistentDirectoryProvider = { isolatedPersistentDir },
            downloader = { _, targetZip, onProgress ->
                createZipWithModel(targetZip, fakeModel.name)
                onProgress?.invoke(targetZip.length(), targetZip.length())
                Ok(Unit)
            }
        )

        assertFalse(manager.isInstalled(fakeModel.name))

        val result = manager.downloadModel(fakeModel)
        val extractedDir = result.get()
        assertNotNull(extractedDir)
        assertTrue(extractedDir.isDirectory)
        assertTrue(File(extractedDir, "am/final.mdl").isFile)
        assertTrue(manager.isInstalled(fakeModel.name))
        assertEquals(manager.modelsDirectory.absolutePath, extractedDir.parentFile.absolutePath, "Model should be saved in modelsDirectory")
        assertFalse(File(isolatedPersistentDir, fakeModel.name).exists(), "Model should NOT be copied to persistent directory")

        // Check status action
        val status = manager.statusAction()
        assertTrue(status.contains(fakeModel.name))

        // Delete model
        assertTrue(manager.deleteModel(fakeModel.name))
        assertFalse(manager.isInstalled(fakeModel.name))
    }

    @Test
    fun `import model from zip file installs model successfully into modelsDirectory`() {
        val isolatedPersistentDir = File(tempDir, "persistent").apply { mkdirs() }
        val manager = VoskModelManager(dataDirectory = { tempDir }, persistentDirectoryProvider = { isolatedPersistentDir })
        val importZip = File(tempDir, "vosk-model-imported.zip")
        createZipWithModel(importZip, "vosk-model-imported")

        val result = manager.importModelFromFile(importZip)
        val dir = result.get()
        assertNotNull(dir)
        assertTrue(dir.isDirectory)
        assertEquals(manager.modelsDirectory.absolutePath, dir.parentFile.absolutePath, "Import should extract into modelsDirectory")
        assertFalse(File(isolatedPersistentDir, "vosk-model-imported").exists(), "Import should NOT copy to persistent directory")
        assertTrue(manager.isInstalled("vosk-model-imported"))
        assertTrue(manager.deleteModel("vosk-model-imported"))
    }

    @Test
    fun `persistent directory acts as read fallback for existing models`() {
        val isolatedPersistentDir = File(tempDir, "persistent").apply { mkdirs() }
        val manager = VoskModelManager(dataDirectory = { tempDir }, persistentDirectoryProvider = { isolatedPersistentDir })

        // Place a model only in the persistent directory
        val legacyModelDir = File(isolatedPersistentDir, "vosk-model-small-en-us-0.15").apply { mkdirs() }
        writeFakeModelFiles(legacyModelDir)

        assertTrue(manager.isInstalled("vosk-model-small-en-us-0.15"), "Should detect model in persistent fallback directory")
        assertEquals(legacyModelDir.absolutePath, manager.findModelForLanguage("en")?.absolutePath, "Should find model in persistent fallback directory")
    }

    @Test
    fun `resumable download controller supports pause resume and cancel`() {
        val controller = VoskDownloadController()
        assertFalse(controller.isPaused.get())
        assertFalse(controller.isCancelled.get())

        controller.pause()
        assertTrue(controller.isPaused.get())

        controller.resume()
        assertFalse(controller.isPaused.get())

        controller.cancel()
        assertTrue(controller.isCancelled.get())
    }

    @Test
    fun `controller waitIfPaused blocks until resume without busy wait`() {
        val controller = VoskDownloadController()
        controller.pause()

        var waitResult: Boolean? = null
        val waitThread = kotlin.concurrent.thread {
            waitResult = controller.waitIfPaused()
        }

        // Wait brief period to confirm thread is actually waiting and hasn't returned
        Thread.sleep(150)
        assertNull(waitResult, "waitIfPaused should block while paused")

        // Resume controller -> thread should unblock and return true
        controller.resume()
        waitThread.join(3_000)

        assertEquals(true, waitResult, "waitIfPaused should return true after resume")
    }

    @Test
    fun `controller waitIfPaused unblocks on cancel and returns false`() {
        val controller = VoskDownloadController()
        controller.pause()

        var waitResult: Boolean? = null
        val waitThread = kotlin.concurrent.thread {
            waitResult = controller.waitIfPaused()
        }

        Thread.sleep(150)
        assertNull(waitResult)

        controller.cancel()
        waitThread.join(3_000)

        assertEquals(false, waitResult, "waitIfPaused should return false after cancel")
    }

    @Test
    fun `atomic install cleans up extracting temp folder on invalid structure`() {
        val manager = VoskModelManager(dataDirectory = { tempDir })
        val badZip = File(tempDir, "bad.zip")
        ZipOutputStream(FileOutputStream(badZip)).use { zos ->
            zos.putNextEntry(ZipEntry("corrupt/some-file.txt"))
            zos.write("corrupt".toByteArray())
            zos.closeEntry()
        }

        val targetDir = File(manager.modelsDirectory, "corrupt-model")
        val res = manager.atomicInstallFromArchive(badZip, targetDir)

        assertTrue(res.getError() != null)
        assertFalse(targetDir.exists())
        assertFalse(File(manager.modelsDirectory, "corrupt-model.extracting").exists())
    }

    @Test
    fun `atomic install correctly finds model root when archive has different folder structure`() {
        val manager = VoskModelManager(dataDirectory = { tempDir })
        val archiveZip = File(tempDir, "nested.zip")
        ZipOutputStream(FileOutputStream(archiveZip)).use { zos ->
            zos.putNextEntry(ZipEntry("different-inner-name/am/final.mdl"))
            zos.write("fake-model-binary".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("different-inner-name/conf/model.conf"))
            zos.write("sample_rate=16000".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("different-inner-name/graph/HCLG.fst"))
            zos.write("fake-graph".toByteArray())
            zos.closeEntry()
        }

        val targetDir = File(manager.modelsDirectory, "vosk-model-nested")
        val res = manager.atomicInstallFromArchive(archiveZip, targetDir)

        assertNotNull(res.get())
        assertTrue(targetDir.exists())
        assertTrue(manager.isInstalled("vosk-model-nested"))
        assertFalse(File(manager.modelsDirectory, "vosk-model-nested.extracting").exists())
    }

    @Test
    fun `concurrent download of the same model is rejected`() = runBlocking {
        val fakeModel = VoskModel(
            name = "vosk-model-concurrent-test",
            language = "en",
            label = "Concurrent Test",
            sizeBytes = 1024L,
            url = "https://example.com/fake.zip"
        )

        val latch = java.util.concurrent.CountDownLatch(1)
        val downloadStarted = java.util.concurrent.CountDownLatch(1)

        val manager = VoskModelManager(
            dataDirectory = { tempDir },
            downloader = { _, targetZip, _ ->
                downloadStarted.countDown()
                latch.await()
                createZipWithModel(targetZip, fakeModel.name)
                Ok(Unit)
            }
        )

        val firstJob = this.launch(kotlinx.coroutines.Dispatchers.IO) {
            manager.downloadModel(fakeModel)
        }

        downloadStarted.await()
        assertTrue(manager.isDownloading(fakeModel.name))

        val secondResult = manager.downloadModel(fakeModel)
        val error = secondResult.getError()
        assertNotNull(error)
        assertTrue(error.message.contains("already being downloaded"))

        latch.countDown()
        firstJob.join()

        assertFalse(manager.isDownloading(fakeModel.name))
        assertTrue(manager.isInstalled(fakeModel.name))
    }

    @Test
    fun `resumable download handles HTTP 416 with complete part file`() {
        val fakeZip = File(tempDir, "server-fake.zip")
        createZipWithModel(fakeZip, "vosk-model-416-test")
        val fakeZipBytes = fakeZip.readBytes()

        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/model.zip") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range != null) {
                exchange.responseHeaders.set("Content-Range", "bytes */${fakeZipBytes.size}")
                exchange.sendResponseHeaders(416, -1) // no response body
            } else {
                exchange.sendResponseHeaders(200, fakeZipBytes.size.toLong())
                exchange.responseBody.use { it.write(fakeZipBytes) }
            }
        }
        server.start()

        try {
            val manager = VoskModelManager(dataDirectory = { tempDir })
            val model = VoskModel(
                name = "vosk-model-416-test",
                language = "en",
                label = "416 Test",
                sizeBytes = fakeZipBytes.size.toLong(),
                url = "http://127.0.0.1:${server.address.port}/model.zip"
            )

            // Create complete .part file
            val partFile = File(manager.modelsDirectory, "${model.name}.zip.part")
            partFile.writeBytes(fakeZipBytes)

            val controller = VoskDownloadController()
            val result = manager.downloadModelResumable(
                model = model,
                controller = controller,
                onProgress = { _, _, _, _ -> },
                onExtracting = {}
            )

            val dir = result.get()
            assertNotNull(dir)
            assertTrue(manager.isInstalled(model.name))
            assertFalse(partFile.exists())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `resumable download handles HTTP 416 with stale part file by restarting`() {
        val fakeZip = File(tempDir, "server-fake-stale.zip")
        createZipWithModel(fakeZip, "vosk-model-416-stale-test")
        val fakeZipBytes = fakeZip.readBytes()

        var requestCount = 0
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/model.zip") { exchange ->
            requestCount++
            val range = exchange.requestHeaders.getFirst("Range")
            if (range != null && requestCount == 1) {
                exchange.responseHeaders.set("Content-Range", "bytes */${fakeZipBytes.size}")
                exchange.sendResponseHeaders(416, -1)
            } else {
                exchange.sendResponseHeaders(200, fakeZipBytes.size.toLong())
                exchange.responseBody.use { it.write(fakeZipBytes) }
            }
        }
        server.start()

        try {
            val manager = VoskModelManager(dataDirectory = { tempDir })
            val model = VoskModel(
                name = "vosk-model-416-stale-test",
                language = "en",
                label = "416 Stale Test",
                sizeBytes = fakeZipBytes.size.toLong(),
                url = "http://127.0.0.1:${server.address.port}/model.zip"
            )

            // Create corrupt/stale .part file whose length differs from server
            val partFile = File(manager.modelsDirectory, "${model.name}.zip.part")
            partFile.writeBytes(ByteArray(fakeZipBytes.size + 100))

            val controller = VoskDownloadController()
            val result = manager.downloadModelResumable(
                model = model,
                controller = controller,
                onProgress = { _, _, _, _ -> },
                onExtracting = {}
            )

            val dir = result.get()
            assertNotNull(dir)
            assertTrue(manager.isInstalled(model.name))
            assertFalse(partFile.exists())
            assertTrue(requestCount >= 2, "Expected at least 2 requests (initial 416 then restart)")
        } finally {
            server.stop(0)
        }
    }
}
