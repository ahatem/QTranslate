package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.rewriter.RewriteRequest
import com.github.ahatem.qtranslate.api.rewriter.RewriteResponse
import com.github.ahatem.qtranslate.api.rewriter.RewriteStyle
import com.github.ahatem.qtranslate.api.rewriter.Rewriter
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map

class AIRewriterService(
    private val client: AIServiceClient,
    private val settings: () -> AISettings
) : Rewriter {

    override val id: String = "ai-rewriter"
    override val name: String get() = "${settings().provider} Rewriter"
    override val version: String = "1.0.0"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    override suspend fun rewrite(request: RewriteRequest): Result<RewriteResponse, ServiceError> {
        val styleInstruction = when (request.style) {
            RewriteStyle.FORMAL -> "Rewrite in a formal, professional tone. Use precise vocabulary and complete sentences. Remove slang and colloquialisms."
            RewriteStyle.CASUAL -> "Rewrite in a natural, conversational tone. Use everyday language as if speaking to a friend."
            RewriteStyle.CONCISE -> "Rewrite as briefly as possible. Remove all filler words, redundancy, and unnecessary detail."
            RewriteStyle.DETAILED -> "Rewrite in an expanded, thorough form. Add relevant context and elaborate on key points."
            RewriteStyle.SIMPLIFIED -> "Rewrite using plain, simple language suitable for a general audience. Avoid jargon."
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

        return client.complete(system, request.text, jsonMode = false).map { rewritten ->
            RewriteResponse(rewrittenText = rewritten.trim().removeSurrounding("\""))
        }
    }
}