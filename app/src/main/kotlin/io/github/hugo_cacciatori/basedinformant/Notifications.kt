package io.github.hugo_cacciatori.basedinformant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.hugo_cacciatori.basedinformant.core.run.NewDigestNotice
import io.github.hugo_cacciatori.basedinformant.core.run.NoticeIds
import io.github.hugo_cacciatori.basedinformant.core.run.RunError
import io.github.hugo_cacciatori.basedinformant.core.run.RunNotifier
import io.github.hugo_cacciatori.basedinformant.core.run.RunOutcome
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter

/**
 * The four channels and the run's notifications (01-product.md section 7, 04-platforms.md section 3). Texts never carry
 * topic titles, keywords or tags: notifications can show on a lock screen.
 */
object Notifications {
    const val NEW_DIGESTS = "new_digests"
    const val REMINDERS = "reminders"
    const val PROGRESS = "progress"
    const val RUN_NOTICES = "run_notices"

    /** The digest a new-digest notification opens in History's viewer. */
    const val EXTRA_DIGEST_ID = "digest_id"

    /** Idempotent: run at every app start. */
    fun createChannels(context: Context) {
        val channels = listOf(
            NEW_DIGESTS to (R.string.channel_new_digests to NotificationManagerCompat.IMPORTANCE_DEFAULT),
            REMINDERS to (R.string.channel_reminders to NotificationManagerCompat.IMPORTANCE_DEFAULT),
            PROGRESS to (R.string.channel_progress to NotificationManagerCompat.IMPORTANCE_LOW),
            RUN_NOTICES to (R.string.channel_run_notices to NotificationManagerCompat.IMPORTANCE_LOW),
        ).map { (id, nameAndImportance) ->
            NotificationChannelCompat.Builder(id, nameAndImportance.second).setName(context.getString(nameAndImportance.first)).build()
        }
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(channels)
    }

    /** The ongoing notification Android requires while the runner works. */
    fun progress(context: Context): Notification = NotificationCompat.Builder(context, PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.notice_progress))
        .setContentText(context.getString(R.string.notice_progress_text))
        .setOngoing(true)
        .setProgress(0, 0, true)
        .setContentIntent(openApp(context, requestCode = 0, digestId = null))
        .build()

    /** The reminder (01-product.md section 6): free, it only says it is time; tapping it opens the Keywords screen. */
    @SuppressLint("MissingPermission")
    fun reminder(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(
            NoticeIds.REMINDER,
            NotificationCompat.Builder(context, REMINDERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notice_reminder))
                .setContentText(context.getString(R.string.notice_reminder_text))
                .setContentIntent(openApp(context, NoticeIds.REMINDER, digestId = null))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Opens the app; with [digestId], the intent carries the digest to show. */
    private fun openApp(context: Context, requestCode: Int, digestId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { digestId?.let { putExtra(EXTRA_DIGEST_ID, it) } }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Posts through [NotificationManagerCompat]; without the permission (Android 13+) nothing shows and the app goes on. */
    class Poster(private val context: Context) : RunNotifier {
        private val manager = NotificationManagerCompat.from(context)

        override fun newDigest(notice: NewDigestNotice) {
            val date = notice.date.toJavaLocalDate().format(DateTimeFormatter.ofPattern(context.getString(R.string.notice_date_pattern)))
            val count = context.resources.getQuantityString(R.plurals.notice_topics, notice.topicCount, notice.topicCount)
            post(
                notice.notificationId,
                NotificationCompat.Builder(context, NEW_DIGESTS)
                    .setContentTitle(context.getString(R.string.notice_new_digest))
                    .setContentText(context.getString(R.string.notice_new_digest_text, count, date))
                    .setContentIntent(openApp(context, notice.notificationId, notice.digestId)),
            )
        }

        override fun runNotice(outcome: RunOutcome) {
            val text = noticeText(outcome) ?: return
            val tokens = tokensOf(outcome)
            val body = if (tokens == null) text else context.getString(R.string.notice_tokens, text, tokens)
            post(
                NoticeIds.RUN,
                NotificationCompat.Builder(context, RUN_NOTICES)
                    .setContentTitle(context.getString(R.string.notice_run_title))
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(openApp(context, NoticeIds.RUN, digestId = null)),
            )
        }

        /** The gist of 01-product.md section 4.3; the screens give the full outcome and its buttons. */
        private fun noticeText(outcome: RunOutcome): String? = when (outcome) {
            is RunOutcome.Empty -> context.getString(R.string.notice_empty)
            is RunOutcome.NotWritten -> context.getString(R.string.notice_not_written)
            is RunOutcome.Unreadable -> context.getString(R.string.notice_unreadable)
            is RunOutcome.FailedBilled -> context.getString(R.string.notice_failed_billed)
            is RunOutcome.Interrupted -> context.getString(R.string.notice_interrupted)
            is RunOutcome.Refused -> context.getString(
                when (outcome.error) {
                    RunError.KEY_REFUSED -> R.string.notice_key_refused
                    RunError.INSUFFICIENT_TOKENS -> R.string.notice_insufficient_tokens
                    RunError.UNAVAILABLE -> R.string.notice_unavailable
                    else -> R.string.notice_refused
                },
            )
            is RunOutcome.Stored, RunOutcome.Busy, RunOutcome.NotBuilt -> null
        }

        private fun tokensOf(outcome: RunOutcome): Long? = when (outcome) {
            is RunOutcome.Empty -> outcome.tokensBilled
            is RunOutcome.NotWritten -> outcome.tokensBilled
            is RunOutcome.Unreadable -> outcome.tokensBilled
            is RunOutcome.FailedBilled -> outcome.tokensBilled
            else -> null
        }

        // The permission is checked by areNotificationsEnabled: denied, nothing is posted.
        @SuppressLint("MissingPermission")
        private fun post(id: Int, builder: NotificationCompat.Builder) {
            if (!manager.areNotificationsEnabled()) return
            manager.notify(id, builder.setSmallIcon(R.drawable.ic_notification).setAutoCancel(true).build())
        }
    }
}
