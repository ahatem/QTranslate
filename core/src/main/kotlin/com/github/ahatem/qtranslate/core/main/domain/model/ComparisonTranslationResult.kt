package com.github.ahatem.qtranslate.core.main.domain.model

enum class ComparisonStatus {
    LOADING,
    SUCCESS,
    FAILURE
}

data class ComparisonTranslationResult(
    val serviceId: String,
    val serviceName: String?,
    val status: ComparisonStatus,
    val text: String = "",
    val errorMessage: String? = null
)
