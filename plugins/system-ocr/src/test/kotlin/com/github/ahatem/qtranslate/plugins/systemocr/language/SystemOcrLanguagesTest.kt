package com.github.ahatem.qtranslate.plugins.systemocr.language

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemOcrLanguagesTest {

    @Test
    fun `maps tesseract traineddata codes to bcp47`() {
        assertEquals(LanguageCode.ENGLISH, SystemOcrLanguages.fromTesseract("eng"))
        assertEquals(LanguageCode.GERMAN, SystemOcrLanguages.fromTesseract("deu"))
        assertEquals(LanguageCode.FRENCH, SystemOcrLanguages.fromTesseract("fra"))
        assertEquals(LanguageCode.CHINESE_SIMPLIFIED, SystemOcrLanguages.fromTesseract("chi_sim"))
        assertEquals(LanguageCode.CHINESE_TRADITIONAL, SystemOcrLanguages.fromTesseract("chi_tra"))
    }

    @Test
    fun `is case insensitive`() {
        assertEquals(LanguageCode.ENGLISH, SystemOcrLanguages.fromTesseract("ENG"))
    }

    @Test
    fun `ignores models that are not languages`() {
        assertNull(SystemOcrLanguages.fromTesseract("osd"))
        assertNull(SystemOcrLanguages.fromTesseract("equ"))
        assertNull(SystemOcrLanguages.fromTesseract("script/Latin"))
    }

    @Test
    fun `passes bcp47 tags through for the windows and vision engines`() {
        assertEquals(LanguageCode("en-US"), SystemOcrLanguages.fromBcp47("en-US"))
        assertEquals(LanguageCode("zh-Hans-CN"), SystemOcrLanguages.fromBcp47("zh-Hans-CN"))
        assertNull(SystemOcrLanguages.fromBcp47("not a tag"))
    }

    @Test
    fun `maps bcp47 to tesseract codes`() {
        assertEquals("eng", SystemOcrLanguages.toTesseract(LanguageCode.ENGLISH))
        assertEquals("deu", SystemOcrLanguages.toTesseract(LanguageCode.GERMAN))
        assertEquals("chi_sim", SystemOcrLanguages.toTesseract(LanguageCode.CHINESE_SIMPLIFIED))
        assertEquals("chi_tra", SystemOcrLanguages.toTesseract(LanguageCode.CHINESE_TRADITIONAL))
    }

    @Test
    fun `resolves a regional tag to its base language`() {
        assertEquals("eng", SystemOcrLanguages.toTesseract(LanguageCode("en-US")))
        assertEquals("deu", SystemOcrLanguages.toTesseract(LanguageCode("de-DE")))
    }

    @Test
    fun `has no tesseract mapping for an unknown language`() {
        assertNull(SystemOcrLanguages.toTesseract(LanguageCode("xyz")))
    }
}
