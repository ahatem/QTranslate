package com.github.ahatem.qtranslate.plugins.wikimedia

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal object WiktionaryParser {
    private val ignoredHeadings = setOf(
        "alternative forms", "etymology", "pronunciation", "usage notes", "derived terms",
        "related terms", "descendants", "translations", "references", "further reading",
        "anagrams", "see also", "external links"
    )

    fun parse(html: String): List<WiktionarySenseGroup> {
        val document = Jsoup.parse(html)
        val primarySection = document.selectFirst("section:has(> h2)")
            ?: document.selectFirst("body")
            ?: return emptyList()

        return primarySection.select("section").mapNotNull(::parseSection)
            .ifEmpty { parseFlatSection(primarySection) }
            .distinctBy { it.heading to it.definitions }
            .take(12)
    }

    private fun parseSection(section: Element): WiktionarySenseGroup? {
        val heading = section.children().firstOrNull { it.tagName() == "h3" || it.tagName() == "h4" }
            ?.text()?.trim().orEmpty()
        if (heading.isBlank() || heading.lowercase() in ignoredHeadings) return null
        val definitions = section.children()
            .filter { it.tagName() == "ol" }
            .flatMap { list -> list.children().filter { it.tagName() == "li" } }
            .mapNotNull(::definitionText)
        return definitions.takeIf(List<String>::isNotEmpty)?.let { WiktionarySenseGroup(heading, it.take(12)) }
    }

    private fun parseFlatSection(section: Element): List<WiktionarySenseGroup> {
        val results = mutableListOf<WiktionarySenseGroup>()
        var heading = "definition"
        section.children().forEach { element ->
            when (element.tagName()) {
                "h3", "h4" -> heading = element.text().trim()
                "ol" -> if (heading.lowercase() !in ignoredHeadings) {
                    val definitions = element.children()
                        .filter { it.tagName() == "li" }
                        .mapNotNull(::definitionText)
                        .take(12)
                    if (definitions.isNotEmpty()) results += WiktionarySenseGroup(heading, definitions)
                }
            }
        }
        return results
    }

    private fun definitionText(item: Element): String? {
        val copy = item.clone()
        copy.select("ul, ol, dl, table, style, script, sup.reference").remove()
        return copy.text().trim().replace(Regex("\\s+"), " ").takeIf(String::isNotBlank)
    }
}
