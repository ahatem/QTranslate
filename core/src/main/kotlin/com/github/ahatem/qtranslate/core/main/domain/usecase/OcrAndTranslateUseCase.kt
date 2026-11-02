package com.github.ahatem.qtranslate.core.main.domain.usecase

/**
 * Compatibility name for the OCR extraction use case.
 *
 * OCR now has more than one consumer (capture workflows and the OCR Workspace), so the
 * implementation lives in `core.ocr`. Existing construction sites and tests can keep using the
 * historical name until they are touched.
 */
typealias OcrAndTranslateUseCase = com.github.ahatem.qtranslate.core.ocr.OcrExtractionUseCase
