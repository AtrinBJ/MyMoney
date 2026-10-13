package com.mymoney.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mymoney.app.MainActivity
import com.mymoney.app.R
import com.mymoney.app.locale.LocaleController
import com.mymoney.core.designsystem.format.Formats
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.settings.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Posts reminders as one notification. Amounts appear only in the private version; the lock-screen version
 * says just "You have reminders" (docs/SECURITY.md §Notifications).
 */
class AndroidNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locale: LocaleController,
) : Notifier {
    /** Set by the worker before [show]; formatting follows the user's settings. */
    var settings: AppSettings = AppSettings()
    var currencies: Map<String, Currency> = emptyMap()

    override fun canNotify(): Boolean = canNotify(context)

    override fun show(reminders: List<Reminder>) {
        if (reminders.isEmpty()) return
        ensureChannel(context)
        val formats = Formats(settings, locale.current(), currencies)
        val lines = reminders.map { text(it, formats) }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_public))
            .build()
        val title = if (lines.size == 1) lines.first() else context.getString(R.string.notif_summary, Digits.localize(lines.size.toString(), settings.digits))
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (lines.size == 1) null else lines.first())
            .setStyle(NotificationCompat.InboxStyle().also { s -> lines.take(6).forEach { s.addLine(it) } })
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; nothing to do.
        }
    }

    private fun text(r: Reminder, f: Formats): String = when (r) {
        is Reminder.Due -> context.getString(
            if (r.income) R.string.notif_due_income else R.string.notif_due,
            r.ruleName, f.money.format(r.amountMinor, r.currency), f.dates.medium(r.dueDate),
        )
        is Reminder.Overdue -> context.getString(R.string.notif_overdue, Digits.localize(r.count.toString(), settings.digits))
        is Reminder.BudgetThreshold -> context.getString(R.string.notif_budget, r.budgetName, Digits.localize(r.percent.toString(), settings.digits))
        is Reminder.AutoPosted -> context.getString(R.string.notif_auto_posted, Digits.localize(r.count.toString(), settings.digits))
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        private const val NOTIFICATION_ID = 1001

        fun canNotify(context: Context): Boolean {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.notif_channel_desc)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }
}
