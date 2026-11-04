package com.mymoneytracker.app.data.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mymoneytracker.core.alerts.AlertRules
import com.mymoneytracker.core.alerts.AlertSettings
import com.mymoneytracker.core.market.MarketClock
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 알림 확인 예약 (v2.11): 켜져 있으면 30분마다 백그라운드 확인(WorkManager) + 정해진 시각(비중 08:30, 목표 09:00,
 * 장 마감 정리 시각, 밤 모으기 끝)에는 시각 알람으로 깨워 바로 확인한다.
 */
object AlertScheduler {
    private const val PERIODIC = "alert-periodic"
    private const val NOW = "alert-now"
    private const val ALARM_REQUEST = 7301

    /** 설정이 바뀌었거나 앱을 열었을 때: 켜져 있으면 예약, 꺼져 있으면 모두 취소. */
    fun apply(context: Context, settings: AlertSettings) {
        AlertState(context).cacheSettings(settings)
        val work = WorkManager.getInstance(context)
        if (settings.enabled) {
            val periodic = PeriodicWorkRequestBuilder<AlertWorker>(30, TimeUnit.MINUTES)
                .setConstraints(networkConstraint())
                .build()
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
            scheduleAlarm(context, settings)
        } else {
            work.cancelUniqueWork(PERIODIC)
            cancelAlarm(context)
        }
    }

    /** 지금 바로 한 번 확인 (시각 알람·재부팅 뒤). 폰이 쉬는 중에도 돌도록 급한 작업으로. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AlertWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(networkConstraint())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    private fun networkConstraint() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 다음 정해진 시각에 알람을 건다 (정확한 알람을 못 쓰면 대략 그 시각). */
    fun scheduleAlarm(context: Context, settings: AlertSettings) {
        val next = AlertRules.nextWake(ZonedDateTime.now(MarketClock.KST), settings) ?: return cancelAlarm(context)
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = next.toInstant().toEpochMilli()
        val pi = alarmIntent(context)
        val exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
        try {
            if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST,
        Intent(context, AlertAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 로그아웃·탈퇴할 때: 예약을 모두 지우고 기기에 둔 알림 상태도 지운다. */
    fun stopAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
        cancelAlarm(context)
        AlertState(context).clear()
    }
}

/** 정해진 시각 알람: 바로 확인 작업을 돌린다 (다음 알람은 작업이 다시 건다). */
class AlertAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlertScheduler.runNow(context)
    }
}

/** 재부팅·앱 업데이트 뒤: 기기에 둔 설정 사본으로 예약을 다시 건다. */
class AlertBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = AlertState(context).cachedSettings() ?: return
        AlertScheduler.apply(context, settings)
        if (settings.enabled) AlertScheduler.runNow(context)
    }
}
