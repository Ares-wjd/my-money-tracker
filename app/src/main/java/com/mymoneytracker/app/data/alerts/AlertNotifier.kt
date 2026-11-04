package com.mymoneytracker.app.data.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mymoneytracker.app.MainActivity
import com.mymoneytracker.app.R
import com.mymoneytracker.core.alerts.Alert
import com.mymoneytracker.core.alerts.AlertChannel
import com.mymoneytracker.core.alerts.AlertTarget

/** 알림 보내기와 필수 설정(알림 권한·배터리 최적화 제외) 확인 (v2.11). */
object AlertNotifier {
    const val EXTRA_SCREEN = "alert_screen"
    const val EXTRA_ID = "alert_id"

    /** 알림 권한 (안드로이드 13 미만은 항상 허용). */
    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 배터리 최적화에서 빠져 있는지 (백그라운드 확인이 막히지 않게). */
    fun batteryUnrestricted(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    /** 필수 설정 두 가지가 모두 되어 있는지. */
    fun ready(context: Context): Boolean = notificationsAllowed(context) && batteryUnrestricted(context)

    /** 채널 만들기 (여러 번 불러도 된다). 채널마다 폰 설정에서 소리를 바꿀 수 있다. */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        AlertChannel.entries.forEach { ch ->
            val importance = if (ch == AlertChannel.DIGEST) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH
            manager.createNotificationChannel(NotificationChannel(ch.id, ch.label, importance))
        }
    }

    /** 알림 하나를 보낸다. 권한이 없으면 false. */
    fun post(context: Context, alert: Alert): Boolean {
        if (!notificationsAllowed(context)) return false
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_SCREEN, alert.target.screen)
            .putExtra(EXTRA_ID, alert.target.id)
        val requestCode = alert.key.hashCode()
        val pending = PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, alert.channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setColor(0xFF0F6B5C.toInt())
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** 시험 알림. */
    fun test(context: Context): Boolean = post(
        context,
        Alert("TEST:${System.currentTimeMillis()}", AlertChannel.MOVE, "시험 알림", "알림이 이렇게 옵니다. 누르면 시장별 변동이 열려요.", AlertTarget.TODAY),
    )
}
