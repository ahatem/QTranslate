package com.github.ahatem.qtranslate.services.text_extractor

import com.github.ahatem.qtranslate.common.UserAgent
import com.github.ahatem.qtranslate.utils.generateRandomHex
import kong.unirest.core.Unirest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO

class GoogleTextExtractor : TextExtractor {

  fun getApiKey() {
    Unirest.get("https://brandfolder.com/workbench/extract-text-from-image")
      .header("cache-control", "max-age=0")
      .header("if-none-match", "W/\"${generateRandomHex(16)}\"")
      .header("user-agent", UserAgent.random())
      .header("upgrade-insecure-requests", "1")
      .header("referrer", "https://www.google.com/")
      .header("referrerPolicy", "origin")
      .header("credentials", "include")
      .asString().body.let {
        println("=".repeat(100))
        val key = Regex("BROWSER_GOOGLE_VISION_API_KEY ='(.*?)';").findAll(it).map { it.groupValues[1] }.toList()[0]
        println("GoogleKey: $key")
      }
  }

  override fun extractText(image: BufferedImage): String {
    val apiKey = "AIzaSyAV-SXt0qiF5aHdn-Zgcl4Gr61_gxx28qs"

    val outputStream = ByteArrayOutputStream()
    ImageIO.write(image, "png", outputStream)
    val base64Image = Base64.getEncoder().encodeToString(outputStream.toByteArray())

    val requestBody = """
        {
          "requests": [
            {
              "image": {
                "content": "$base64Image"
              },
              "features": [
                {
                  "type": "DOCUMENT_TEXT_DETECTION",
                }
              ]
            }
          ]
        }
        """.trimIndent()

    val response = Unirest.post("https://vision.googleapis.com/v1/images:annotate?key=$apiKey")
      .header("accept", "*/*")
      .header("user-agent", UserAgent.random())
      .header("accept-language", "en-US,en;q=0.9")
      .header("content-type", "application/json")
      .header("Referer", "https://brandfolder.com/")
      .body(requestBody)
      .asJson().body.`object`
//    println(response.toString(2))

    runCatching {
      val textAnnotations = response.getJSONArray("responses")
        .getJSONObject(0)
        .getJSONObject("fullTextAnnotation")
      return textAnnotations.getString("text")
    }

    throw Exception("Couldn't extract text by '${this.javaClass.simpleName}'")
  }
}
