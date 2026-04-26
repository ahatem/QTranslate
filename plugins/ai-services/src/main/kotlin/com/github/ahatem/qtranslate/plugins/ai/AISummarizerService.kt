package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.summarizer.SummarizeRequest
import com.github.ahatem.qtranslate.api.summarizer.SummarizeResponse
import com.github.ahatem.qtranslate.api.summarizer.Summarizer
import com.github.ahatem.qtranslate.api.summarizer.SummaryLength
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map

class AISummarizerService(
    private val client: AIServiceClient,
    private val settings: () -> AISettings
) : Summarizer {

    override val id: String = "ai-summarizer"
    override val name: String get() = "${settings().provider} Summarizer"
    override val version: String = "1.0.0"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    override suspend fun summarize(request: SummarizeRequest): Result<SummarizeResponse, ServiceError> {
        val lengthInstruction = when (request.length) {
            SummaryLength.SHORT -> "Respond with a single sentence of no more than 30 words."
            SummaryLength.MEDIUM -> "Respond with 2–4 concise sentences that capture the key points."
            SummaryLength.LONG -> "Respond with a detailed multi-paragraph summary that preserves important nuance."
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

        return client.complete(system, request.text, jsonMode = false).map { summary ->
            SummarizeResponse(summary = summary.trim().removeSurrounding("\""))
        }
    }
}