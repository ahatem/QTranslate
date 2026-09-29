package com.github.ahatem.qtranslate.plugins.systemservices.tts

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceLocaleMapperTest {
    @Test fun `region tags map to both regional and base language`() {
        assertTrue(VoiceLocaleMapper.codes("en-US").containsAll(setOf(LanguageCode("en-US"), LanguageCode.ENGLISH)))
        assertTrue(VoiceLocaleMapper.codes("ar_EG").containsAll(setOf(LanguageCode("ar-EG"), LanguageCode.ARABIC)))
        assertTrue(VoiceLocaleMapper.codes("zh-CN").contains(LanguageCode.CHINESE_SIMPLIFIED))
        assertTrue(VoiceLocaleMapper.codes("es_419").contains(LanguageCode("es-419")))
        assertTrue(VoiceLocaleMapper.codes("ar_001").contains(LanguageCode("ar-001")))
    }

    @Test fun `unknown locale does not produce a voice language`() {
        assertTrue(VoiceLocaleMapper.codes("???").isEmpty())
    }

    @Test fun `voice preference is deterministic and respects regional match`() {
        val voices = listOf(SystemVoice("us", "US", "en-US"), SystemVoice("gb", "GB", "en-GB"))
        assertEquals("gb", VoiceLocaleMapper.preferred(LanguageCode("en-GB"), voices)?.id)
        assertEquals("us", VoiceLocaleMapper.preferred(LanguageCode.ENGLISH, voices)?.id)
        assertNull(VoiceLocaleMapper.preferred(LanguageCode.AUTO, voices))
    }
}
