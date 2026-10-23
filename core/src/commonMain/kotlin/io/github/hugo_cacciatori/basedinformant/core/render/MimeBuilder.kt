package io.github.hugo_cacciatori.basedinformant.core.render

import kotlin.io.encoding.Base64

/**
 * A basic `multipart/alternative` message (rendering.md section 2): UTF-8, RFC 2047 subject, base64 parts.
 * The HTML part is the very string given, never regenerated. Milestone 0.5.0 adds the Gmail encoding.
 */
object MimeBuilder {
    fun build(from: String, to: String, rendered: RenderedDigest, date: String, boundary: String = "bi-digest-boundary"): String =
        buildString {
            append("From: ").append(from).append(CRLF)
            append("To: ").append(to).append(CRLF)
            append("Subject: ").append(encodeHeader(rendered.subject)).append(CRLF)
            append("Date: ").append(date).append(CRLF)
            append("MIME-Version: 1.0").append(CRLF)
            append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append('"').append(CRLF)
            append(CRLF)
            part(boundary, "text/plain", rendered.text)
            part(boundary, "text/html", rendered.html)
            append("--").append(boundary).append("--").append(CRLF)
        }

    private fun StringBuilder.part(boundary: String, type: String, body: String) {
        append("--").append(boundary).append(CRLF)
        append("Content-Type: ").append(type).append("; charset=UTF-8").append(CRLF)
        append("Content-Transfer-Encoding: base64").append(CRLF)
        append(CRLF)
        Base64.Mime.encode(body.encodeToByteArray()).let { append(it) }
        append(CRLF)
    }

    /** RFC 2047 encoded-word for non-ASCII headers; ASCII headers stay as they are. */
    internal fun encodeHeader(text: String): String =
        if (text.all { it.code in 0x20..0x7e }) text
        else "=?UTF-8?B?" + Base64.encode(text.encodeToByteArray()) + "?="

    private const val CRLF = "\r\n"
}
