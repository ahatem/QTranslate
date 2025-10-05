package com.github.ahatem.qtranslate.plugins.google.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TranslateResponse(
    @SerialName("sentences") val sentences: List<TranslatedSentence> = emptyList(),
    @SerialName("dict") val dictionary: List<DictionaryEntry>? = null,
    @SerialName("src") val sourceLanguage: String = "",
    @SerialName("alternative_translations") val alternatives: List<AlternativeTranslation>? = null,
    @SerialName("confidence") val confidence: Double = 0.0,
    @SerialName("spell") val spell: SpellCorrection? = null,
    @SerialName("synsets") val synsets: List<SynonymSet>? = null,
    @SerialName("ld_result") val languageDetection: LanguageDetection? = null,
    @SerialName("definitions") val definitions: List<WordDefinition>? = null,
    @SerialName("examples") val examples: Examples? = null // add this line
)

@Serializable
data class TranslatedSentence(
    @SerialName("trans") val text: String? = null,
    @SerialName("orig") val original: String? = null,
    @SerialName("translit") val transliteration: String? = null
)

@Serializable
data class DictionaryEntry(
    @SerialName("pos") val pos: String = "",
    @SerialName("terms") val terms: List<String> = emptyList(),
    @SerialName("entry") val entries: List<DictionaryTerm> = emptyList(),
    @SerialName("base_form") val baseForm: String? = null,
    @SerialName("pos_enum") val posEnum: Int? = null
)

@Serializable
data class DictionaryTerm(
    @SerialName("word") val word: String = "",
    @SerialName("reverse_translation") val reverseTranslations: List<String> = emptyList(),
    @SerialName("score") val score: Double? = null
)

@Serializable
data class AlternativeTranslation(
    @SerialName("src_phrase") val sourcePhrase: String = "",
    @SerialName("alternative") val options: List<AlternativeOption> = emptyList(),
    @SerialName("raw_src_segment") val rawSrcSegment: String? = null,
    @SerialName("start_pos") val startPos: Int? = null,
    @SerialName("end_pos") val endPos: Int? = null
)

@Serializable
data class AlternativeOption(
    @SerialName("word_postproc") val text: String = "",
    @SerialName("has_preceding_space") val hasPrecedingSpace: Boolean? = null,
    @SerialName("attach_to_next_token") val attachToNextToken: Boolean? = null
)

@Serializable
data class SpellCorrection(
    @SerialName("spell_html_res") val spellHtmlRes: String? = null,
    @SerialName("spell_res") val correctedText: String = "",
    @SerialName("correction_type") val correctionType: List<Int>? = null
)

@Serializable
data class SynonymSet(
    @SerialName("pos") val pos: String = "",
    @SerialName("entry") val entries: List<SynonymEntry> = emptyList(),
    @SerialName("base_form") val baseForm: String? = null,
    @SerialName("pos_enum") val posEnum: Int? = null
)

@Serializable
data class SynonymEntry(
    @SerialName("synonym") val synonyms: List<String> = emptyList(),
    @SerialName("definition_id") val definitionId: String? = null
)

@Serializable
data class LanguageDetection(
    @SerialName("srclangs") val detectedLanguages: List<String> = emptyList(),
    @SerialName("srclangs_confidences") val confidences: List<Double> = emptyList(),
    @SerialName("extended_srclangs") val extendedLanguages: List<String> = emptyList()
)

@Serializable
data class WordDefinition(
    @SerialName("pos") val pos: String = "",
    @SerialName("entry") val entries: List<DefinitionEntry> = emptyList(),
    @SerialName("base_form") val baseForm: String? = null,
    @SerialName("pos_enum") val posEnum: Int? = null
)

@Serializable
data class DefinitionEntry(
    @SerialName("gloss") val gloss: String = "",
    @SerialName("example") val example: String? = null,
    @SerialName("definition_id") val definitionId: String? = null,
    @SerialName("label_info") val labelInfo: LabelInfo? = null
)

@Serializable
data class LabelInfo(
    @SerialName("register") val register: List<String>? = null
)

@Serializable
data class Examples(
    @SerialName("example") val example: List<ExampleItem> = emptyList()
)

@Serializable
data class ExampleItem(
    @SerialName("text") val text: String = "",
    @SerialName("definition_id") val definitionId: String? = null
)

@Serializable
data class OfficialTranslateResponse(
    @SerialName("data") val data: TranslateData
)

@Serializable
data class TranslateData(
    @SerialName("translations") val translations: List<Translation>
)

@Serializable
data class Translation(
    @SerialName("translatedText") val translatedText: String,
    @SerialName("detectedSourceLanguage") val detectedSourceLanguage: String? = null
)

@Serializable
data class VisionResponse(
    @SerialName("responses") val responses: List<VisionAnnotation>
)

@Serializable
data class VisionAnnotation(
    @SerialName("fullTextAnnotation") val fullTextAnnotation: FullTextAnnotation? = null,
    @SerialName("error") val error: VisionError? = null
)

@Serializable
data class FullTextAnnotation(
    @SerialName("text") val text: String = "",
    @SerialName("pages") val pages: List<Page>? = null
)

@Serializable
data class Page(
    @SerialName("property") val property: PageProperty? = null
)

@Serializable
data class PageProperty(
    @SerialName("detectedLanguages") val detectedLanguages: List<DetectedLanguage>? = null
)

@Serializable
data class DetectedLanguage(
    @SerialName("languageCode") val languageCode: String
)

@Serializable
data class VisionError(
    @SerialName("message") val message: String
)

@Serializable
data class VisionRequest(
    @SerialName("requests") val requests: List<VisionImageRequest>
)

@Serializable
data class VisionImageRequest(
    @SerialName("image") val image: VisionImage,
    @SerialName("features") val features: List<VisionFeature>,
    @SerialName("imageContext") val imageContext: VisionImageContext? = null
)

@Serializable
data class VisionImage(
    @SerialName("content") val content: String
)

@Serializable
data class VisionFeature(
    @SerialName("type") val type: String
)

@Serializable
data class VisionImageContext(
    @SerialName("languageHints") val languageHints: List<String>
)