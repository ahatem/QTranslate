package com.pnix.qtranslate.playground

import com.pnix.qtranslate.data.translators.google.GoogleTranslator
import com.pnix.qtranslate.data.translators.reverso.ReversoTranslator
import javazoom.jl.player.Player
import java.io.ByteArrayInputStream

fun main() {
  var text = """
    Helo im yoor browther, i want to tell you this is veri amazyng
  """.trimIndent()

  text = """
    Moka your ass is red 
  """.trimIndent()

  /*val textToSpeech = GoogleTranslator().textToSpeech(text, gender = "male", "ar")
  ByteArrayInputStream(textToSpeech.content).use {
    Player(it).apply {
      play()
      close()
    }
  }*/
  /*SpellChecker.spellCheck(text).corrections.forEach {
    println("*- ${it.originalWord}")
    it.suggestions.forEach { suggestion ->
      println(" - $suggestion")
    }
  }*/

}

