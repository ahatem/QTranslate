package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.tts.AudioFormat
import com.github.ahatem.qtranslate.api.tts.Gender
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.api.tts.TTSRequest
import com.github.ahatem.qtranslate.api.tts.TTSResponse
import com.github.ahatem.qtranslate.api.tts.TextToSpeech
import com.github.ahatem.qtranslate.api.tts.Voice
import com.github.ahatem.qtranslate.api.tts.VoiceSupport
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.getOrElse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class VoiceInfo(
    val locale: String,
    val gender: String,
    val shortName: String
)

class BingTTSService(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val authManager: BingAuthManager,
    private val languageMapper: BingLanguageMapper,
    private val apiConfig: ApiConfig
) : TextToSpeech, VoiceSupport {

    override val id: String = "bing-tts"
    override val name: String = "Bing TTS"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/bing-translate-icon.svg"

    override val supportedLanguages: SupportedLanguages
        get() = SupportedLanguages.Specific(languageMapper.ttsLanguageCodes.toSet())

    companion object {
        private const val TTS_URL = "https://www.bing.com/tfettts"
        private const val MAX_CHUNK_LENGTH = 500
    }

    private val defaultVoiceMap: Map<String, VoiceInfo> = mapOf(
        "af" to VoiceInfo("af-ZA", "Female", "af-ZA-AdriNeural"),
        "am" to VoiceInfo("am-ET", "Female", "am-ET-MekdesNeural"),
        "ar" to VoiceInfo("ar-SA", "Male", "ar-SA-HamedNeural"),
        "ary" to VoiceInfo("ar-MA", "Female", "ar-MA-MounaNeural"),
        "arz" to VoiceInfo("ar-EG", "Male", "ar-EG-ShakirNeural"),
        "ast" to VoiceInfo("es-ES", "Female", "es-ES-ElviraNeural"),
        "be" to VoiceInfo("ru-RU", "Female", "ru-RU-SvetlanaNeural"),
        "bg" to VoiceInfo("bg-BG", "Male", "bg-BG-BorislavNeural"),
        "bn" to VoiceInfo("bn-IN", "Female", "bn-IN-TanishaaNeural"),
        "ca" to VoiceInfo("ca-ES", "Female", "ca-ES-JoanaNeural"),
        "cs" to VoiceInfo("cs-CZ", "Male", "cs-CZ-AntoninNeural"),
        "cy" to VoiceInfo("cy-GB", "Female", "cy-GB-NiaNeural"),
        "da" to VoiceInfo("da-DK", "Female", "da-DK-ChristelNeural"),
        "de" to VoiceInfo("de-DE", "Female", "de-DE-KatjaNeural"),
        "el" to VoiceInfo("el-GR", "Male", "el-GR-NestorasNeural"),
        "en" to VoiceInfo("en-US", "Female", "en-US-AriaNeural"),
        "en-GB" to VoiceInfo("en-GB", "Female", "en-GB-SoniaNeural"),
        "es" to VoiceInfo("es-ES", "Female", "es-ES-ElviraNeural"),
        "et" to VoiceInfo("et-EE", "Female", "et-EE-AnuNeural"),
        "fa" to VoiceInfo("fa-IR", "Female", "fa-IR-DilaraNeural"),
        "fi" to VoiceInfo("fi-FI", "Female", "fi-FI-NooraNeural"),
        "fr" to VoiceInfo("fr-FR", "Female", "fr-FR-DeniseNeural"),
        "fr-CA" to VoiceInfo("fr-CA", "Female", "fr-CA-SylvieNeural"),
        "ga" to VoiceInfo("ga-IE", "Female", "ga-IE-OrlaNeural"),
        "gu" to VoiceInfo("gu-IN", "Female", "gu-IN-DhwaniNeural"),
        "he" to VoiceInfo("he-IL", "Male", "he-IL-AvriNeural"),
        "hi" to VoiceInfo("hi-IN", "Female", "hi-IN-SwaraNeural"),
        "hr" to VoiceInfo("hr-HR", "Male", "hr-HR-SreckoNeural"),
        "hu" to VoiceInfo("hu-HU", "Male", "hu-HU-TamasNeural"),
        "id" to VoiceInfo("id-ID", "Male", "id-ID-ArdiNeural"),
        "is" to VoiceInfo("is-IS", "Female", "is-IS-GudrunNeural"),
        "it" to VoiceInfo("it-IT", "Male", "it-IT-DiegoNeural"),
        "iu" to VoiceInfo("iu-CANS-CA", "Female", "iu-Cans-CA-SiqiniqNeural"),
        "iu-Latn" to VoiceInfo("iu-LATN-CA", "Female", "iu-Latn-CA-SiqiniqNeural"),
        "ja" to VoiceInfo("ja-JP", "Female", "ja-JP-NanamiNeural"),
        "jav" to VoiceInfo("jv-ID", "Female", "jv-ID-SitiNeural"),
        "kk" to VoiceInfo("kk-KZ", "Female", "kk-KZ-AigulNeural"),
        "km" to VoiceInfo("km-KH", "Female", "km-KH-SreymomNeural"),
        "kn" to VoiceInfo("kn-IN", "Female", "kn-IN-SapnaNeural"),
        "ko" to VoiceInfo("ko-KR", "Female", "ko-KR-SunHiNeural"),
        "lo" to VoiceInfo("lo-LA", "Female", "lo-LA-KeomanyNeural"),
        "lt" to VoiceInfo("lt-LT", "Female", "lt-LT-OnaNeural"),
        "lv" to VoiceInfo("lv-LV", "Female", "lv-LV-EveritaNeural"),
        "mk" to VoiceInfo("mk-MK", "Female", "mk-MK-MarijaNeural"),
        "ml" to VoiceInfo("ml-IN", "Female", "ml-IN-SobhanaNeural"),
        "mr" to VoiceInfo("mr-IN", "Female", "mr-IN-AarohiNeural"),
        "ms" to VoiceInfo("ms-MY", "Male", "ms-MY-OsmanNeural"),
        "mt" to VoiceInfo("mt-MT", "Female", "mt-MT-GraceNeural"),
        "my" to VoiceInfo("my-MM", "Female", "my-MM-NilarNeural"),
        "nb" to VoiceInfo("nb-NO", "Female", "nb-NO-PernilleNeural"),
        "nl" to VoiceInfo("nl-NL", "Female", "nl-NL-ColetteNeural"),
        "pl" to VoiceInfo("pl-PL", "Female", "pl-PL-ZofiaNeural"),
        "ps" to VoiceInfo("ps-AF", "Female", "ps-AF-LatifaNeural"),
        "pt" to VoiceInfo("pt-BR", "Female", "pt-BR-FranciscaNeural"),
        "pt-PT" to VoiceInfo("pt-PT", "Female", "pt-PT-FernandaNeural"),
        "ro" to VoiceInfo("ro-RO", "Male", "ro-RO-EmilNeural"),
        "ru" to VoiceInfo("ru-RU", "Female", "ru-RU-DariyaNeural"),
        "sk" to VoiceInfo("sk-SK", "Male", "sk-SK-LukasNeural"),
        "sl" to VoiceInfo("sl-SI", "Male", "sl-SI-RokNeural"),
        "sr-Cyrl" to VoiceInfo("sr-RS", "Female", "sr-RS-SophieNeural"),
        "su" to VoiceInfo("su-ID", "Female", "su-ID-TutiNeural"),
        "sv" to VoiceInfo("sv-SE", "Female", "sv-SE-SofieNeural"),
        "ta" to VoiceInfo("ta-IN", "Female", "ta-IN-PallaviNeural"),
        "te" to VoiceInfo("te-IN", "Male", "te-IN-ShrutiNeural"),
        "th" to VoiceInfo("th-TH", "Male", "th-TH-NiwatNeural"),
        "tr" to VoiceInfo("tr-TR", "Female", "tr-TR-EmelNeural"),
        "uk" to VoiceInfo("uk-UA", "Female", "uk-UA-PolinaNeural"),
        "ur" to VoiceInfo("ur-IN", "Female", "ur-IN-GulNeural"),
        "uz" to VoiceInfo("uz-UZ", "Female", "uz-UZ-MadinaNeural"),
        "vi" to VoiceInfo("vi-VN", "Male", "vi-VN-NamMinhNeural"),
        "yue" to VoiceInfo("zh-HK", "Female", "zh-HK-HiuGaaiNeural"),
        "zh-Hans" to VoiceInfo("zh-CN", "Female", "zh-CN-XiaoxiaoNeural"),
        "zh-Hant" to VoiceInfo("zh-CN", "Female", "zh-CN-XiaoxiaoNeural")
    )

    private val voiceInfoByName: Map<String, VoiceInfo> = defaultVoiceMap.values.associateBy { it.shortName }

    override val voices: List<Voice> = defaultVoiceMap.map { (langTag, info) ->
        val lang = LanguageCode(langTag)
        val gender = when (info.gender.lowercase()) {
            "female" -> Gender.FEMALE
            "male" -> Gender.MALE
            else -> null
        }
        val voiceName = info.shortName.substringAfterLast('-').replace("Neural", "")
        Voice(
            id = info.shortName,
            name = voiceName,
            language = lang,
            gender = gender
        )
    }

    override suspend fun synthesize(request: TTSRequest): Result<TTSResponse, ServiceError> =
        coroutineScope {
            // TTSRequest is now a sealed interface — we handle both variants explicitly.
            // ByLanguage: caller picked a language, we resolve the default voice for it.
            // ByVoice: caller picked a specific voice from VoiceSupport.voices.
            when (request) {
                is TTSRequest.ByLanguage -> {
                    if (request.text.isBlank()) {
                        Ok(TTSResponse(TTSAudio.Bytes(ByteArray(0), AudioFormat.MP3)))
                    } else {
                        val defaultVoice = resolveDefaultVoice(request.language)
                        synthesizeWithChunking(request.text, defaultVoice, request.speed)
                    }
                }
                is TTSRequest.ByVoice -> {
                    if (request.text.isBlank()) {
                        Ok(TTSResponse(TTSAudio.Bytes(ByteArray(0), AudioFormat.MP3)))
                    } else {
                        synthesizeWithChunking(request.text, request.voice, request.speed)
                    }
                }
            }
        }

    private suspend fun synthesizeWithChunking(
        text: String,
        voice: Voice,
        speed: Float
    ): Result<TTSResponse, ServiceError> =
        coroutineBinding {
            val auth = authManager.getAuth().bind()
            val voiceInfo = selectVoiceInfo(voice)
            val locale = voiceInfo.locale
            val shortName = voice.id.ifEmpty { voiceInfo.shortName }
            val gender = voiceInfo.gender
            val rate = String.format("%+.2f%%", (speed - 1f) * 100)
            val chunks = partitionText(text)

            val audioChunks = chunks
                .map { chunk ->
                    async {
                        synthesizeChunk(
                            text = chunk,
                            locale = locale,
                            voiceName = shortName,
                            gender = gender,
                            rate = rate,
                            auth = auth
                        )
                    }
                }
                .awaitAll()

            val audioData =
                if (audioChunks.isEmpty()) ByteArray(0)
                else audioChunks.reduce { acc, bytes -> acc + bytes }

            TTSResponse(audio = TTSAudio.Bytes(audioData, AudioFormat.MP3))
        }

    private fun resolveDefaultVoice(language: LanguageCode): Voice {
        val providerCode = languageMapper.toProviderCode(language)
        val info = defaultVoiceMap[providerCode] ?: defaultVoiceMap["en"]!!
        return voices.firstOrNull { it.id == info.shortName } ?: voices.first { it.language.tag == "en" }
    }

    private fun selectVoiceInfo(voice: Voice): VoiceInfo {
        return voiceInfoByName[voice.id] ?: run {
            val providerCode = languageMapper.toProviderCode(voice.language)
            defaultVoiceMap[providerCode] ?: defaultVoiceMap["en"]!!
        }
    }

    private suspend fun synthesizeChunk(
        text: String,
        locale: String,
        voiceName: String,
        gender: String,
        rate: String,
        auth: BingAuth
    ): Result<ByteArray, ServiceError> = coroutineBinding {
        val escapedText = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val ssml = """
            <speak version='1.0' xml:lang='$locale'>
                <voice xml:lang='$locale' xml:gender='$gender' name='$voiceName'>
                    <prosody rate='$rate'>$escapedText</prosody>
                </voice>
            </speak>
        """.trimIndent()

        val formData = mapOf(
            "ssml" to ssml,
            "token" to auth.token,
            "key" to auth.key
        )

        httpClient.postFormBytes(
            url = TTS_URL,
            headers = apiConfig.createHeaders(),
            formData = formData,
            queryParams = mapOf(
                "isVertical" to 1,
                "IG" to auth.ig,
                "IID" to auth.iid,
            ),
            cookies = mapOf("MUID" to auth.muid)
        ).bind()
    }

    private fun partitionText(text: String): List<String> =
        text.split(Regex("\\s+"))
            .fold(mutableListOf<String>()) { acc, word ->
                val current = acc.lastOrNull() ?: ""
                val next = if (current.isEmpty()) word else "$current $word"
                if (next.length > MAX_CHUNK_LENGTH) {
                    if (word.isNotEmpty()) acc.add(word)
                } else {
                    if (acc.isNotEmpty()) acc[acc.lastIndex] = next
                    else acc.add(next)
                }
                acc
            }
            .filter { it.isNotBlank() }
}