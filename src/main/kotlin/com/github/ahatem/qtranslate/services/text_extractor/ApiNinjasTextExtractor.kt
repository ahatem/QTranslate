package com.github.ahatem.qtranslate.services.text_extractor

import kong.unirest.core.Unirest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.imageio.ImageIO

class ApiNinjasTextExtractor : TextExtractor {
  override fun extractText(image: BufferedImage): String {
    val apiKey = "ZS2hnmsFlHFX6KHY2kBXsQ==IeS5TibCHbg8CrnA"
    val apiEndpoint = "https://api.api-ninjas.com/v1/imagetotext"

    val outputStream = ByteArrayOutputStream()
    ImageIO.write(image, "png", outputStream)

    val file = File("output.png")
    val fileOutputStream = FileOutputStream(file)
    fileOutputStream.write(outputStream.toByteArray())
    fileOutputStream.close()

    runCatching {
      val response = Unirest.post(apiEndpoint)
        .header("X-Api-Key", apiKey)
        .field("image", file, "image/png")
        .asJson().body.array

      val words = mutableListOf<String>()

      for (i in 0 until response.length()) {
        val word = response.getJSONObject(i).getString("text")
        words.add(word)
      }

      return words.joinToString(separator = " ")
    }

    throw Exception("Couldn't extract text by '${this.javaClass.simpleName}'")
  }
}
