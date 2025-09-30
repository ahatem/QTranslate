package com.pnix.qtranslate.common

import kong.unirest.core.Unirest

object EmailSender {

  private val apiKey = "6e78d68489d2186688cfa6dfa1a51833-db4df449-bc794542"
  private val domain = "sandbox39d5d992ff1149f7ab987344aa877818.mailgun.org"
  private val recipient = "buzz.webra@gmail.com"

  private data class Email(val from: String, val to: String, val subject: String, val text: String)

  fun sendEmail(sender: String, subject: String, text: String): Boolean {
    val url = "https://api.mailgun.net/v3/$domain/messages"

    val email = Email(
      from = sender,
      to = recipient,
      subject = subject,
      text = text
    )

    runCatching {
      val response = Unirest.post(url)
        .basicAuth("api", apiKey)
        .field("from", email.from)
        .field("to", email.to)
        .field("subject", email.subject)
        .field("text", email.text)
        .asString()

      if (response.status == 200) {
        return true
      } else {
        throw RuntimeException("Failed to send email: ${response.body}")
      }
    }

    return false
  }

}