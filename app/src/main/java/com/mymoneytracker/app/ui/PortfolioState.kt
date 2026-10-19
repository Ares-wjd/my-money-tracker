package com.mymoneytracker.app.ui

import com.mymoneytracker.app.data.AppSettings
import com.mymoneytracker.core.chart.ChartInterval
import com.mymoneytracker.core.chart.ChartPoint
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.portfolio.DataCheck
import com.mymoneytracker.core.portfolio.HoldingValuation
import com.mymoneytracker.core.portfolio.KrwHoldingCalculator
import com.mymoneytracker.core.portfolio.KrwHoldingView
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.portfolio.PortfolioSummary
import java.time.LocalDate

/** 화면들이 공유하는 투자 데이터 전체. */
data class PortfolioData(
    val accounts: List<InvestmentAccount>,
    val holdings: List<Holding>,
    val records: List<Record>,
    val settings: AppSettings,
    val market: MarketSnapshot,
    val summary: PortfolioSummary,
    /** 데이터 점검 결과 (무시한 것 포함, v2.9). */
    val checks: List<DataCheck.Issue> = emptyList(),
) {
    /** 데이터 점검에서 무시한 항목 키 (폰·PC 공유). */
    val ignoredChecks: Set<String> get() = settings.ignoredChecks

    /** 홈 알림 개수: 무시하지 않은 🔴·🟠. */
    val checkAlerts: Int get() = DataCheck.alertCount(checks, settings.ignoredChecks)

    /** 계산에 쓰는 환율: 자동으로 받은 최신 환율, 없으면 직접 입력한 환율. */
    val usdKrw: Double? get() = market.latestUsdKrw()?.second ?: settings.manualUsdKrw
    val usdKrwDate: LocalDate? get() = market.latestUsdKrw()?.first
    /** 환율 출처 표시 ("한투" / "수출입은행", 과거 목록 값이면 null). */
    val usdKrwSource: String? get() = market.latestUsdKrwSourceLabel()

    fun account(id: String): InvestmentAccount? = accounts.firstOrNull { it.id == id }
    fun holding(id: String): Holding? = holdings.firstOrNull { it.id == id }
    fun record(id: String): Record? = records.firstOrNull { it.id == id }
    fun accountSummary(id: String) = summary.accounts.firstOrNull { it.account.id == id }
    fun holdingsOf(accountId: String): List<Holding> = holdings.filter { it.accountId == accountId }

    /** 계좌의 기록 (이 계좌가 받는 이체 포함), 최신순. */
    fun recordsOf(accountId: String): List<Record> =
        records.filter { it.accountId == accountId || it.toAccountId == accountId }
            .sortedWith(PortfolioCalculator.recordOrder.reversed())

    /** 오늘 수익률 (전일 종가 대비). 자동 시세를 쓰지 않거나 전일 종가가 없으면 null. */
    fun todayRate(valuation: HoldingValuation): Double? = PriceLookup.dailyChangeRate(valuation.holding, market)

    /** 원화 보기의 해외 종목 숫자 (매매일 환율로 환차익 반영). 매매일 환율이 하나라도 없으면 null. */
    fun krwView(valuation: HoldingValuation): KrwHoldingView? =
        KrwHoldingCalculator.view(valuation, records, usdKrw) { market.tradeUsdKrw(it, LocalDate.now()) }

    fun recordsOfHolding(holdingId: String): List<Record> =
        records.filter { it.holdingId == holdingId }.sortedWith(PortfolioCalculator.recordOrder.reversed())
}

/** 기기에만 저장된 API 연결 정보 요약 (비밀 값은 앞부분만). */
data class ApiStatus(
    val kisKeyHint: String? = null,
    val kisServiceExpiry: LocalDate? = null,
    val eximKeyHint: String? = null,
    val linkedAccountNo: String? = null,
    val linkedProduct: String = "01",
    val linkedAccountId: String? = null,
    val syncStart: LocalDate? = null,
    val lastSync: LocalDate? = null,
) {
    val hasKis: Boolean get() = kisKeyHint != null
    val hasExim: Boolean get() = eximKeyHint != null

    /** KIS 서비스 만료까지 남은 날 (만료일을 입력한 경우). */
    fun daysUntilKisExpiry(today: LocalDate = LocalDate.now()): Long? =
        kisServiceExpiry?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }
}

data class RefreshState(
    val running: Boolean = false,
    val lastRefreshedAt: Long? = null,
    val errors: List<String> = emptyList(),
)

data class ChartSelection(
    val interval: ChartInterval = ChartInterval.MONTH,
    val accountId: String? = null,
)

/** [loading] 과거 시세를 받는 중, [computing] 일별 값을 계산하는 중 (또는 아직 계산하지 않은 날이 있음). */
data class ChartState(
    val selection: ChartSelection = ChartSelection(),
    val points: List<ChartPoint> = emptyList(),
    val loading: Boolean = false,
    val computing: Boolean = false,
    val errors: List<String> = emptyList(),
)
