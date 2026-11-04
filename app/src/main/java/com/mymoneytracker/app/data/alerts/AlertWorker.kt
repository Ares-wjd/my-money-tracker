package com.mymoneytracker.app.data.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.mymoneytracker.app.R
import com.mymoneytracker.app.data.EximClient
import com.mymoneytracker.app.data.KisClient
import com.mymoneytracker.app.data.MarketDataRepository
import com.mymoneytracker.app.data.PortfolioRepository
import com.mymoneytracker.app.data.SavingsRepository
import com.mymoneytracker.app.data.SecureStore
import com.mymoneytracker.core.alerts.Alert
import com.mymoneytracker.core.alerts.AlertRules
import com.mymoneytracker.core.alerts.AlertSettings
import com.mymoneytracker.core.alerts.CloseBasis
import com.mymoneytracker.core.goals.GoalCalculator
import com.mymoneytracker.core.market.MarketClock
import com.mymoneytracker.core.market.MarketGroup
import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.portfolio.AllocationCalculator
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.portfolio.PortfolioSummary
import com.mymoneytracker.core.portfolio.TodayChangeCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/**
 * 알림 백그라운드 확인 (docs/FEATURES.md 1-9): 데이터를 읽고, 한투 연결이 있으면 시세를 새로 받은 뒤
 * [AlertRules] 로 보낼 알림을 정해 보낸다. 밤 모으기 시간에는 모아 두었다가 끝 시각에 한 알림으로.
 */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /** 안드로이드 12 미만에서 급한 작업으로 돌 때 잠깐 보이는 알림. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "알림 확인 중", NotificationManager.IMPORTANCE_MIN))
        val notification = NotificationCompat.Builder(applicationContext, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("시세와 알림을 확인하는 중")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return ForegroundInfo(STATUS_ID, notification)
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (FirebaseApp.getApps(context).isEmpty()) return Result.success()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        val state = AlertState(context)
        val db = FirebaseFirestore.getInstance()
        val settings = AlertSettingsRepository(db, uid).load() ?: state.cachedSettings() ?: return Result.success()
        state.cacheSettings(settings)
        AlertScheduler.scheduleAlarm(context, settings)
        if (!settings.enabled || !AlertNotifier.ready(context)) return Result.success()
        runCatching { withTimeout(TIMEOUT_MS) { check(context, db, uid, settings, state) } }
            .onFailure { Log.w(TAG, "알림 확인 실패", it) }
        return Result.success()
    }

    private suspend fun <T> Flow<T>.once(): T? = withTimeoutOrNull(READ_TIMEOUT_MS) { first() }

    private suspend fun check(context: Context, db: FirebaseFirestore, uid: String, settings: AlertSettings, state: AlertState) {
        val store = SecureStore(context)
        val kis = KisClient(store)
        val repository = PortfolioRepository(db, uid) { }
        val savings = SavingsRepository(db, uid) { }
        val market = MarketDataRepository(db, uid, kis, EximClient(store))
        val accounts = repository.accounts().once() ?: return
        val holdings = repository.holdings().once() ?: return
        val records = repository.records().once() ?: return
        val appSettings = repository.settings().once()
        fun summarize(snap: MarketSnapshot): PortfolioSummary = PortfolioCalculator.summarize(
            accounts, holdings, records, snap.latestUsdKrw()?.second ?: appSettings?.manualUsdKrw,
            priceOf = { PriceLookup.current(it, snap) },
        )
        var snapshot = market.snapshot().once() ?: return
        var summary = summarize(snapshot)
        // 한투 연결이 있으면 시세를 새로 받는다 (받은 현재가·일봉은 PC 에도 저장)
        if (kis.hasCredentials) {
            val held = summary.accounts.flatMap { a -> a.holdings.filter { it.position.quantity > 0 }.map { it.holding } }
            val needsFx = summary.accounts.any { a -> a.cash.usd != 0.0 || a.holdings.any { it.holding.currency == Currency.USD && it.position.quantity > 0 } }
            runCatching { market.refreshLatest(held, needsFx) }
            snapshot = market.snapshot().once() ?: snapshot
            summary = summarize(snapshot)
        }

        val now = ZonedDateTime.now(MarketClock.KST)
        val today = now.toLocalDate()
        val sent = state.sent()
        val change = TodayChangeCalculator.calculate(summary, snapshot, now)
        val quietAlerts = mutableListOf<Alert>()
        quietAlerts += AlertRules.moves(change, summary.valueKrw, settings, sent)

        if (AlertRules.dailyDue(now.toLocalDateTime(), AlertRules.ALLOCATION_TIME, state.lastChecked(AlertState.CHECK_ALLOCATION))) {
            val targets = repository.allocationTargets().once()
            if (targets != null) {
                val views = targets.map { AllocationCalculator.view(it, summary.accounts, summary.usdKrw) }
                val result = AlertRules.allocations(views, state.allocOut(), settings, today)
                state.saveAllocOut(result.out)
                quietAlerts += result.alerts
                state.setLastChecked(AlertState.CHECK_ALLOCATION, today)
            }
        }
        if (AlertRules.dailyDue(now.toLocalDateTime(), AlertRules.GOAL_TIME, state.lastChecked(AlertState.CHECK_GOAL))) {
            val savingsAccounts = savings.accounts().once()
            val goals = savings.goals().once()
            val groups = savings.groups().once().orEmpty()
            if (savingsAccounts != null && goals != null) {
                val summaries = savingsAccounts.map { a ->
                    GoalCalculator.summarize(a, goals.filter { it.accountId == a.id }, today, groups.filter { it.accountId == a.id })
                }
                quietAlerts += AlertRules.goals(summaries, today, settings, sent)
                state.setLastChecked(AlertState.CHECK_GOAL, today)
            }
        }

        // 장 마감 정리: 정한 시각에 그대로 보낸다 (밤 모으기와 상관없이)
        val closeAlerts = mutableListOf<Alert>()
        for (group in listOf(MarketGroup.KR, MarketGroup.US)) {
            val setting = settings.close(group)
            val date = AlertRules.closeDate(group, setting, now, snapshot.calendar) ?: continue
            if ("CLOSE:${group.name}:$date" in sent) continue
            val regular = if (group == MarketGroup.US && setting.basis == CloseBasis.REGULAR && kis.hasCredentials) {
                summary.accounts.flatMap { it.holdings }
                    .filter { it.position.quantity > 0 && MarketGroup.of(it.holding) == MarketGroup.US }
                    .map { it.holding }.distinctBy { MarketKeys.of(it) }
                    .mapNotNull { h ->
                        val key = MarketKeys.of(h) ?: return@mapNotNull null
                        val close = runCatching { kis.overseasDaily(h.market, h.code, date) }.getOrNull()?.firstOrNull { it.date == date }?.close
                        close?.let { key to it }
                    }.toMap()
            } else {
                emptyMap()
            }
            val result = AlertRules.closeResult(group, date, setting.basis, summary, snapshot, regular) ?: continue
            val fxEffect = if (group == MarketGroup.US) change?.market(MarketGroup.FX)?.changeKrw else null
            closeAlerts += AlertRules.closeAlert(group, date, setting.basis, result, fxEffect)
        }

        val quiet = AlertRules.isQuiet(now.toLocalTime(), settings)
        if (quiet) {
            state.addPending(quietAlerts)
        } else {
            val pending = state.pending()
            if (pending.isNotEmpty()) {
                AlertRules.digest(pending, today)?.let { send(context, state, it) }
                state.clearPending()
            }
            quietAlerts.forEach { send(context, state, it) }
        }
        closeAlerts.forEach { send(context, state, it) }
        state.markSent((quietAlerts + closeAlerts).flatMap { it.marks }, today)
    }

    private fun send(context: Context, state: AlertState, alert: Alert) {
        if (AlertNotifier.post(context, alert)) state.addRecent(alert)
    }

    private companion object {
        const val TAG = "AlertWorker"
        const val STATUS_CHANNEL = "status"
        const val STATUS_ID = 7302
        const val TIMEOUT_MS = 4 * 60 * 1000L
        const val READ_TIMEOUT_MS = 30 * 1000L
    }
}
