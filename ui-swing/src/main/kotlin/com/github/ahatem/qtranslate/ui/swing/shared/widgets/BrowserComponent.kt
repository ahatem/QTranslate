package com.github.ahatem.qtranslate.ui.swing.shared.widgets


import org.eclipse.swt.SWT
import org.eclipse.swt.SWTError
import org.eclipse.swt.awt.SWT_AWT
import org.eclipse.swt.browser.*
import org.eclipse.swt.layout.FillLayout
import org.eclipse.swt.widgets.Display
import org.eclipse.swt.widgets.Shell
import java.awt.BorderLayout
import java.awt.Canvas
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JPanel
import javax.swing.SwingUtilities

enum class BrowserEngine(internal val style: Int) {
    DEFAULT(SWT.NONE),
    WEBKIT(SWT.WEBKIT),
    EDGE(SWT.EDGE),
}

class BrowserComponent(
    private val engine: BrowserEngine = BrowserEngine.DEFAULT,
    private val onUrlChanged: (url: String) -> Unit = {},
    private val onTitleChanged: (title: String) -> Unit = {},
    private val onLoadingStateChanged: (isLoading: Boolean) -> Unit = {},
    private val onError: (message: String) -> Unit = {}
) : JPanel(BorderLayout()) {

    private val canvas = Canvas()
    private val browserRef = AtomicReference<Browser?>()
    private val swtThreadRef = AtomicReference<Thread?>()

    init {
        add(canvas, BorderLayout.CENTER)

        canvas.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                resizeBrowser()
            }
        })
    }

    override fun addNotify() {
        super.addNotify()
        if (swtThreadRef.get() == null) {
            val thread = SwtThread()
            if (swtThreadRef.compareAndSet(null, thread)) {
                thread.start()
            }
        }
    }

    override fun removeNotify() {
        dispose()
        super.removeNotify()
    }

    fun loadUrl(url: String) = executeOnSwtThread { it.url = url }
    fun back() = executeOnSwtThread { it.back() }
    fun forward() = executeOnSwtThread { it.forward() }
    fun refresh() = executeOnSwtThread { it.refresh() }
    fun stop() = executeOnSwtThread { it.stop() }

    private fun dispose() {
        swtThreadRef.getAndSet(null)?.interrupt()
        browserRef.getAndSet(null)
    }

    private fun executeOnSwtThread(action: (Browser) -> Unit) {
        browserRef.get()?.display?.asyncExec {
            browserRef.get()?.takeIf { !it.isDisposed }?.let(action)
        }
    }

    private fun resizeBrowser() {
        val browser = browserRef.get() ?: return
        val display = browser.display
        if (display.isDisposed) return
        display.asyncExec {
            val shell = browser.shell
            if (shell.isDisposed) return@asyncExec
            val size = canvas.size
            shell.setSize(size.width, size.height)
            shell.layout(true, true)
        }
    }

    private inner class SwtThread : Thread("SWT-Browser-Thread") {
        override fun run() {
            val display: Display
            val shell: Shell
            val browser: Browser

            try {
                display = Display()
                shell = SWT_AWT.new_Shell(display, canvas)
                shell.layout = FillLayout()

                browser = try {
                    Browser(shell, engine.style)
                } catch (e: SWTError) {
                    SwingUtilities.invokeLater {
                        onError("Failed to create browser with ${engine.name}. ${e.message}")
                    }
                    display.dispose()
                    return
                }

                if (!browserRef.compareAndSet(null, browser)) {
                    display.dispose()
                    return
                }
            } catch (e: Throwable) {
                SwingUtilities.invokeLater { onError("Failed to initialize SWT Browser: ${e.message}") }
                return
            }

            browser.addLocationListener(object : LocationAdapter() {
                override fun changed(event: LocationEvent) {
                    if (event.top) SwingUtilities.invokeLater { onUrlChanged(event.location) }
                }
            })

            browser.addTitleListener { event ->
                SwingUtilities.invokeLater { onTitleChanged(event.title) }
            }

            browser.addProgressListener(object : ProgressAdapter() {
                override fun changed(event: ProgressEvent) {
                    if (event.current < event.total) SwingUtilities.invokeLater { onLoadingStateChanged(true) }
                }

                override fun completed(event: ProgressEvent) {
                    SwingUtilities.invokeLater { onLoadingStateChanged(false) }
                }
            })

            display.asyncExec {
                val size = canvas.size
                shell.setSize(size.width, size.height)
                shell.layout(true, true)
                shell.open()
            }

            try {
                while (!isInterrupted && !shell.isDisposed) {
                    if (!display.readAndDispatch()) display.sleep()
                }
            } catch (_: Exception) {
            } finally {
                if (!display.isDisposed) display.dispose()
            }
        }
    }
}




