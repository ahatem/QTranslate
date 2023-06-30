package com.pnix.qtranslate.presentation.components

import org.eclipse.swt.SWT
import org.eclipse.swt.awt.SWT_AWT
import org.eclipse.swt.browser.Browser
import org.eclipse.swt.layout.FillLayout
import org.eclipse.swt.widgets.Display
import org.eclipse.swt.widgets.Shell
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Dimension
import java.awt.event.WindowListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JPanel


class SwtBrowserCanvas : Canvas() {
  private val browserReference = AtomicReference<Browser?>()

  private val swtThreadReference = AtomicReference<SwtThread?>()
  val browser: Browser? get() = browserReference.get()

  fun setUrl(url: String?) {
    browser?.display?.asyncExec { browser?.setUrl(url) }
  }

  /**
   * Create the browser canvas component.
   *
   *
   * This must be called **after** the parent application Frame is made visible -
   * usually directly after `frame.setVisible(true)`.
   *
   *
   * This method creates the background thread, which in turn creates the SWT components and
   * handles the SWT event dispatch loop.
   *
   *
   * This method will block (for a very short time) until that thread has successfully created
   * the native browser component (or an error occurs).
   *
   * @return `true` if the browser component was successfully created; `false if it was not/` */
  fun initialize(): Boolean {
    val browserCreatedLatch = CountDownLatch(1)
    val swtThread = SwtThread(browserCreatedLatch)
    swtThreadReference.set(swtThread)
    swtThread.start()
    val result: Boolean = try {
      browserCreatedLatch.await()
      browserReference.get() != null
    } catch (e: InterruptedException) {
      e.printStackTrace()
      false
    }
    return result
  }

  /**
   * Dispose the browser canvas component.
   *
   *
   * This should be called from a [WindowListener.windowClosing] implementation.
   */
  fun dispose() {
    browserReference.set(null)
    val swtThread = swtThreadReference.getAndSet(null)
    swtThread?.interrupt()
  }

  /**
   * Implementation of a thread that creates the browser component and then implements an event
   * dispatch loop for SWT.
   */
  inner class SwtThread(private val browserCreatedLatch: CountDownLatch) : Thread() {
    override fun run() {
      // First prepare the SWT components...
      val display: Display
      val shell: Shell
      try {
        display = Display()
        shell = SWT_AWT.new_Shell(display, this@SwtBrowserCanvas)
        shell.layout = FillLayout()
        browserReference.set(Browser(shell, SWT.NONE))
      } catch (e: Exception) {
        e.printStackTrace()
        return
      } finally {
        // Guarantee the count-down so as not to block the caller, even in case of error -
        // there is a theoretical (rare) chance of failure to initialise the SWT components
        browserCreatedLatch.countDown()
      }
      // Execute the SWT event dispatch loop...
      try {
        shell.open()
        while (!isInterrupted && !shell.isDisposed) {
          if (!display.readAndDispatch()) {
            display.sleep()
          }
        }
        browserReference.set(null)
        shell.dispose()
        display.dispose()
      } catch (e: Exception) {
        e.printStackTrace()
        interrupt()
      }
    }
  }
}

class BrowserPanel : JPanel() {

  val browserCanvas = SwtBrowserCanvas()

  init {
    layout = BorderLayout()

    browserCanvas.minimumSize = Dimension(50, 50);


    add(browserCanvas, BorderLayout.CENTER)
  }

  fun initialize() = browserCanvas.initialize()
  fun dispose() = browserCanvas.dispose()
  fun setUrl(url: String) = browserCanvas.setUrl(url)
}