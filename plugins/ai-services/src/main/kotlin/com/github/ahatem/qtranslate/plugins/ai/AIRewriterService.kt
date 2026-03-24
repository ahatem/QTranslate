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
            RewriteStyle.CONCISE -> "Rewrite as briefly as possible. Remove all filler words, redundancy, and unnecessary detail. Every word must earn its place."
            RewriteStyle.DETAILED -> "Rewrite in an expanded, thorough form. Add relevant context, elaborate on key points, and ensure nothing important is left ambiguous."
            RewriteStyle.SIMPLIFIED -> "Rewrite using plain, simple language. Aim for a reading level suitable for a general audience. Avoid jargon and complex sentence structures."
        }

        val system = """
            You are a professional writing assistant.
            $styleInstruction
            Output ONLY the rewritten text — no preamble, no labels, no quotes.
            Preserve the original meaning faithfully.
            Respond in the SAME LANGUAGE as the input text.
            Preserve paragraph structure and line breaks.
        """.trimIndent()

        return client.complete(system, request.text, jsonMode = false).map { rewritten ->
            RewriteResponse(rewrittenText = rewritten.trim())
        }
    }
}