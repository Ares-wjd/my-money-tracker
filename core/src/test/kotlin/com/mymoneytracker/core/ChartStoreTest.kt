package com.mymoneytracker.core

import com.mymoneytracker.core.chart.ChartCalculator
import com.mymoneytracker.core.chart.ChartInterval
import com.mymoneytracker.core.chart.ChartStore
import com.mymoneytracker.core.chart.StoredChart
import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceHistory
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.TreeMap

// 웹 chartStore.test.ts 와 같은 케이스 (요약값 문자열도 같아야 한다).
class ChartStoreTest {
    private val today = LocalDate.of(2026, 10, 13)
    private val end = today.minusDays(1)
    private val a = InvestmentAccount(id = "a", name = "국내")
    private val b = InvestmentAccount(id = "b", name = "해외")
    private val sam = Holding(id = "h1", accountId = "a", name = "삼성전자", code = "005930", market = Market.KR)
    private val voo = Holding(id = "h2", accountId = "b", name = "VOO", code = "VOO", market = Market.NYSE)
    private val holdings = listOf(sam, voo)
    private val records = listOf(
        Record(id = "1", accountId = "a", type = RecordType.DEPOSIT, date = LocalDate.of(2026, 8, 1), amount = 1_000_000.0, krwAmount = 1_000_000.0, createdAt = 1),
        Record(id = "2", accountId = "a", type = RecordType.BUY, date = LocalDate.of(2026, 8, 3), holdingId = "h1", quantity = 10.0, price = 70_000.0, createdAt = 2),
        Record(id = "3", accountId = "a", type = RecordType.CASH_BALANCE, date = LocalDate.of(2026, 9, 1), amount = 300_000.0, createdAt = 3),
        Record(id = "4", accountId = "b", type = RecordType.DEPOSIT, date = LocalDate.of(2026, 9, 10), amount = 2_000_000.0, krwAmount = 2_000_000.0, createdAt = 4),
        Record(id = "5", accountId = "b", type = RecordType.BUY, date = LocalDate.of(2026, 9, 11), holdingId = "h2", quantity = 2.5, price = 500.25, currency = Currency.USD, createdAt = 5),
    )

    private fun closes(vararg pairs: Pair<String, Double>) = TreeMap(pairs.associate { LocalDate.parse(it.first) to it.second })
    private val snapshot = MarketSnapshot(
        prices = mapOf(
            MarketKeys.of(sam)!! to PriceHistory(closes("2026-08-03" to 70_000.0, "2026-09-01" to 72_000.0, "2026-10-01" to 75_000.0)),
            MarketKeys.of(voo)!! to PriceHistory(closes("2026-09-11" to 500.0, "2026-10-01" to 520.0)),
        ),
        usdKrw = closes("2026-09-10" to 1_380.0, "2026-10-01" to 1_400.0),
    )

    private fun full(account: InvestmentAccount, recs: List<Record> = records, snap: MarketSnapshot = snapshot, hs: List<Holding> = holdings, until: LocalDate = end): StoredChart {
        val plan = ChartStore.plan(account, hs, recs, snap, null, null, until)!!
        return ChartStore.build(plan, ChartStore.compute(plan, account, hs, recs, snap, null))
    }

    @Test
    fun hashIsSameAsWeb() {
        assertEquals("811c9dc5", ChartStore.hash(""))
        assertEquals("e40c292c", ChartStore.hash("a"))
        assertEquals("3ff0000000000000", ChartStore.num(1.0))
        assertEquals("0000000000000000", ChartStore.num(-0.0))
        assertEquals(
            "5|b|BUY|2026-09-11|USD|0000000000000000|0000000000000000||h2|4004000000000000|407f440000000000|0000000000000000|0000000000000000||0||5",
            ChartStore.recordLine(records[4]),
        )
        assertEquals("h2|b|VOO|NYSE|STOCK||", ChartStore.holdingLine(voo))
        assertEquals(ChartStore.hash(ChartStore.recordLine(records[4])), ChartStore.recordSigs(ChartStore.related("b", records), end)[LocalDate.of(2026, 9, 11)])
    }

