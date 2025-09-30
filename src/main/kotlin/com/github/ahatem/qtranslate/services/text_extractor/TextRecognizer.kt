package com.github.ahatem.qtranslate.services.text_extractor

import java.awt.image.BufferedImage

interface TextExtractor {
  fun extractText(image: BufferedImage): String
}




