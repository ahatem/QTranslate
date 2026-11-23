package com.github.ahatem.qtranslate.plugins.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [AiJson.extractJson] against the shapes model output actually takes. Malformed input passes
 * through trimmed so callers fail parsing.
 */
class AiJsonTest {

    private fun assertExtracts(cases: List<Triple<String, String, String>>) {
        for ((label, raw, expected) in cases) {
            assertEquals(expected, AiJson.extractJson(raw), label)
        }
    }

    @Test
    fun `plain JSON passes through, trimmed`() = assertExtracts(
        listOf(
            Triple("object", """{"translation":"Bonjour"}""", """{"translation":"Bonjour"}"""),
            Triple("array", """["a","b"]""", """["a","b"]"""),
            Triple("surrounding whitespace", "  \n  {\"a\":1}  \n", """{"a":1}"""),
            Triple("malformed text", "  not json at all  ", "not json at all"),
            Triple("unbalanced", """{"a":1""", """{"a":1"""),
        )
    )

    @Test
    fun `fenced JSON is unwrapped`() = assertExtracts(
        listOf(
            Triple("json tag", "```json\n{\"a\":1}\n```", """{"a":1}"""),
            Triple("no tag", "```\n{\"a\":1}\n```", """{"a":1}"""),
            Triple("extra whitespace", "  ```json\n\n  {\"a\": 1}\n\n```  ", """{"a": 1}"""),
            Triple("embedded in prose", "Here is the result:\n```json\n{\"a\":1}\n```\nHope that helps!", """{"a":1}"""),
        )
    )

    @Test
    fun `JSON wrapped in prose is extracted`() = assertExtracts(
        listOf(
            Triple("object", """Here is result: {"translation":"Bonjour"} - done.""", """{"translation":"Bonjour"}"""),
            Triple("array", """Corrections: ["teh","recieve"] (2 found)""", """["teh","recieve"]"""),
            Triple(
                "brackets inside strings stay balanced",
                """prefix {"a":{"b":[1,{"c":"} not a close"}]}} suffix""",
                """{"a":{"b":[1,{"c":"} not a close"}]}}"""
            ),
        )
    )
}
