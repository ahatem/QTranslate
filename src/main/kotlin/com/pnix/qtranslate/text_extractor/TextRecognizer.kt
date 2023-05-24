package com.pnix.qtranslate.text_extractor

import java.awt.image.BufferedImage

interface TextExtractor {
  fun extractText(image: BufferedImage): String
}




