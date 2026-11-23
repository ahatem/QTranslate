package com.github.ahatem.qtranslate.core.workspace.lookup

import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionary
import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.Definition
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.DictionaryResponse
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.MutableStateFlow

/** Fakes shared by the lookup use-case tests. */
internal fun managerWith(dictionary: Dictionary) = ActiveServiceManager(
    activeServices = MutableStateFlow(mapOf("fake:default:dictionary" to dictionary)),
    configuration = MutableStateFlow(Configuration.DEFAULT)
)

/** A monolingual dictionary that records the plain requests it receives. */
internal open class RecordingDictionary : Dictionary {
    override val key = "dictionary"
    override val name = "Fake"
    override val version = "1.0.0"
    override val supportedLanguages = SupportedLanguages.All
    val plainRequests = mutableListOf<DictionaryRequest>()

    override suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError> {
        plainRequests += request
        return Ok(responseFor(request.word))
    }

    protected open fun responseFor(word: String): DictionaryResponse =
        DictionaryResponse(listOf(DictionaryEntry(word, "noun", listOf(Definition("a grain")))))
}

/** Also records bilingual requests. Only words in [defineWords] get a definition, or all when it is null. */
internal class RecordingBilingualDictionary(
    private val defineWords: Set<String>? = null
) : RecordingDictionary(), BilingualDictionary {
    val bilingualRequests = mutableListOf<BilingualDictionaryRequest>()

    override suspend fun lookupBilingual(
        request: BilingualDictionaryRequest
    ): Result<DictionaryResponse, ServiceError> {
        bilingualRequests += request
        val defined = defineWords == null || request.word in defineWords
        return Ok(if (defined) responseFor(request.word) else DictionaryResponse(emptyList()))
    }
}
