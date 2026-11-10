package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.swing.SwingUtilities

/**
 * Metadata for a downloadable Vosk speech recognition model.
 *
 * Hosted on alphacephei.com/vosk/models. Small models (~30-50 MB) run fast with low memory;
 * large models offer higher accuracy at the cost of more disk and memory.
 */
data class VoskModel(
    val name: String,
    val language: String,
    val label: String,
    val sizeBytes: Long,
    val url: String,
    val isLarge: Boolean = false
) {
    val sizeMb: Int get() = (sizeBytes / (1024 * 1024)).toInt()
}

/**
 * Manages local Vosk speech models: listing available models, checking disk installation,
 * downloading archives from alphacephei.com with progress, unzipping, verifying, and deleting.
 *
 * Models are stored inside the plugin's dedicated data directory under `vosk-models/`.
 */
class VoskModelManager(
    private val dataDirectory: () -> File,
    private val persistentDirectoryProvider: (() -> File)? = null,
    private val downloader: suspend (url: String, targetZip: File, onProgress: ((Long, Long) -> Unit)?) -> Result<Unit, ServiceError> = ::defaultDownload
) {
    private val activeDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isDownloading(modelName: String): Boolean = activeDownloads.contains(modelName)

    fun currentlyDownloadingModel(): String? = activeDownloads.firstOrNull()

    val modelsDirectory: File
        get() = File(dataDirectory(), "vosk-models").apply { if (!exists()) mkdirs() }

    val persistentModelsDirectory: File by lazy {
        if (persistentDirectoryProvider != null) {
            persistentDirectoryProvider.invoke()
        } else {
            val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
            File(base, "QTranslate/plugins_data/speech-services/vosk-models")
        }
    }

    /**
     * Validates whether a directory contains a runnable Vosk model. Supports both standard layouts:
     *
     * 1. New/nested layout:
     *    - Acoustic model: `am/final.mdl`
     *    - Model configuration: `conf/model.conf`
     *    - Decoding graph: `graph/HCLG.fst` OR (`graph/HCLr.fst` + `graph/Gr.fst`)
     *
     * 2. Old/flat layout (e.g. `vosk-model-small-tr-0.3`, `vosk-model-small-pt-0.3`):
     *    - Acoustic model: `final.mdl`
     *    - Model configuration: `mfcc.conf`
     *    - Decoding graph: `HCLG.fst` OR (`HCLr.fst` + `Gr.fst`)
     *
     * Checking for these required files prevents incomplete extractions from counting as installed
     * while properly accepting all official models from alphacephei.com.
     */
    internal fun File.isValidModelDir(): Boolean {
        if (!isDirectory) return false

        // Check new nested layout
        val hasNestedAcoustic = File(this, "am/final.mdl").isFile
        val hasNestedConf = File(this, "conf/model.conf").isFile
        val hasNestedGraph = File(this, "graph/HCLG.fst").isFile ||
            (File(this, "graph/HCLr.fst").isFile && File(this, "graph/Gr.fst").isFile)
        if (hasNestedAcoustic && hasNestedConf && hasNestedGraph) return true

        // Check old flat layout
        val hasFlatAcoustic = File(this, "final.mdl").isFile
        val hasFlatConf = File(this, "mfcc.conf").isFile
        val hasFlatGraph = File(this, "HCLG.fst").isFile ||
            (File(this, "HCLr.fst").isFile && File(this, "Gr.fst").isFile)
        return hasFlatAcoustic && hasFlatConf && hasFlatGraph
    }

    /**
     * Determines if a folder in [modelsDirectory] or [persistentModelsDirectory] exists, is non-empty,
     * but fails [isValidModelDir].
     */
    internal fun File.isIncompleteModelDir(): Boolean {
        if (!isDirectory) return false
        val files = listFiles() ?: return false
        return files.isNotEmpty() && !isValidModelDir()
    }

    /**
     * Locates the root directory containing required Vosk model files within [searchDir] (up to depth 2).
     * This avoids brittle name-guessing when archive root folders differ from the model catalogue name (A4).
     */
    internal fun findModelRoot(searchDir: File): File? {
        if (!searchDir.exists() || !searchDir.isDirectory) return null
        if (searchDir.isValidModelDir()) return searchDir

        val children = searchDir.listFiles() ?: return null
        for (child in children) {
            if (child.isDirectory && child.isValidModelDir()) return child
        }

        for (child in children) {
            if (child.isDirectory) {
                val subchildren = child.listFiles() ?: continue
                for (sub in subchildren) {
                    if (sub.isDirectory && sub.isValidModelDir()) return sub
                }
            }
        }
        return null
    }

    /**
     * Atomically extracts an archive file into a temporary folder, identifies the valid model root,
     * and moves it into [targetDir] (A2, A4). Cleans up any temp files on failure or cancellation.
     */
    internal fun atomicInstallFromArchive(
        archiveFile: File,
        targetDir: File,
        destinationParentDir: File = modelsDirectory
    ): Result<File, ServiceError> {
        val tempExtractDir = File(destinationParentDir, "${targetDir.name}.extracting")
        if (tempExtractDir.exists()) tempExtractDir.deleteRecursively()
        tempExtractDir.mkdirs()

        return try {
            extractZip(archiveFile, tempExtractDir)
            archiveFile.delete()

            val modelRoot = findModelRoot(tempExtractDir)
                ?: return Err(ServiceError.ValidationError("Extracted archive does not contain a valid Vosk model structure."))

            if (targetDir.exists()) targetDir.deleteRecursively()

            if (modelRoot == tempExtractDir) {
                if (!tempExtractDir.renameTo(targetDir)) {
                    tempExtractDir.copyRecursively(targetDir, overwrite = true)
                    tempExtractDir.deleteRecursively()
                }
            } else {
                if (!modelRoot.renameTo(targetDir)) {
                    modelRoot.copyRecursively(targetDir, overwrite = true)
                }
                tempExtractDir.deleteRecursively()
            }

            if (!targetDir.isValidModelDir()) {
                targetDir.deleteRecursively()
                return Err(ServiceError.ValidationError("Extracted model directory is empty or invalid: ${targetDir.name}"))
            }

            Ok(targetDir)
        } catch (e: Exception) {
            tempExtractDir.deleteRecursively()
            Err(ServiceError.ValidationError("Failed to extract or install model: ${e.message}"))
        } finally {
            if (tempExtractDir.exists()) tempExtractDir.deleteRecursively()
        }
    }

    fun isInstalled(modelName: String): Boolean {
        if (File(modelsDirectory, modelName).isValidModelDir()) return true
        if (File(persistentModelsDirectory, modelName).isValidModelDir()) return true
        return false
    }

    /**
     * Infers the language tag (e.g. "ar", "en", "de") for a given model directory.
     * Looks for model-info.json first, then known catalogue models, then name pattern (E1).
     */
    fun inferLanguageFromModelDir(dir: File): String {
        val meta = File(dir, "model-info.json")
        if (meta.isFile) {
            val content = runCatching { meta.readText() }.getOrNull().orEmpty()
            val match = Regex(""""language"\s*:\s*"([^"]+)"""").find(content)
            if (match != null) return match.groupValues[1].lowercase()
        }
        val known = MODELS_BY_NAME[dir.name]
        if (known != null) return known.language

        // Regex pattern: vosk-model[-small]-<lang>-... or vosk-model-<lang>
        val match = Regex("""vosk-model(?:-small)?-([a-z]{2,3}(?:-[a-z]{2})?)""", RegexOption.IGNORE_CASE).find(dir.name)
        if (match != null) return match.groupValues[1].lowercase()

        return "unknown"
    }

    fun listInstalledModels(): List<File> {
        val localList = if (modelsDirectory.exists() && modelsDirectory.isDirectory) {
            modelsDirectory.listFiles { file -> file.isValidModelDir() }?.toList() ?: emptyList()
        } else emptyList()

        val persistentList = if (persistentModelsDirectory.exists() && persistentModelsDirectory.isDirectory) {
            persistentModelsDirectory.listFiles { file -> file.isValidModelDir() }?.toList() ?: emptyList()
        } else emptyList()

        // Merge, avoiding duplicates by directory name
        val map = LinkedHashMap<String, File>()
        localList.forEach { map[it.name] = it }
        persistentList.forEach { if (!map.containsKey(it.name)) map[it.name] = it }
        return map.values.toList()
    }

    /**
     * Resolves an installed model directory for [language] (e.g. "en", "ar").
     *
     * If [preferredModelName] is installed, it is returned first. Otherwise returns any installed
     * model matching the language prefix.
     */
    fun findModelForLanguage(language: String, preferredModelName: String? = null): File? {
        val clean = language.trim().lowercase()
        val isAuto = clean == "auto" || clean.isEmpty()
        val lang = if (isAuto) "auto" else clean.substringBefore('-').substringBefore('_').take(2)

        if (!preferredModelName.isNullOrBlank()) {
            val preferredDir = File(modelsDirectory, preferredModelName).takeIf { it.isValidModelDir() }
                ?: File(persistentModelsDirectory, preferredModelName).takeIf { it.isValidModelDir() }
            if (preferredDir != null) {
                val preferredLang = inferLanguageFromModelDir(preferredDir)
                if (isAuto || preferredLang == lang || preferredLang.startsWith(lang)) {
                    return preferredDir
                }
            }
        }

        val installed = listInstalledModels()
        if (isAuto) {
            // C5: If preferred model is installed, use its language for Auto
            if (!preferredModelName.isNullOrBlank()) {
                val preferredDir = File(modelsDirectory, preferredModelName).takeIf { it.isValidModelDir() }
                    ?: File(persistentModelsDirectory, preferredModelName).takeIf { it.isValidModelDir() }
                if (preferredDir != null) return preferredDir
            }

            // Check if an installed model matches the system locale language first
            val systemLang = java.util.Locale.getDefault().language.lowercase()
            if (systemLang.isNotEmpty()) {
                val sysModel = installed.firstOrNull { dir ->
                    inferLanguageFromModelDir(dir) == systemLang
                }
                if (sysModel != null) return sysModel
            }

            // Otherwise prioritize English model if installed
            val enModel = installed.firstOrNull { dir ->
                inferLanguageFromModelDir(dir) == "en"
            }
            if (enModel != null) return enModel

            return installed.minByOrNull { it.name }
        }

        return installed.firstOrNull { dir ->
            val inferred = inferLanguageFromModelDir(dir)
            inferred == lang || inferred.startsWith(lang)
        }
    }

    /**
     * Downloads [model] archive from alphacephei.com, extracts it into [modelsDirectory],
     * verifies the extracted files, and removes the archive.
     */
    suspend fun downloadModel(
        model: VoskModel,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File, ServiceError> = withContext(Dispatchers.IO) {
        val targetDir = File(modelsDirectory, model.name)
        if (isInstalled(model.name)) {
            return@withContext Ok(targetDir)
        }

        if (!activeDownloads.add(model.name)) {
            return@withContext Err(ServiceError.ValidationError("Model '${model.name}' is already being downloaded."))
        }

        val tempZip = File(modelsDirectory, "${model.name}.zip.tmp")
        try {
            val downloadError = downloader(model.url, tempZip, onProgress).getError()
            if (downloadError != null) {
                tempZip.delete()
                return@withContext Err(downloadError)
            }

            atomicInstallFromArchive(tempZip, targetDir)
        } catch (e: Exception) {
            tempZip.delete()
            targetDir.deleteRecursively()
            Err(ServiceError.NetworkError("Failed to download or extract model '${model.name}': ${e.message}", e))
        } finally {
            activeDownloads.remove(model.name)
            if (tempZip.exists()) tempZip.delete()
        }
    }

    fun deleteModel(modelName: String): Boolean {
        val dir1 = File(modelsDirectory, modelName)
        val dir2 = File(persistentModelsDirectory, modelName)
        val part1 = File(modelsDirectory, "$modelName.zip.part")
        val extract1 = File(modelsDirectory, "$modelName.extracting")
        var deleted = false
        if (dir1.exists()) deleted = dir1.deleteRecursively() || deleted
        if (dir2.exists()) deleted = dir2.deleteRecursively() || deleted
        if (part1.exists()) deleted = part1.delete() || deleted
        if (extract1.exists()) deleted = extract1.deleteRecursively() || deleted
        return deleted
    }

    /**
     * Imports an existing Vosk model from a local ZIP file or folder into the persistent storage.
     */
    fun importModelFromFile(sourceFile: File): Result<File, ServiceError> {
        if (!sourceFile.exists()) {
            return Err(ServiceError.ValidationError("File or folder does not exist: ${sourceFile.absolutePath}"))
        }

        val targetRoot = modelsDirectory
        return try {
            if (sourceFile.isDirectory) {
                // Folder import
                val targetDir = File(targetRoot, sourceFile.name)
                sourceFile.copyRecursively(targetDir, overwrite = true)
                Ok(targetDir)
            } else if (sourceFile.name.endsWith(".zip", ignoreCase = true)) {
                // ZIP archive import
                val guessedName = sourceFile.nameWithoutExtension
                val tempZipCopy = File(targetRoot, "$guessedName.import.tmp.zip")
                sourceFile.copyTo(tempZipCopy, overwrite = true)
                atomicInstallFromArchive(tempZipCopy, File(targetRoot, guessedName))
            } else {
                Err(ServiceError.ValidationError("Please select a .zip archive or an extracted model folder."))
            }
        } catch (e: Exception) {
            Err(ServiceError.UnknownError("Failed to import model: ${e.message}", e))
        }
    }

    /** Interactive file chooser dialog to import a model. */
    fun importModelInteractive(): String {
        if (GraphicsEnvironment.isHeadless()) {
            return "Cannot open file chooser in headless environment."
        }

        var selectedFile: File? = null
        try {
            SwingUtilities.invokeAndWait {
                val chooser = javax.swing.JFileChooser().apply {
                    dialogTitle = SpeechLocalization.get("import_chooser_title")
                    fileSelectionMode = javax.swing.JFileChooser.FILES_AND_DIRECTORIES
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Vosk Model Archive (*.zip)", "zip")
                }
                val parent = Window.getWindows().firstOrNull { it.isFocused }
                    ?: Window.getWindows().firstOrNull { it.isVisible }
                val res = chooser.showOpenDialog(parent)
                if (res == javax.swing.JFileChooser.APPROVE_OPTION) {
                    selectedFile = chooser.selectedFile
                }
            }
        } catch (e: Exception) {
            return SpeechLocalization.get("import_failed", e.message ?: e.toString())
        }

        val file = selectedFile ?: return SpeechLocalization.get("import_cancelled")
        return importModelFromFile(file).fold(
            success = { dir -> SpeechLocalization.get("import_success", dir.name) },
            failure = { error -> SpeechLocalization.get("import_failed", error.message) }
        )
    }

    /** Message for the settings "Delete Model" action. */
    fun deleteAction(modelName: String): String {
        val model = MODELS_BY_NAME[modelName]
            ?: return SpeechLocalization.get("delete_unknown_model", modelName)
        if (!isInstalled(model.name)) {
            return SpeechLocalization.get("delete_not_installed", model.label)
        }
        val deleted = deleteModel(model.name)
        return if (deleted) {
            SpeechLocalization.get("delete_success", model.label)
        } else {
            SpeechLocalization.get("delete_failed", model.label)
        }
    }

    /** Message for the settings "Check Models" action. */
    fun statusAction(selectedModelName: String? = null): String {
        val installed = listInstalledModels()
        val selected = selectedModelName?.let { MODELS_BY_NAME[it] }
        val sb = StringBuilder()
        if (selected != null) {
            val localDir = File(modelsDirectory, selected.name)
            val persistentDir = File(persistentModelsDirectory, selected.name)
            val isInst = isInstalled(selected.name)
            val isIncomplete = localDir.isIncompleteModelDir() || persistentDir.isIncompleteModelDir()

            val statusStr = when {
                isInst -> SpeechLocalization.get("status_installed")
                isIncomplete -> SpeechLocalization.get("status_incomplete")
                else -> SpeechLocalization.get("status_not_downloaded")
            }
            sb.append(SpeechLocalization.get("status_selected_header", selected.label, statusStr))
        }
        if (installed.isEmpty()) {
            sb.append(SpeechLocalization.get("status_no_models"))
        } else {
            sb.append(SpeechLocalization.get("status_installed_header", installed.size))
            installed.forEach { dir ->
                val info = MODELS_BY_NAME[dir.name]
                val desc = info?.label ?: dir.name
                sb.append("• $desc (${dir.name})\n")
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * Resumable downloader supporting HTTP Range headers, pausing, resuming, cancellation,
     * transfer speed computation, and estimated time remaining.
     */
    fun downloadModelResumable(
        model: VoskModel,
        controller: VoskDownloadController,
        onProgress: (downloaded: Long, total: Long, speed: Long, etaSeconds: Long) -> Unit,
        onExtracting: () -> Unit
    ): Result<File, ServiceError> {
        val targetDir = File(modelsDirectory, model.name)
        if (isInstalled(model.name)) return Ok(targetDir)

        if (!activeDownloads.add(model.name)) {
            return Err(ServiceError.ValidationError("Model '${model.name}' is already being downloaded."))
        }

        val partFile = File(modelsDirectory, "${model.name}.zip.part")
        val finalZip = File(modelsDirectory, "${model.name}.zip")

        return try {
            var downloaded = if (partFile.exists()) partFile.length() else 0L
            var totalLength = -1L

            while (!controller.isCancelled.get() && (totalLength <= 0 || downloaded < totalLength)) {
                if (controller.isPaused.get()) {
                    val resumed = controller.waitIfPaused()
                    if (!resumed) break
                }

                downloaded = if (partFile.exists()) partFile.length() else 0L
                val (conn, code) = openConnectionWithRedirects(model.url, rangeStart = downloaded)

                // HTTP 416: Requested Range Not Satisfiable
                if (code == 416) {
                    val contentRangeTotal = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                    val realLength = contentRangeTotal ?: runCatching {
                        val (headConn, _) = openConnectionWithRedirects(model.url, method = "HEAD")
                        headConn.contentLengthLong.also { headConn.disconnect() }
                    }.getOrNull() ?: -1L
                    conn.disconnect()

                    if (realLength > 0 && partFile.exists() && partFile.length() == realLength) {
                        // The .part file is already completely downloaded
                        totalLength = realLength
                        downloaded = realLength
                        break
                    } else {
                        // The .part file is stale/invalid; restart from scratch
                        partFile.delete()
                        downloaded = 0L
                        continue
                    }
                }

                val append = (code == HttpURLConnection.HTTP_PARTIAL)
                if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
                    conn.disconnect()
                    return Err(ServiceError.NetworkError("HTTP $code from server while downloading ${model.name}"))
                }

                val contentRangeTotal = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                val serverContentLength = conn.contentLengthLong
                if (append) {
                    totalLength = contentRangeTotal ?: if (serverContentLength > 0) downloaded + serverContentLength else -1L
                } else {
                    totalLength = if (serverContentLength > 0) serverContentLength else -1L
                    downloaded = 0L
                }

                val reportedTotal = if (totalLength > 0) totalLength else model.sizeBytes

                partFile.parentFile?.mkdirs()
                val fos = FileOutputStream(partFile, append)

                conn.inputStream.use { input ->
                    fos.use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var speedAccumulator = 0L
                        var lastSpeedTime = System.currentTimeMillis()
                        var currentSpeed = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (controller.isCancelled.get()) {
                                partFile.delete()
                                return Err(ServiceError.UnknownError("Download cancelled by user."))
                            }
                            if (controller.isPaused.get()) {
                                break
                            }

                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            speedAccumulator += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastSpeedTime
                            if (elapsed >= 400) {
                                currentSpeed = (speedAccumulator * 1000) / elapsed
                                speedAccumulator = 0L
                                lastSpeedTime = now
                                val remaining = if (reportedTotal > downloaded) reportedTotal - downloaded else 0L
                                val eta = if (currentSpeed > 0) remaining / currentSpeed else -1L
                                onProgress(downloaded, reportedTotal, currentSpeed, eta)
                            }
                        }
                    }
                }

                if (controller.isCancelled.get()) {
                    partFile.delete()
                    return Err(ServiceError.UnknownError("Download cancelled by user."))
                }
            }

            if (controller.isCancelled.get()) {
                partFile.delete()
                return Err(ServiceError.UnknownError("Download cancelled by user."))
            }

            onExtracting()
            if (finalZip.exists()) finalZip.delete()
            if (!partFile.renameTo(finalZip)) {
                partFile.copyTo(finalZip, overwrite = true)
                partFile.delete()
            }

            atomicInstallFromArchive(finalZip, targetDir)
        } catch (e: Exception) {
            if (controller.isCancelled.get()) {
                partFile.delete()
                return Err(ServiceError.UnknownError("Download cancelled by user."))
            }
            Err(ServiceError.NetworkError("Download failed: ${e.message}", e))
        } finally {
            activeDownloads.remove(model.name)
            if (finalZip.exists()) finalZip.delete()
        }
    }

    /**
     * Downloads [modelName] with a real-time progress dialog on Swing, supporting pause, resume,
     * and ETA, or silently if in a headless environment.
     */
    fun downloadWithProgressDialog(modelName: String): String {
        val model = MODELS_BY_NAME[modelName]
            ?: return "Unknown model '$modelName'. Please choose a model from the list."

        if (isInstalled(model.name)) {
            return "Model '${model.label}' is already installed and ready to use."
        }

        if (isDownloading(model.name)) {
            return "Model '${model.label}' is already being downloaded."
        }

        // A6: Check free disk space before downloading (~2.5x archive size)
        val requiredBytes = (model.sizeBytes * 2.5).toLong()
        val usableBytes = modelsDirectory.usableSpace
        if (usableBytes > 0 && usableBytes < requiredBytes) {
            val reqMb = requiredBytes / (1024 * 1024)
            val availMb = usableBytes / (1024 * 1024)
            return SpeechLocalization.get("disk_space_error", model.label, "${reqMb}MB", "${availMb}MB")
        }

        val controller = VoskDownloadController()
        var dialog: VoskDownloadDialog? = null

        if (!GraphicsEnvironment.isHeadless()) {
            try {
                val parentWindow = Window.getWindows().firstOrNull { it.isFocused }
                    ?: Window.getWindows().firstOrNull { it.isVisible }

                SwingUtilities.invokeAndWait {
                    dialog = VoskDownloadDialog(parentWindow, model, controller).apply {
                        isVisible = true
                    }
                }
            } catch (_: Exception) {
                // Ignore if GUI initialization fails
            }
        }

        val result = downloadModelResumable(
            model = model,
            controller = controller,
            onProgress = { downloaded, total, speed, eta ->
                dialog?.updateProgress(downloaded, total, speed, eta)
            },
            onExtracting = {
                dialog?.notifyExtracting()
            }
        )

        return result.fold(
            success = {
                dialog?.notifyCompleted()
                "Model '${model.label}' (~${model.sizeMb} MB) downloaded and installed successfully."
            },
            failure = { error ->
                dialog?.notifyError(error.message)
                "Download error: ${error.message}"
            }
        )
    }

    /** Message for the settings "Download Model" action (called synchronously via reflection). */
    fun downloadAction(modelName: String): String = downloadWithProgressDialog(modelName)

    companion object {
        val AVAILABLE_MODELS = listOf(
            VoskModel(
                name = "vosk-model-small-en-us-0.15",
                language = "en",
                label = "English (US) small ~40MB",
                sizeBytes = 40 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-en-us-0.22-lgraph",
                language = "en",
                label = "English (US) large ~128MB (~1GB RAM)",
                sizeBytes = 128 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip",
                isLarge = true
            ),
            VoskModel(
                name = "vosk-model-ar-mgb2-0.4",
                language = "ar",
                label = "Arabic ~318MB",
                sizeBytes = 333_241_610L,
                url = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-ar-0.22-linto-1.1.0",
                language = "ar",
                label = "Arabic large ~1.3GB (~3GB RAM)",
                sizeBytes = 1_377_395_170L,
                url = "https://alphacephei.com/vosk/models/vosk-model-ar-0.22-linto-1.1.0.zip",
                isLarge = true
            ),
            VoskModel(
                name = "vosk-model-small-de-0.15",
                language = "de",
                label = "German small ~45MB",
                sizeBytes = 45 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-fr-0.22",
                language = "fr",
                label = "French small ~41MB",
                sizeBytes = 41 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-es-0.42",
                language = "es",
                label = "Spanish small ~39MB",
                sizeBytes = 39 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-it-0.22",
                language = "it",
                label = "Italian small ~48MB",
                sizeBytes = 48 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-ru-0.22",
                language = "ru",
                label = "Russian small ~45MB",
                sizeBytes = 45 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-tr-0.3",
                language = "tr",
                label = "Turkish small ~35MB",
                sizeBytes = 35 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-pt-0.3",
                language = "pt",
                label = "Portuguese small ~31MB",
                sizeBytes = 31 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-ja-0.22",
                language = "ja",
                label = "Japanese small ~48MB",
                sizeBytes = 48 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-cn-0.22",
                language = "zh",
                label = "Chinese small ~42MB",
                sizeBytes = 42 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
                isLarge = false
            ),
            VoskModel(
                name = "vosk-model-small-uk-v3-nano",
                language = "uk",
                label = "Ukrainian small ~73MB",
                sizeBytes = 73 * 1024 * 1024L,
                url = "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-nano.zip",
                isLarge = false
            )
        )

        val MODELS_BY_NAME: Map<String, VoskModel> = AVAILABLE_MODELS.associateBy { it.name }
        const val DEFAULT_MODEL_NAME = "vosk-model-small-en-us-0.15"

        /**
         * E3: Default to the catalogue's small model for the system language when one exists,
         * otherwise English small.
         */
        fun defaultModelForSystem(systemLang: String = java.util.Locale.getDefault().language.lowercase()): String {
            val matchingSmall = AVAILABLE_MODELS.firstOrNull { it.language == systemLang && !it.isLarge }
                ?: AVAILABLE_MODELS.firstOrNull { it.language == systemLang }
            return matchingSmall?.name ?: DEFAULT_MODEL_NAME
        }

        const val DROPDOWN_OPTIONS: String =
            "vosk-model-small-en-us-0.15,vosk-model-en-us-0.22-lgraph,vosk-model-ar-mgb2-0.4,vosk-model-ar-0.22-linto-1.1.0,vosk-model-small-de-0.15,vosk-model-small-fr-0.22,vosk-model-small-es-0.42,vosk-model-small-it-0.22,vosk-model-small-ru-0.22,vosk-model-small-tr-0.3,vosk-model-small-pt-0.3,vosk-model-small-ja-0.22,vosk-model-small-cn-0.22,vosk-model-small-uk-v3-nano"

        internal fun parseContentRangeTotal(contentRange: String?): Long? {
            if (contentRange.isNullOrBlank()) return null
            val slashIndex = contentRange.lastIndexOf('/')
            if (slashIndex != -1 && slashIndex + 1 < contentRange.length) {
                return contentRange.substring(slashIndex + 1).trim().toLongOrNull()
            }
            return null
        }

        internal fun openConnectionWithRedirects(
            urlStr: String,
            rangeStart: Long = 0L,
            readTimeoutMillis: Int = 30_000,
            method: String = "GET"
        ): Pair<HttpURLConnection, Int> {
            var url = URI(urlStr).toURL()
            var conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMillis
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "QTranslate/1.5.0")
            if (rangeStart > 0 && method == "GET") {
                conn.setRequestProperty("Range", "bytes=$rangeStart-")
            }

            var code = conn.responseCode
            var redirects = 0
            while (code in listOf(301, 302, 303, 307, 308) && redirects < 5) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URI(location).toURL()
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = readTimeoutMillis
                conn.setRequestProperty("User-Agent", "QTranslate/1.5.0")
                if (rangeStart > 0 && method == "GET") {
                    conn.setRequestProperty("Range", "bytes=$rangeStart-")
                }
                code = conn.responseCode
                redirects++
            }
            return conn to code
        }

        private suspend fun defaultDownload(
            urlStr: String,
            targetZip: File,
            onProgress: ((Long, Long) -> Unit)?
        ): Result<Unit, ServiceError> = withContext(Dispatchers.IO) {
            try {
                val (redirectedConn, code) = openConnectionWithRedirects(urlStr, readTimeoutMillis = 60_000)

                if (code !in 200..299) {
                    return@withContext Err(ServiceError.NetworkError("HTTP $code while downloading $urlStr"))
                }

                val totalLength = redirectedConn.contentLengthLong
                targetZip.parentFile?.mkdirs()

                redirectedConn.inputStream.use { input ->
                    FileOutputStream(targetZip).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        var accumulated = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            accumulated += bytesRead
                            onProgress?.invoke(accumulated, totalLength)
                        }
                    }
                }
                Ok(Unit)
            } catch (e: Exception) {
                Err(ServiceError.NetworkError("Download failed: ${e.message}", e))
            }
        }

        fun extractZip(zipFile: File, destinationDir: File) {
            val destCanonical = destinationDir.canonicalPath
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val destFile = File(destinationDir, entry.name)
                    val canonicalPath = destFile.canonicalPath
                    if (!canonicalPath.startsWith(destCanonical + File.separator) && canonicalPath != destCanonical) {
                        throw SecurityException("Zip traversal detected: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        destFile.mkdirs()
                    } else {
                        destFile.parentFile?.mkdirs()
                        FileOutputStream(destFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }
}
