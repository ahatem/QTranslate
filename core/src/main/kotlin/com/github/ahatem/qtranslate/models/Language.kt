package com.github.ahatem.qtranslate.models

import com.google.gson.Gson

private data class IsoLanguage(val name: String, val alpha2: String, val alpha3: String)

data class Language(private val language: String) {
    companion object {
        private val isoLanguages by lazy {
            val inputStream = IsoLanguage::class.java.classLoader.getResourceAsStream("data/iso-codes.json")
            val json = inputStream?.bufferedReader().use { it?.readText() ?: "" }
            Gson().fromJson(json, Array<IsoLanguage>::class.java)
                .sortedBy { it.name }
        }

        fun listAllLanguages() = isoLanguages.map { Language(it.alpha3) }
    }

    private val isoLanguage: IsoLanguage?

    init {
        val languageLower = language.lowercase()
        val matchingIsoLanguage = isoLanguages.find { it.alpha2 == languageLower || it.alpha3 == languageLower }
        isoLanguage = matchingIsoLanguage
    }

    val name = isoLanguage?.name ?: language
    val alpha2 = isoLanguage?.alpha2 ?: "auto"
    val alpha3 = isoLanguage?.alpha3 ?: "auto"
    val id = isoLanguage?.alpha3 ?: "auto"


    override fun toString(): String {
        return name
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Language

        if (name != other.name) return false
        if (alpha2 != other.alpha2) return false
        if (alpha3 != other.alpha3) return false

        return id == other.id
    }

    override fun hashCode(): Int {
        var result = alpha2.hashCode()
        result = 31 * result + alpha3.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + name.hashCode()
        return result
    }

}
