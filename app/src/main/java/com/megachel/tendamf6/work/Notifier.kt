package com.megachel.tendamf6.work

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
import com.megachel.tendamf6.R
import com.megachel.tendamf6.ui.SmsActivity

private const val CHANNEL_ID = "tenda_sms"
private const val NOTIFICATION_ID = 1001

fun notifyNewSms(context: Context, newCount: Int, totalUnread: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_sms),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    val intent = Intent(context, SmsActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val pending = PendingIntent.getActivity(
        context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val text = context.resources.getQuantityString(R.plurals.new_sms, newCount, newCount)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(text)
        .setSubText(context.getString(R.string.unread_total, totalUnread))
        .setContentIntent(pending)
        .setAutoCancel(true)
        .build()

    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
}
