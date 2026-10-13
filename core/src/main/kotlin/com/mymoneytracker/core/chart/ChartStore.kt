package com.mymoneytracker.core.chart

import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceEstimator
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.CashCalculator
import java.time.LocalDate
import java.time.YearMonth
import java.util.TreeMap

/** 저장된 하루 값 (그 계좌의 평가금·투자금·누적 배당, 그날 가격이 없는 종목이 있었는지). */
data class DayValue(
    val valueKrw: Double,
    val investedKrw: Double,
    val dividendsKrw: Double,
    val missingPrice: Boolean = false,
)

/**
 * 계좌 하나의 저장된 일별 값 (users/{uid}/chartDaily/{계좌id}). [from] 첫 기록일, [to] 계산한 마지막 날(그때의 어제).
 * [recSig]·[accSig]·[priceSig]·[anchor] 는 계산할 때의 입력 요약 — 지금 입력과 비교해 다시 계산할 범위를 찾는다.
 */
data class StoredChart(
    val version: Int,
    val from: LocalDate,
    val to: LocalDate,
    val days: Map<LocalDate, DayValue>,
    val recSig: Map<LocalDate, String>,
    val accSig: String,
    val priceSig: Map<String, String>,
    val anchor: LocalDate?,
)

/** 계산 계획: [start] ~ [end] 를 다시 계산하고, [keep] (start 이전 저장값) 은 그대로 둔다. */
data class ChartPlan(
    val accountId: String,
    val from: LocalDate,
    val start: LocalDate,
    val end: LocalDate,
    val keep: Map<LocalDate, DayValue>,
    val recSig: Map<LocalDate, String>,
    val accSig: String,
    val priceSig: Map<String, String>,
    val anchor: LocalDate?,
) {
    /** 다시 계산할 날짜들 (start ~ end, 하루씩). */
    val dates: List<LocalDate>
        get() = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
}

/** 저장된 값으로 만든 그래프. [pending] 은 아직 계산하지 않은 날이 있어 점을 뺐는지. */
data class ChartView(val points: List<ChartPoint>, val pending: Boolean)

/**
 * 그래프 일별 값 미리 계산·저장 (웹 chartStore.ts 와 같은 규칙). 요약값(해시)은 폰·PC 가 똑같이 만들어야 하므로
 * 칸을 정해진 순서로 이어 붙이고, 숫자는 64비트 실수 비트(16진수)로 쓴다.
 */
object ChartStore {
    /** 2: 시세가 없는 날 가격 추정 (계산 규칙이 바뀌어 처음부터 다시 계산). */
    const val VERSION = 2

    /** FNV-1a 32비트 (UTF-16 글자 단위), 8자리 16진수. */
    fun hash(text: String): String {
        var h = 0x811c9dc5.toInt()
        for (c in text) {
            h = h xor c.code
            h *= 16777619
        }
        return Integer.toHexString(h).padStart(8, '0')
    }

