package com.pnix.qtranslate.services.text_extractor

import kong.unirest.Unirest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO

class GoogleTextExtractor : TextExtractor {
    override fun extractText(image: BufferedImage): String {
        val apiKey = "AIzaSyABaAbadFNsPWa3O9mjgo6I8sGKv8J7QmU"

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
            .header("accept-language", "en-US,en;q=0.9")
            .header("content-type", "application/json")
            .header("sec-ch-ua", "\"Chromium\";v=\"112\", \"Microsoft Edge\";v=\"112\", \"Not:A-Brand\";v=\"99\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "cross-site")
            .header("sec-gpc", "1")
            .header("Referer", "https://brandfolder.com/")
            .body(requestBody)
            .asJson().body.`object`

        runCatching {
            val textAnnotations = response.getJSONArray("responses")
                .getJSONObject(0)
                .getJSONObject("fullTextAnnotation")
            return textAnnotations.getString("text")
        }

        throw Exception("Couldn't extract text by '${this.javaClass.simpleName}'")
    }
}
