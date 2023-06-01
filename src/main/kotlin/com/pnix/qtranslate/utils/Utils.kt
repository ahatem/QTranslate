package com.pnix.qtranslate.utils

import com.pnix.qtranslate.common.Localizer
import com.pnix.qtranslate.services.translators.abstraction.TranslatorService
import com.pnix.qtranslate.services.translators.bing.BingTranslator
import com.pnix.qtranslate.services.translators.google.GoogleTranslator
import com.pnix.qtranslate.services.translators.reverso.ReversoTranslator
import com.pnix.qtranslate.services.translators.yandex.YandexTranslator
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.random.Random

fun String.isRTL(): Boolean {
  for (element in this) {
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

fun generateRandomHex(length: Int): String {
  val byteArray = ByteArray(length / 2)
  Random.nextBytes(byteArray)
  return byteArray.joinToString("") { byte -> "%02x".format(byte) }
}

val TranslatorService.localizedName get() = Localizer.localize("service_${this.serviceName.lowercase()}_translate")

val supportedTranslators by lazy {
  listOf(
    GoogleTranslator(), BingTranslator(), YandexTranslator(), ReversoTranslator(),
  )
}
