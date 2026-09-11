package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import com.github.ahatem.qtranslate.api.core.Logger
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/** Records the levels used by the capture flow so tests can assert what was logged. */
internal class RecordingLogger : Logger {

    val warns = mutableListOf<String>()
    val errors = mutableListOf<String>()

    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) { warns += message }
    override fun error(message: String, error: Throwable?) { errors += message }
}

/** Change monitor whose token only advances when a test advances it. */
internal class FakeChangeMonitor(var current: Long = 0L) : ClipboardChangeMonitor {

    override fun mark(): Long? = current

    override fun hasChangedSince(token: Long): Boolean = current != token
}

/**
 * In-memory clipboard that records everything ever published, like a clipboard manager would.
 */
internal class RecordingClipboard(text: String? = null) : SystemClipboard {

    val published = mutableListOf<String>()
    val restored = mutableListOf<ClipboardSnapshot>()
    var contents: ClipboardSnapshot? = text?.let { ClipboardSnapshot(StringSelection(it)) }
    var text: String? = text
    var restoreFailure: Throwable? = null
    var restoreCount = 0

    override fun snapshot(): ClipboardSnapshot? = contents

    override fun readText(): String? = text

    override fun signature(): Long? = null

    override fun restore(snapshot: ClipboardSnapshot) {
        restoreFailure?.let { throw it }
        restoreCount++
        restored += snapshot
        contents = snapshot
        val value = runCatching {
            snapshot.transferable.getTransferData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
        published += value ?: NON_TEXT
        text = value
    }

    /** Simulates another application putting [value] on the clipboard. */
    fun simulateExternalCopy(value: String?) {
        text = value
        contents = value?.let { ClipboardSnapshot(StringSelection(it)) }
    }

    fun setSnapshot(snapshot: ClipboardSnapshot) {
        contents = snapshot
        text = null
    }

    companion object {
        const val NON_TEXT = "<non-text>"
    }
}

/** Transferable backed by an explicit flavor map, for building snapshot fixtures. */
internal class MapTransferable(
    private val data: List<Pair<DataFlavor, Any>>,
) : Transferable {

    override fun getTransferDataFlavors(): Array<DataFlavor> = data.map { it.first }.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = data.any { it.first == flavor }

    override fun getTransferData(flavor: DataFlavor): Any =
        data.firstOrNull { it.first == flavor }?.second ?: throw UnsupportedFlavorException(flavor)
}
