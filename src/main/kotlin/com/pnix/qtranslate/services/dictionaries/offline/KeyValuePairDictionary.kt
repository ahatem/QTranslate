package com.pnix.qtranslate.services.dictionaries.offline

import com.pnix.qtranslate.models.Definition
import com.pnix.qtranslate.services.dictionaries.common.Dictionary
import java.io.File

// ? Key-Value Pair Dictionary car=a four-wheeled vehicle that is used for transportation
class KeyValuePairDictionary(file: File) : Dictionary {
  override fun searchWord(word: String): List<Definition> {
    TODO("Not yet implemented")
  }
}