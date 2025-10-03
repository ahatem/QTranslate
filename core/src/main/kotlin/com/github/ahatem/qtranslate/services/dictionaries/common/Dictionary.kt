package com.github.ahatem.qtranslate.services.dictionaries.common

import com.github.ahatem.qtranslate.models.Definition


/*
* Online:
*   * Wikipedia
*   * Word Reference
*   * BabyLon Dictionary
*   * Google
*   * Reverso Dictionary
*   * https://developer.oxforddictionaries.com/
*   * https://dictionaryapi.dev/
*   * https://www.twinword.com/api/word-dictionary.php
*   * https://owlbot.info/#
*   * https://www.wordsapi.com/
*   * https://acronyms.thefreedictionary.com/AIV
*   * https://dictionaryapi.com/products/api-medical-dictionary
*   * https://www.wordwebonline.com/search.pl?w=RVFV
* */
interface Dictionary {
    fun searchWord(word: String): List<Definition>
}

