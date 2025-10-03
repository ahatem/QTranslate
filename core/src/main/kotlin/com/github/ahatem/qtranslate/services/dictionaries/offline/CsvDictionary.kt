package com.github.ahatem.qtranslate.services.dictionaries.offline

import com.github.ahatem.qtranslate.models.Definition
import com.github.ahatem.qtranslate.services.dictionaries.common.Dictionary
import java.io.File

class CsvDictionary(file: File) : Dictionary {
    override fun searchWord(word: String): List<Definition> {
        TODO("Not yet implemented")
    }
}