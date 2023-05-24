package com.pnix.qtranslate.services.text_extractor

import kong.unirest.Unirest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO

class OcrSpaceTextExtractor : TextExtractor {
    override fun extractText(image: BufferedImage): String {
        val apiKey = "d47031f85088957"

        val outputStream = ByteArrayOutputStream()
        val contentType = "image/png"

        ImageIO.write(image, "png", outputStream)
        val imageBytes = outputStream.toByteArray()

        val base64Image = Base64.getEncoder().encodeToString(imageBytes)
        val dataUri = "data:$contentType;base64,$base64Image"

        runCatching {
            val response = Unirest.post("https://api.ocr.space/parse/image")
                .header("apikey", apiKey)
                .field("base64Image", dataUri)
                .asJson().body.`object`
            val textAnnotations = response.getJSONArray("ParsedResults").getJSONObject(0)
            return textAnnotations.getString("ParsedText")
        }

        throw Exception("Couldn't extract text by '${this.javaClass.simpleName}'")
    }
}
