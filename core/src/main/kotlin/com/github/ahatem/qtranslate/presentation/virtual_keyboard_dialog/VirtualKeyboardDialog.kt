package com.github.ahatem.qtranslate.presentation.virtual_keyboard_dialog

import com.github.ahatem.qtranslate.utils.GBHelper
import com.github.ahatem.qtranslate.utils.setPadding
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.*


class VirtualKeyboardButton : JButton()


class DirectionalButton(
    centerText: String?,
    val topLeftText: String = "",
    val topRightText: String = "",
    val bottomLeftText: String = "",
    val bottomRightText: String = "",

    ) : JButton(centerText) {

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val size = size
        val textHeight = getFontMetrics(font).height

        if (topLeftText.isNotEmpty()) g2d.drawString(topLeftText, 5, textHeight)

        val topRightTextWidth = getFontMetrics(font).stringWidth(topRightText)
        if (topRightText.isNotEmpty()) g2d.drawString(topRightText, size.width - topRightTextWidth, textHeight)

        if (bottomLeftText.isNotEmpty()) g2d.drawString(bottomLeftText, 0, size.height - textHeight / 2)

        val bottomRightTextWidth = getFontMetrics(font).stringWidth(topRightText)
        if (bottomRightText.isNotEmpty()) g2d.drawString(
            bottomRightText,
            size.width - bottomRightTextWidth,
            size.height - textHeight / 2
        )

        g2d.dispose()
    }
}


class KeyboardPanel(owner: JDialog) : JPanel() {

    init {
        layout = GridBagLayout()
        val pos = GBHelper().apply {
            anchor = GridBagConstraints.FIRST_LINE_START
            weightx = .5
            weighty = .5
            fill = GridBagConstraints.BOTH
        }

        val ii = ImageIcon(BufferedImage(30, 1, BufferedImage.TYPE_INT_ARGB))
        // I Think this 28 came from (first row items width - defaultWidth) my-case:  rows(13) * defaultWidth(2) - defaultWidth(2)
        for (i in 0..28) add(JLabel(ii))


        val defaultWidth = 2
        add(DirectionalButton("`", topLeftText = "~"), pos.nextRow().width(defaultWidth))
        add(DirectionalButton("1", topLeftText = "!"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("2", topLeftText = "@"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("3", topLeftText = "#"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("4", topLeftText = "$"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("5", topLeftText = "%"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("6", topLeftText = "^"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("7", topLeftText = "&"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("8", topLeftText = "*"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("9", topLeftText = "("), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("0", topLeftText = ")"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("-", topLeftText = "‾"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("=", topLeftText = "+"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("←"), pos.nextCol().width(3))

        add(DirectionalButton("Tab ↹"), pos.nextRow().width(3))
        add(DirectionalButton("Q"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("W"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("E"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("R"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("T"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("Y"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("U"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("I"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("O"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("P"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("["), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("]"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("\\"), pos.nextCol().width(defaultWidth))

        add(DirectionalButton("Caps-Lock"), pos.nextRow().width(4))
        add(DirectionalButton("A"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("S"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("D"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("F"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("G"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("H"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("J"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("K"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("L"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton(";"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("'"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("Enter").apply {
            owner.rootPane.defaultButton = this
        }, pos.nextCol().width(3))

        add(DirectionalButton("Shift"), pos.nextRow().width(5))
        add(DirectionalButton("Z"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("X"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("C"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("V"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("B"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("N"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("M"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton(","), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("."), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("/"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("Shift"), pos.nextCol().width(4))


        add(DirectionalButton("Fn"), pos.nextRow().width(defaultWidth))
        add(DirectionalButton("Ctrl"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("Win"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("Alt"), pos.nextCol().width(defaultWidth))

        add(DirectionalButton(""), pos.nextCol().width(14))

        add(DirectionalButton("Alt"), pos.nextCol().width(defaultWidth))
        add(DirectionalButton("⋮"), pos.nextCol().width(3))
        add(DirectionalButton("Ctrl"), pos.nextCol().width(defaultWidth))

    }
}


// https://stackoverflow.com/questions/40398072/singleton-with-parameter-in-kotlin
// https://global-uploads.webflow.com/61603672d19aa0d08107f39c/61dd8da853915830cc3369a6_Tenkeyless-keyboard-form-factor-min.png
class VirtualKeyboardDialog(frame: Frame) : JDialog(frame, "Virtual Keyboard", false) {

    init {
        preferredSize = Dimension(820, 250)
        minimumSize = Dimension(690, 170)
        setPadding(4)
        isAlwaysOnTop = true


        layout = BorderLayout()

        val languages = JComboBox(arrayOf("English", "Arabic"))

        add(KeyboardPanel(this))
        add(languages, BorderLayout.SOUTH)

        pack()
        setLocationRelativeTo(frame)
        componentOrientation = frame.componentOrientation
        isVisible = true
    }
}