    @Test
    fun storedMatchesDirectCalculation() {
        val stored = mapOf("a" to full(a), "b" to full(b))
        assertEquals(LocalDate.of(2026, 8, 1), stored.getValue("a").from)
        assertEquals(end, stored.getValue("a").to)
        val first = LocalDate.of(2026, 8, 1)
        val direct = ChartCalculator.series(
            ChartCalculator.pointDates(ChartInterval.DAY_5, today, first), today, listOf(a, b), holdings, records, snapshot, null,
        )
        val view = ChartStore.assemble(ChartInterval.DAY_5, today, ChartStore.firstDates(listOf(a, b), records), stored, direct.last())
        assertFalse(view.pending)
        assertEquals(direct.size, view.points.size)
        direct.zip(view.points).forEach { (d, s) ->
            assertEquals(d.date, s.date)
            assertEquals(d.valueKrw, s.valueKrw, 1e-6)
            assertEquals(d.investedKrw, s.investedKrw, 1e-6)
        }
        // 다시 계산할 것이 없다
        assertNull(ChartStore.plan(a, holdings, records, snapshot, null, stored.getValue("a"), end))
        assertNull(ChartStore.plan(b, holdings, records, snapshot, null, stored.getValue("b"), end))
    }

    @Test
    fun recomputesOnlyFromChangedDate() {
        val sa = full(a)
        // 첫 예수금 입력일(9/1) 이후 기록 → 그날부터
        val added = records + Record(id = "6", accountId = "a", type = RecordType.DEPOSIT, date = LocalDate.of(2026, 9, 20), amount = 1.0, krwAmount = 1.0)
        val plan = ChartStore.plan(a, holdings, added, snapshot, null, sa, end)!!
        assertEquals(LocalDate.of(2026, 9, 20), plan.start)
        assertEquals(LocalDate.of(2026, 9, 19), plan.keep.keys.max())
        // 다른 계좌 기록은 상관없음
        assertNull(ChartStore.plan(b, holdings, added, snapshot, null, full(b), end))
        // 첫 예수금 입력일 이전 기록 → 거꾸로 추정이 바뀌므로 처음부터
        val early = records + Record(id = "7", accountId = "a", type = RecordType.WITHDRAW, date = LocalDate.of(2026, 8, 20), amount = 1.0, krwAmount = 1.0)
        assertEquals(LocalDate.of(2026, 8, 1), ChartStore.plan(a, holdings, early, snapshot, null, sa, end)!!.start)
        // 기록을 지워도 그날부터
        val removed = records.filterNot { it.id == "5" }
        assertEquals(LocalDate.of(2026, 9, 11), ChartStore.plan(b, holdings, removed, snapshot, null, full(b), end)!!.start)
    }

    @Test
    fun recomputesOnPriceNewDayHoldingAndForce() {
        val sb = full(b)
        // 10월 종가가 새로 생기면 10/1 부터
        val newClose = snapshot.copy(prices = snapshot.prices + (MarketKeys.of(voo)!! to PriceHistory(closes("2026-09-11" to 500.0, "2026-10-01" to 520.0, "2026-10-08" to 530.0))))
        assertEquals(LocalDate.of(2026, 10, 1), ChartStore.plan(b, holdings, records, newClose, null, sb, end)!!.start)
        // 환율도
        val newFx = snapshot.copy(usdKrw = closes("2026-09-10" to 1_380.0, "2026-09-15" to 1_390.0, "2026-10-01" to 1_400.0))
        assertEquals(LocalDate.of(2026, 9, 1).let { maxOf(it, sb.from) }, ChartStore.plan(b, holdings, records, newFx, null, sb, end)!!.start)
        // 하루 지나면 그 하루만
        val next = ChartStore.plan(b, holdings, records, snapshot, null, sb, today)!!
        assertEquals(today, next.start)
        assertEquals(listOf(today), next.dates)
        // 종목 정보가 바뀌면 처음부터
        val edited = listOf(sam, voo.copy(manualPrice = 510.0, manualPriceDate = LocalDate.of(2026, 10, 5)))
        assertEquals(sb.from, ChartStore.plan(b, edited, records, snapshot, null, sb, end)!!.start)
        // 전체 다시 계산
        assertEquals(sb.from, ChartStore.plan(b, holdings, records, snapshot, null, sb, end, force = true)!!.start)
        // 기록이 없으면 null (저장값을 지운다)
        assertNull(ChartStore.plan(InvestmentAccount(id = "c", name = "빈 계좌"), holdings, records, snapshot, null, null, end))
    }

    @Test
    fun pendingWhenNotComputed() {
        val todayPoint = ChartCalculator.series(listOf(today), today, listOf(a, b), holdings, records, snapshot, null).single()
        val view = ChartStore.assemble(ChartInterval.MONTH, today, ChartStore.firstDates(listOf(a, b), records), mapOf("a" to full(a)), todayPoint)
        assertTrue(view.pending)
        // 8월 말은 b 의 첫 기록일 전이라 a 만으로, 9월 말은 b 가 없어 뺌
        assertEquals(listOf(LocalDate.of(2026, 8, 31), today), view.points.map { it.date })
    }
}
