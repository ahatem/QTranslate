package com.github.ahatem.qtranslate.plugins.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [AiJson.extractJson] against the shapes model output actually takes:
 * plain JSON, fenced JSON (with or without a language tag), and JSON wrapped
 * in prose. Malformed input passes through trimmed so callers fail parsing.
 */
class AiJsonTest {

    @Test
    fun `plain JSON object passes through unchanged`() {
        val raw = """{"translation":"Bonjour"}"""
        assertEquals(raw, AiJson.extractJson(raw))
    }

    @Test
    fun `plain JSON array passes through unchanged`() {
        val raw = """["a","b"]"""
        assertEquals(raw, AiJson.extractJson(raw))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("""{"a":1}""", AiJson.extractJson("  \n  {\"a\":1}  \n"))
    }

    @Test
    fun `fenced JSON with a json language tag is unwrapped`() {
        val raw = "```json\n{\"a\":1}\n```"
        assertEquals("""{"a":1}""", AiJson.extractJson(raw))
    }

    @Test
    fun `fenced JSON without a language tag is unwrapped`() {
        val raw = "```\n{\"a\":1}\n```"
        assertEquals("""{"a":1}""", AiJson.extractJson(raw))
    }

    @Test
    fun `fenced JSON with extra whitespace is unwrapped and trimmed`() {
        val raw = "  ```json\n\n  {\"a\": 1}\n\n```  "
        assertEquals("""{"a": 1}""", AiJson.extractJson(raw))
    }

    @Test
    fun `fenced JSON embedded in prose is unwrapped`() {
        val raw = "Here is the result:\n```json\n{\"a\":1}\n```\nHope that helps!"
        assertEquals("""{"a":1}""", AiJson.extractJson(raw))
    }

    @Test
    fun `prose-wrapped JSON object is extracted`() {
        val raw = """Here is result: {"translation":"Bonjour"} — done."""
        assertEquals("""{"translation":"Bonjour"}""", AiJson.extractJson(raw))
    }

    @Test
    fun `prose-wrapped JSON array is extracted`() {
        val raw = """Corrections: ["teh","recieve"] (2 found)"""
        assertEquals("""["teh","recieve"]""", AiJson.extractJson(raw))
    }

    @Test
    fun `nested brackets and braces inside strings are balanced correctly`() {
        val raw = """prefix {"a":{"b":[1,{"c":"} not a close"}]}} suffix"""
        assertEquals("""{"a":{"b":[1,{"c":"} not a close"}]}}""", AiJson.extractJson(raw))
    }

    @Test
    fun `malformed text with no JSON passes through trimmed for the caller to fail`() {
        assertEquals("not json at all", AiJson.extractJson("  not json at all  "))
    }

    @Test
    fun `unbalanced JSON passes through trimmed for the caller to fail`() {
        assertEquals("""{"a":1""", AiJson.extractJson("""{"a":1"""))
    }
}