    /** 숫자 → 64비트 실수 비트 16진수 (−0 은 0). */
    fun num(x: Double): String =
        java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(if (x == 0.0) 0.0 else x)).padStart(16, '0')

    fun recordLine(r: Record): String = listOf(
        r.id, r.accountId, r.type.name, r.date.toString(), r.currency.name, num(r.amount), num(r.krwAmount),
        r.toAccountId.orEmpty(), r.holdingId.orEmpty(), num(r.quantity), num(r.price), num(r.fee), num(r.tax),
        r.cost?.let(::num).orEmpty(), if (r.initial) "1" else "0", r.externalId.orEmpty(), r.createdAt.toString(),
    ).joinToString("|")

    fun holdingLine(h: Holding): String = listOf(
        h.id, h.accountId, h.code, h.market.name, h.assetType.name, h.manualPrice?.let(::num).orEmpty(), h.manualPriceDate?.toString().orEmpty(),
    ).joinToString("|")

    /** 이 계좌와 관련된 기록 (보낸·받은 이체 포함). */
    fun related(accountId: String, records: List<Record>): List<Record> =
        records.filter { it.accountId == accountId || it.toAccountId == accountId }

    /** 날짜별 기록 요약 ([end] 까지). */
    fun recordSigs(related: List<Record>, end: LocalDate): Map<LocalDate, String> =
        related.filter { !it.date.isAfter(end) }.groupBy { it.date }
            .mapValues { (_, list) -> hash(list.map(::recordLine).sorted().joinToString("\n")) }

    /** 종목·연결 여부·직접 입력 환율 요약 (바뀌면 처음부터 다시 계산). */
    fun accountSig(holdings: List<Holding>, linked: Boolean, manualUsdKrw: Double?): String =
        hash((listOf("linked=" + (if (linked) "1" else "0"), "fx=" + manualUsdKrw?.let(::num).orEmpty()) + holdings.map(::holdingLine).sorted()).joinToString("\n"))

    /**
     * 종가·환율 요약: "시세키|연-월" (또는 "fx|연-월") 마다 그달 [from] ~ [end] 값, "시세키|base" 는 [from] 에 쓰는 직전 값.
     * 값이 없는 달은 넣지 않는다.
     */
    fun priceSigs(keys: Collection<String>, snapshot: MarketSnapshot, from: LocalDate, end: LocalDate): Map<String, String> {
        val out = mutableMapOf<String, String>()
        fun add(name: String, series: TreeMap<LocalDate, Double>?) {
            if (series == null || from.isAfter(end)) return
            series.floorEntry(from.minusDays(1))?.let { out["$name|base"] = hash(it.key.toString() + "=" + num(it.value)) }
            series.subMap(from, true, end, true).entries.groupBy { YearMonth.from(it.key) }.forEach { (month, entries) ->
                out["$name|$month"] = hash(entries.joinToString(";") { it.key.toString() + "=" + num(it.value) })
            }
        }
        keys.sorted().forEach { add(it, snapshot.prices[it]?.closes) }
        add("fx", snapshot.usdKrw)
        return out
    }

    /**
     * 매매 단가 요약: "trade:가격계열키|연-월" 마다 그달 매수·매도 (날짜·단가·수량). 같은 시세 키면 다른 계좌 기록도 (가격 추정에 쓰므로).
     * [end] 이후는 넣지 않는다.
     */
    fun tradeSigs(keys: Collection<String>, estimator: PriceEstimator, holdings: List<Holding>, records: List<Record>, end: LocalDate): Map<String, String> {
        val byId = holdings.associateBy { it.id }
        val wanted = keys.toSet()
        return records
            .filter { (it.type == RecordType.BUY || it.type == RecordType.SELL) && !it.date.isAfter(end) }
            .mapNotNull { r -> r.holdingId?.let(byId::get)?.let(estimator::keyOf)?.takeIf { it in wanted }?.let { it to r } }
            .groupBy({ (key, r) -> "trade:$key|${YearMonth.from(r.date)}" }, { (_, r) -> r.date.toString() + "=" + num(r.price) + "=" + num(r.quantity) })
            .mapValues { (_, lines) -> hash(lines.sorted().joinToString(";")) }
    }

    /** 직접 입력 계좌의 거꾸로 추정 경계: 통화별 첫 예수금 입력일 중 가장 늦은 날 (입력이 없으면 null, 연결 계좌는 null). */
    fun anchorLimit(accountId: String, related: List<Record>, linked: Boolean): LocalDate? {
        if (linked) return null
        return Currency.entries.mapNotNull { c ->
            related.filter { it.type == RecordType.CASH_BALANCE && it.accountId == accountId && it.currency == c }.minOfOrNull { it.date }
        }.maxOrNull()
    }

    /** 요약 두 개를 비교해 다른 키(날짜·달) 중 가장 이른 날. 같으면 null. */
    private fun <K> earliestDiff(a: Map<K, String>, b: Map<K, String>, dateOf: (K) -> LocalDate): LocalDate? =
        (a.keys + b.keys).filter { a[it] != b[it] }.minOfOrNull(dateOf)

    /**
     * 계좌 하나의 계산 계획. 기록이 없으면 null (저장값은 지운다). 다시 계산할 것이 없으면 null.
     * @param end 저장할 마지막 날 (어제)
     * @param force 처음부터 다시 계산 (설정의 [그래프 전체 다시 계산])
     */
    fun plan(
        account: InvestmentAccount,
        holdings: List<Holding>,
        records: List<Record>,
        snapshot: MarketSnapshot,
        manualUsdKrw: Double?,
        stored: StoredChart?,
        end: LocalDate,
        force: Boolean = false,
    ): ChartPlan? {
        val related = related(account.id, records)
        val from = related.minOfOrNull { it.date } ?: return null
        val own = holdings.filter { it.accountId == account.id }
        val linked = CashCalculator.isBrokerLinked(account.id, related)
        val keys = own.mapNotNull { MarketKeys.of(it) }.toSet()
        val estimator = PriceEstimator(holdings, records, snapshot)
        val seriesKeys = own.map(estimator::keyOf).toSet()
        val accSig = accountSig(own, linked, manualUsdKrw)
        val anchor = anchorLimit(account.id, related, linked)
        val recSig = recordSigs(related, end)
        val priceSig = priceSigs(keys, snapshot, from, end) + tradeSigs(seriesKeys, estimator, holdings, records, end)
        fun plan(start: LocalDate, keep: Map<LocalDate, DayValue>) =
            ChartPlan(account.id, from, start, end, keep, recSig, accSig, priceSig, anchor)

        val full = force || stored == null || stored.version != VERSION || stored.accSig != accSig || stored.from != from ||
            (!linked && stored.anchor != anchor)
        if (full) return plan(from, emptyMap())
        stored!!
        // 저장할 때 계산한 날(to)까지만 비교하고, 그 뒤 날짜는 새로 계산할 범위
        val recDiff = earliestDiff(recordSigs(related, stored.to), stored.recSig) { it }
        val oldSigs = priceSigs(keys, snapshot, from, stored.to) + tradeSigs(seriesKeys, estimator, holdings, records, stored.to)
        val priceDiff = earliestDiff(oldSigs, stored.priceSig) { key ->
            key.substringAfterLast('|').let { if (it == "base") from else YearMonth.parse(it).atDay(1) }
        }
        val changed = listOfNotNull(recDiff, priceDiff).minOrNull()
        val candidates = mutableListOf(stored.to.plusDays(1))
        for (key in seriesKeys) {
            // 바뀐 가격 하나는 그 앞의 알고 있는 가격까지 추정값을 바꾼다 → 바로 앞 알고 있는 가격 다음 날부터
            if (changed != null) candidates += estimator.knownBefore(key, changed)?.plusDays(1) ?: from
            // 새 날짜에 생긴 가격이 그 앞의 (시세를 모르던) 기간을 잇게 되면 그 기간도
            val next = estimator.knownFrom(key, stored.to.plusDays(1))
            val prev = estimator.knownUntil(key, stored.to)
            if (next != null && !next.key.isAfter(end) && prev != null) {
                val holiday = prev.value.close && next.value.close &&
                    java.time.temporal.ChronoUnit.DAYS.between(prev.key, next.key) <= PriceEstimator.HOLIDAY_GAP_DAYS
                if (!holiday) candidates += prev.key.plusDays(1)
            }
        }
        changed?.let { candidates += it }
        var start = candidates.min()
        if (start.isBefore(from)) start = from
        if (start.isAfter(end)) return null
        // 첫 예수금 입력일 이전이 바뀌면 거꾸로 추정한 앞 날짜들도 바뀐다
        if (anchor != null && !start.isAfter(anchor)) start = from
        return plan(start, stored.days.filterKeys { !it.isBefore(from) && it.isBefore(start) })
    }

    /** 계획의 날짜들 중 [dates] 를 계산한다 (웹은 나눠서 부르고, 폰은 한 번에). */
    fun compute(
        plan: ChartPlan,
        account: InvestmentAccount,
        holdings: List<Holding>,
        records: List<Record>,
        snapshot: MarketSnapshot,
        manualUsdKrw: Double?,
        dates: List<LocalDate> = plan.dates,
    ): Map<LocalDate, DayValue> {
        val related = related(account.id, records)
        val own = holdings.filter { it.accountId == account.id }
        val estimator = PriceEstimator(holdings, records, snapshot)
        return ChartCalculator.series(dates, plan.end.plusDays(1), listOf(account), own, related, snapshot, manualUsdKrw, estimator = estimator)
            .associate { it.date to DayValue(it.valueKrw, it.investedKrw, it.dividendsKrw, it.missingPrice) }
    }

    /** 계산 결과로 저장할 값. */
    fun build(plan: ChartPlan, computed: Map<LocalDate, DayValue>): StoredChart =
        StoredChart(VERSION, plan.from, plan.end, plan.keep + computed, plan.recSig, plan.accSig, plan.priceSig, plan.anchor)

    /**
     * 저장된 값으로 그래프 점을 만든다. [firstDates] 는 보여줄 계좌(기록이 있는 계좌)별 첫 기록일 (그 전은 0),
     * [todayPoint] 는 현재가로 바로 계산한 오늘 값. 계좌가 아직 계산하지 않은 날은 점을 뺀다.
     */
    fun assemble(
        interval: ChartInterval,
        today: LocalDate,
        firstDates: Map<String, LocalDate>,
        stored: Map<String, StoredChart>,
        todayPoint: ChartPoint,
    ): ChartView {
        val first = firstDates.values.minOrNull() ?: return ChartView(listOf(todayPoint), false)
        var pending = false
        val points = ChartCalculator.pointDates(interval, today, first).mapNotNull { date ->
            if (!date.isBefore(today)) return@mapNotNull todayPoint
            var value = 0.0
            var invested = 0.0
            var dividends = 0.0
            var missing = false
            for ((id, accountFirst) in firstDates) {
                if (date.isBefore(accountFirst)) continue
                val day = stored[id]?.takeIf { it.from == accountFirst && !date.isAfter(it.to) }?.days?.get(date)
                if (day == null) {
                    pending = true
                    return@mapNotNull null
                }
                value += day.valueKrw
                invested += day.investedKrw
                dividends += day.dividendsKrw
                missing = missing || day.missingPrice
            }
            ChartPoint(date, value, invested, dividends, missing)
        }
        return ChartView(points, pending)
    }

    /** 계좌별 첫 기록일 ([accounts] 중 기록이 있는 계좌만). */
    fun firstDates(accounts: List<InvestmentAccount>, records: List<Record>): Map<String, LocalDate> =
        accounts.mapNotNull { acc -> related(acc.id, records).minOfOrNull { it.date }?.let { acc.id to it } }.toMap()
}
