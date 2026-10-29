package io.github.hugo_cacciatori.basedinformant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.hugo_cacciatori.basedinformant.core.render.RenderedDigest

/**
 * Share → Email (01-product.md section 8, 04-platforms.md section 3): a new message in the device's mail app, as other
 * apps' share buttons do. `ACTION_SEND` carries the subject, the plain text and the styled HTML; its `mailto:` selector
 * lets only mail apps answer, so the default one opens. The owner picks the recipient and sends; the app sends nothing.
 */
object MailShare {
    fun intent(mail: RenderedDigest): Intent = Intent(Intent.ACTION_SEND)
        .putExtra(Intent.EXTRA_SUBJECT, mail.subject)
        .putExtra(Intent.EXTRA_TEXT, mail.text)
        // Most mail apps ignore it and show the text (owner, 2026-10-29: styled where accepted).
        .putExtra(Intent.EXTRA_HTML_TEXT, mail.html)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply { selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) }

    /** False when the device has no mail app. */
    fun open(context: Context, mail: RenderedDigest): Boolean = try {
        context.startActivity(intent(mail))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
