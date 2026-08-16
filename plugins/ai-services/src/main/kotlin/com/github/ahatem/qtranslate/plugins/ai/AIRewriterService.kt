package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.rewriter.RewriteRequest
import com.github.ahatem.qtranslate.api.rewriter.RewriteResponse
import com.github.ahatem.qtranslate.api.plugin.StandardOptions
import com.github.ahatem.qtranslate.api.rewriter.Rewriter
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map

class AIRewriterService(
    private val client: AIServiceClient
) : Rewriter {


    // Nothing this plugin offers works until a key is set, which is what makes a connection
    // test worth offering here. The check itself is shared: one key, one endpoint, one model.
    override val metadata = ServiceMetadata(requiresConfiguration = true)

    override suspend fun validate() = client.validate()

    override val key: String = "ai-rewriter"
    override val name: String = "AI Rewriter"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/ai-icon.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    /**
     * The standard styles, so the labels come out translated in every language the app ships.
     * A service wanting its own vocabulary would build a [com.github.ahatem.qtranslate.api.plugin.ServiceOption]
     * here instead and supply the labels in its own bundle.
     */
    override val options = listOf(StandardOptions.REWRITE_STYLE)

    override suspend fun rewrite(request: RewriteRequest): Result<RewriteResponse, ServiceError> {
        // An id, not an enum: the host passes through whatever the user picked, so an unknown
        // value is possible in principle and falls back to the option's own default.
        val styleInstruction = when (request.style ?: StandardOptions.REWRITE_STYLE.defaultValue) {
            "CASUAL" -> "Rewrite in a natural, conversational tone. Use everyday language as if speaking to a friend."
            "CONCISE" -> "Rewrite as briefly as possible. Remove all filler words, redundancy, and unnecessary detail."
            "DETAILED" -> "Rewrite in an expanded, thorough form. Add relevant context and elaborate on key points."
            "SIMPLIFIED" -> "Rewrite using plain, simple language suitable for a general audience. Avoid jargon."
            else -> "Rewrite in a formal, professional tone. Use precise vocabulary and complete sentences. Remove slang and colloquialisms."
        }

        val system = """
        You are a professional writing assistant.
        
        TASK:
        $styleInstruction
        
        RULES:
        1. Respond in the EXACT SAME LANGUAGE as the source text.
        2. Output ONLY the rewritten text. 
        3. Do NOT include any preamble, labels, or introductory phrases (e.g., do not say "Here is the formal version:").
        4. Preserve original paragraph structure and line breaks.
        
        The text to rewrite begins after the '---' delimiter below.
        ---
    """.trimIndent()

        return client.complete(system, request.text).map { rewritten ->
            RewriteResponse(rewrittenText = rewritten.trim().removeSurrounding("\""))
        }
    }
}
