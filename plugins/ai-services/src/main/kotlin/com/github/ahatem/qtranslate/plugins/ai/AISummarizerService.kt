package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.ServiceCapability
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.summarizer.SummarizeRequest
import com.github.ahatem.qtranslate.api.summarizer.SummarizeResponse
import com.github.ahatem.qtranslate.api.summarizer.Summarizer
import com.github.ahatem.qtranslate.api.plugin.StandardOptions
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map

class AISummarizerService(
    private val client: AIServiceClient
) : Summarizer {

    override val capabilities = setOf(ServiceCapability.SUMMARIZER)

    // Nothing this plugin offers works until a key is set, which is what makes a connection
    // test worth offering here. The check itself is shared: one key, one endpoint, one model.
    override val metadata = ServiceMetadata(requiresConfiguration = true)

    override suspend fun validate() = client.validate()

    override val key: String = "ai-summarizer"
    override val name: String = "AI Summarizer"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/ai-icon.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    /**
     * The standard lengths, so the labels come out translated in every language the app ships.
     * A service wanting its own vocabulary would build a [com.github.ahatem.qtranslate.api.plugin.ServiceOption]
     * here instead and supply the labels in its own bundle.
     */
    override val options = listOf(StandardOptions.SUMMARY_LENGTH)

    override suspend fun summarize(request: SummarizeRequest): Result<SummarizeResponse, ServiceError> {
        // An id, not an enum: the host passes through whatever the user picked, so an unknown
        // value is possible in principle and falls back to the option's own default.
        val lengthInstruction = when (request.length ?: StandardOptions.SUMMARY_LENGTH.defaultValue) {
            "SHORT" -> "Respond with a single sentence of no more than 30 words."
            "LONG" -> "Respond with a detailed multi-paragraph summary that preserves important nuance."
            else -> "Respond with 2–4 concise sentences that capture the key points."
        }

        val system = """
        You are a professional editor. 
        
        TASK:
        Summarize the text provided by the user. 
        The summary MUST be written in the same language as the source text itself.
        
        CONSTRAINTS:
        - $lengthInstruction
        - Output ONLY the summarized text.
        - Do not include any introductory text, labels, quotes, or conversational filler (e.g., do NOT say "Here is a summary").
        
        The user's text to be summarized starts after the '---' delimiter below.
        ---
    """.trimIndent()

        return client.complete(system, request.text).map { summary ->
            SummarizeResponse(summary = summary.trim().removeSurrounding("\""))
        }
    }
}
