package com.github.ahatem.qtranslate.services.dictionaries.common

import com.github.ahatem.qtranslate.services.dictionaries.offline.CsvDictionary
import com.github.ahatem.qtranslate.services.dictionaries.offline.JsonDictionary
import com.github.ahatem.qtranslate.services.dictionaries.offline.KeyValuePairDictionary
import java.io.File

object LocalFileDictionary {

    private val dictionariesFolder = File(File(""), "dictionaries")
    private val allowedExtensions = arrayOf("csv", "json", "txt")

    fun loadLocalDictionaries(): List<Dictionary> {
        if (!dictionariesFolder.exists()) dictionariesFolder.mkdir()

        val allFiles = dictionariesFolder.listFiles() ?: return emptyList()
        val isAllowedFile = { file: File -> file.isFile && file.extension.lowercase() in allowedExtensions }

        return allFiles.filter(isAllowedFile).mapNotNull(::fromFile)
    }

    private fun fromFile(file: File): Dictionary? {
        if (!file.exists() || file.isDirectory || file.extension.isEmpty()) return null

        return when (file.extension) {
            "csv" -> CsvDictionary(file)
            "json" -> JsonDictionary(file)
            "txt" -> KeyValuePairDictionary(file)
            else -> null
        }
    }
}
