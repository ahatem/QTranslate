package com.pnix.qtranslate

import com.formdev.flatlaf.FlatLaf
import com.google.gson.Gson
import com.pnix.qtranslate.common.Localizer
import com.pnix.qtranslate.common.UserAgent
import com.pnix.qtranslate.models.Configurations
import com.pnix.qtranslate.presentation.main_frame.QTranslateFrame
import com.pnix.qtranslate.utils.setupTheme
import kong.unirest.core.Unirest
import org.eclipse.swt.SWT
import org.eclipse.swt.browser.*
import org.eclipse.swt.events.SelectionAdapter
import org.eclipse.swt.events.SelectionEvent
import org.eclipse.swt.layout.*
import org.eclipse.swt.widgets.*
import java.awt.ComponentOrientation
import javax.swing.SwingUtilities
import javax.swing.UIManager


/*
* https://www.logicbig.com/tutorials/java-swing/text-suggestion-component.html
* TODO:
*   Maybe: Change hotkeys to enum constants instead of strings!
*   Add Auto-Complete POPUP TO TTextArea
*   Audio can't be stopped ... so make stop it (if same text OR the content of the clipboard not changed)
*   Languages in settings are not functional
*   Search Dictionaries Ctrl + right mouse click or ctrl+shift+q
*   Create Virtual Keyboard
*/

fun main() {
  System.setProperty("org.eclipse.swt.browser.DefaultType", "edge")
  System.setProperty("sun.awt.xembedserver", "true")

  SwingUtilities.invokeLater {
    Configurations.setupTheme()
    FlatLaf.setUseNativeWindowDecorations(Configurations.enableWindowStyle)
    UIManager.put("TitlePane.unifiedBackground", Configurations.unifyTitleBar)
    UIManager.put("TitlePane.showIcon", false)
    UIManager.put("ScrollBar.showButtons", false)

    QTranslateFrame().apply {
      applyComponentOrientation(ComponentOrientation.getOrientation(Localizer.currentLocale))
      isVisible = true
    }
  }

}

// FOR LATER USE
class AdvancedBrowser(location: String?) {
  init {
    val display = Display()
    val shell = Shell(display)
    shell.text = "Advanced Browser"
    shell.layout = FormLayout()
    val controls = Composite(shell, SWT.NONE)
    var data = FormData()
    data.top = FormAttachment(0, 0)
    data.left = FormAttachment(0, 0)
    data.right = FormAttachment(100, 0)
    controls.layoutData = data
    val status = Label(shell, SWT.NONE)
    data = FormData()
    data.left = FormAttachment(0, 0)
    data.right = FormAttachment(100, 0)
    data.bottom = FormAttachment(100, 0)
    status.layoutData = data
    val browser = Browser(shell, SWT.BORDER)
    data = FormData()
    data.top = FormAttachment(controls)
    data.bottom = FormAttachment(status)
    data.left = FormAttachment(0, 0)
    data.right = FormAttachment(100, 0)
    browser.layoutData = data
    controls.layout = GridLayout(7, false)
    var button = Button(controls, SWT.PUSH)
    button.text = "Back"
    button.addSelectionListener(object : SelectionAdapter() {
      override fun widgetSelected(event: SelectionEvent) {
        browser.back()
      }
    })
    button = Button(controls, SWT.PUSH)
    button.text = "Forward"
    button.addSelectionListener(object : SelectionAdapter() {
      override fun widgetSelected(event: SelectionEvent) {
        browser.forward()
      }
    })
    button = Button(controls, SWT.PUSH)
    button.text = "Refresh"
    button.addSelectionListener(object : SelectionAdapter() {
      override fun widgetSelected(event: SelectionEvent) {
        browser.refresh()
      }
    })
    button = Button(controls, SWT.PUSH)
    button.text = "Stop"
    button.addSelectionListener(object : SelectionAdapter() {
      override fun widgetSelected(event: SelectionEvent) {
        browser.stop()
      }
    })
    val url = Text(controls, SWT.BORDER)
    url.layoutData = GridData(GridData.FILL_HORIZONTAL)
    url.setFocus()
    button = Button(controls, SWT.PUSH)
    button.text = "Go"
    button.addSelectionListener(object : SelectionAdapter() {
      override fun widgetSelected(event: SelectionEvent) {
        browser.url = url.text
      }
    })
    val throbber = Label(controls, SWT.NONE)
    throbber.text = AT_REST
    shell.defaultButton = button
    browser.addCloseWindowListener(AdvancedCloseWindowListener())
    browser.addLocationListener(AdvancedLocationListener(url))
    browser.addProgressListener(AdvancedProgressListener(throbber))
    browser.addStatusTextListener(AdvancedStatusTextListener(status))

    // Go to the initial URL
    if (location != null) {
      browser.url = location
    }
    shell.open()
    while (!shell.isDisposed) {
      if (!display.readAndDispatch()) {
        display.sleep()
      }
    }
    display.dispose()
  }

  internal class AdvancedCloseWindowListener : CloseWindowListener {
    override fun close(event: WindowEvent) {
      (event.widget as Browser).shell.close()
    }
  }

  internal class AdvancedLocationListener(text: Text) : LocationListener {
    private val location: Text

    init {
      location = text
    }

    override fun changing(event: LocationEvent) {
      location.text = "Loading " + event.location + "..."
    }

    override fun changed(event: LocationEvent) {
      location.text = event.location
    }
  }

  internal class AdvancedProgressListener(label: Label) : ProgressListener {
    private val progress: Label

    init {
      progress = label
    }

    override fun changed(event: ProgressEvent) {
      if (event.total !== 0) {
        val percent = (event.current / event.total)
        progress.text = "$percent%"
      } else {
        progress.text = "?"
      }
    }

    override fun completed(event: ProgressEvent?) {
      progress.text = AT_REST
    }
  }

  internal class AdvancedStatusTextListener(label: Label) : StatusTextListener {
    private val status: Label

    init {
      status = label
    }

    override fun changed(event: StatusTextEvent) {
      status.text = event.text
    }
  }

  companion object {
    private const val AT_REST = "Ready"
  }
}

fun sendGetRequestsWithDifferentIps() {
  // from megabasterd proxy list
  val ipAddresses = arrayOf(
    "167.172.148.136:80",
    "178.33.198.181:3128",
    "5.172.177.196:3128",
    "178.128.148.144:80",
    "41.65.168.49:1981",
    "89.132.144.41:9090",
    "45.92.108.112:8080"
  )

  for (proxyAddress in ipAddresses) {
    val parts = proxyAddress.split(":")
    val ipAddress = parts[0]
    val port = parts[1].toInt()
    runCatching {
      val unirest = Unirest.primaryInstance()
      unirest.config().proxy(ipAddress, port)
      val response = unirest.get("http://example.com/")
        .asString()
        .body
      println(response)
    }
  }
}

fun getAutoComplete(text: String) {
  val response = Unirest.get("http://google.com/complete/search?client=chrome&q=$text")
    .header("user-agent", UserAgent.random())
    .asString()

  val gson = Gson()
  val data = gson.fromJson(response.body, Array<Any>::class.java)
  val autoCompletionsString = data[1].toString()
  val autoCompletions = autoCompletionsString
    .substring(1, autoCompletionsString.length - 1)
    .split(", ")
    .filter { !it.matches(Regex("^https?://.*")) }
    .take(10)

  autoCompletions.forEach {
    println(it)
  }

//  for result in json.loads(response.text)[1]:
}

