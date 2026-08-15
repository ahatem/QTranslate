package com.github.ahatem.qtranslate.ui.swing.imagesearch

import java.awt.Image
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Fetches thumbnails off the event thread and hands them back on it.
 *
 * A search returns a dozen images at once. Loading them on the EDT would freeze the window for as
 * long as the slowest one takes, and loading them all at full parallelism would open a dozen
 * sockets to one host for what is a background nicety — so they go through a small pool and arrive
 * as they finish, each tile filling in on its own.
 *
 * Results are cached for the lifetime of the dialog: the same term searched twice, or a popup
 * closed and reopened, should not re-fetch. The cache is bounded because a long session of
 * searching would otherwise hold every image ever shown.
 */
class ThumbnailLoader(private val maxEntries: Int = 120) {

    private val pool = Executors.newFixedThreadPool(THREADS) { runnable ->
        Thread(runnable, "thumbnail-loader").apply { isDaemon = true }
    }

    /**
     * Access-ordered so eviction drops the least recently *used* rather than the oldest loaded —
     * scrolling back to an earlier result should not have to fetch it again.
     */
    private val cache: MutableMap<String, Image> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Image>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Image>) = size > maxEntries
        }
    )

    /**
     * Loads [url], calling [onLoaded] on the event thread.
     *
     * [onLoaded] is not called if the image cannot be fetched or decoded: a tile that stays a
     * placeholder is better than one showing a broken-image glyph, and the reason — an expired
     * URL, a format Java cannot read — is not something the reader can act on.
     */
    fun load(url: String, onLoaded: (Image) -> Unit) {
        cache[url]?.let {
            // Still asynchronous, so a caller building tiles in a loop cannot be re-entered
            // half-way through its own layout pass.
            SwingUtilities.invokeLater { onLoaded(it) }
            return
        }

        pool.execute {
            val image = runCatching { fetch(url) }.getOrNull() ?: return@execute
            cache[url] = image
            SwingUtilities.invokeLater { onLoaded(image) }
        }
    }

    fun shutdown() {
        pool.shutdownNow()
        cache.clear()
    }

    private fun fetch(url: String): Image? {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // Wikimedia returns 403 to the default Java agent, and the request is ours to
            // identify honestly.
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.inputStream.use(ImageIO::read)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val THREADS = 4
        const val TIMEOUT_MS = 10_000
        const val USER_AGENT = "QTranslate/1.0 (https://github.com/ahatem/QTranslate)"
    }
}
