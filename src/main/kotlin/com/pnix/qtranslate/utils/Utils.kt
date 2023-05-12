package com.pnix.qtranslate.utils

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

fun isRTL(s: String): Boolean {
  for (element in s) {
    val d = Character.getDirectionality(element)
    if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
      return true
    }
  }
  return false
}


fun String.copyToClipboard() {
  val toolkit = Toolkit.getDefaultToolkit()
  val clipboard = toolkit.systemClipboard

  val selection = StringSelection(this)
  clipboard.setContents(selection, null)
}
