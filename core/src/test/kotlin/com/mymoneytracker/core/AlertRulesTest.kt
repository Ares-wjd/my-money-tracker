package com.mymoneytracker.core

import com.mymoneytracker.core.alerts.AlertRules
import com.mymoneytracker.core.alerts.AlertSettings
import com.mymoneytracker.core.alerts.AlertTarget
import com.mymoneytracker.core.alerts.CloseBasis
import com.mymoneytracker.core.alerts.CloseSetting
import com.mymoneytracker.core.alerts.CloseTimes
import com.mymoneytracker.core.goals.GoalCalculator
import com.mymoneytracker.core.market.Candle
import com.mymoneytracker.core.market.MarketCalendar
import com.mymoneytracker.core.market.MarketGroup
import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceHistory
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.GoalType
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.PricePoint
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.model.SavingsAccount
import com.mymoneytracker.core.model.SavingsGoal
import com.mymoneytracker.core.model.SavingsSubAccount
import com.mymoneytracker.core.portfolio.AllocationRow
import com.mymoneytracker.core.portfolio.AllocationTarget
import com.mymoneytracker.core.portfolio.AllocationView
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.portfolio.TodayChangeCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.TreeMap

/** 알림 판단 규칙 (docs/FEATURES.md 1-9). */
class AlertRulesTest {
    private val today = LocalDate.of(2026, 10, 14) // 수요일
    private val day = LocalDate.of(2026, 10, 1)
    /** 수요일 밤: 국내 장 마감, 미국 정규장 (서머타임). */
    private val night = ZonedDateTime.parse("2026-10-14T23:50:00+09:00")
    private val a = InvestmentAccount(id = "a", name = "국내")
    private val b = InvestmentAccount(id = "b", name = "해외")
    private val sam = Holding(id = "s", accountId = "a", name = "삼성전자", code = "005930", market = Market.KR)
    private val voo = Holding(id = "v", accountId = "b", name = "VOO", code = "VOO", market = Market.NYSE, assetType = AssetType.ETF)
    private val records = listOf(
        Record(id = "1", accountId = "a", type = RecordType.BUY, date = day, holdingId = "s", quantity = 12.0, price = 60_000.0, createdAt = 1),
        Record(id = "2", accountId = "b", type = RecordType.BUY, date = day, holdingId = "v", quantity = 2.0, price = 450.0, currency = Currency.USD, createdAt = 2),
    )

    private fun snapshot(usCandle: Candle = Candle(today, 500.0, 520.0), usPrev: Candle? = null) = MarketSnapshot(
        prices = mapOf(
            MarketKeys.of(sam)!! to PriceHistory(latest = PricePoint(75_000.0, today), candle = Candle(today, 70_000.0, 76_000.0, regular = 75_000.0)),
            MarketKeys.of(voo)!! to PriceHistory(latest = PricePoint(520.0, today), candle = usCandle, prevCandle = usPrev),
        ),
        usdKrw = TreeMap(mapOf(today.minusDays(1) to 1_390.0)),
        usdKrwLatest = today to 1_400.0,
    )

    private fun summary(snap: MarketSnapshot) =
        PortfolioCalculator.summarize(listOf(a, b), listOf(sam, voo), records, snap.latestUsdKrw()?.second, priceOf = { PriceLookup.current(it, snap) })

    private val on = AlertSettings(enabled = true)

    @Test
    fun levelOneAndTwo() {
        assertEquals(0, AlertRules.level(0.019, 2.0))
        assertEquals(1, AlertRules.level(-0.02, 2.0))
        assertEquals(2, AlertRules.level(0.04, 2.0))
        assertEquals(0, AlertRules.level(0.5, 0.0))
        assertEquals(0, AlertRules.level(null, 2.0))
    }

    @Test
    fun movesOncePerLevelOnLiveMarketOnly() {
        val snap = snapshot()
        val s = summary(snap)
        val change = TodayChangeCalculator.calculate(s, snap, night)
        // 미국 +4% (시장 기준 2% 의 2배), 종목 VOO +4% 는 종목 기준 5% 미만. 국내는 장 마감이라 확인하지 않는다
        val first = AlertRules.moves(change, s.valueKrw, on, emptySet())
        assertEquals(1, first.size)
        val us = first.single()
        assertEquals("미국 ▲4.00% (+5.6만원) · 정규장", us.title)
        assertEquals("10/14(수) 일봉 · VOO ▲4.00%", us.body)
        assertEquals(listOf("MOVE:US:2026-10-14:UP:1", "MOVE:US:2026-10-14:UP:2"), us.marks)
        assertEquals(AlertTarget.TODAY, us.target)
        // 이미 보냈으면 다시 보내지 않는다
        assertTrue(AlertRules.moves(change, s.valueKrw, on, us.marks.toSet()).isEmpty())
        // 종목 기준을 3% 로 낮추면 종목 알림 (2배는 아님)
        val items = AlertRules.moves(change, s.valueKrw, on.copy(moveItem = 3.0), us.marks.toSet())
        assertEquals("미국 ▲4.00% (+5.6만원) · 정규장", items.single().title)
        assertEquals("10/14(수) 일봉 · VOO ▲4.00%", items.single().body)
        assertEquals(listOf("MOVE_ITEM:US_VOO:2026-10-14:UP:1"), items.single().marks)
        // 끄면 없음
        assertTrue(AlertRules.moves(change, s.valueKrw, on.copy(moveOn = false), emptySet()).isEmpty())
    }

    @Test
    fun allocationOnlyNewlyOutOfRange() {
        val target = AllocationTarget(id = "t", name = "ISA 자산배분", accountIds = listOf("a"), targets = mapOf("US:VOO" to 40.0, "KR:005930" to 60.0))
        fun view(vooWeight: Double) = AllocationView(
            target,
            listOf(
                AllocationRow("US:VOO", "VOO", voo, 1.0, 0.0, null, null, vooWeight, 0.4),
                AllocationRow("KR:005930", "삼성전자", sam, 1.0, 0.0, null, null, 1 - vooWeight, 0.6),
            ),
            100.0,
            false,
        )
        val first = AlertRules.allocations(listOf(view(0.472)), emptyMap(), on, today)
        val alert = first.alerts.single()
        assertEquals("ISA 자산배분 · 허용 범위 밖 2종목", alert.title)
        assertEquals("초과 VOO 47.2% (35~45%) · 부족 삼성전자 52.8% (55~65%)", alert.body)
        assertEquals(AlertTarget.allocation("t"), alert.target)
        assertEquals(setOf("US:VOO", "KR:005930"), first.out["t"])
        // 계속 범위 밖이면 다시 알리지 않는다
        assertTrue(AlertRules.allocations(listOf(view(0.48)), first.out, on, today).alerts.isEmpty())
        // 범위 안으로 돌아오면 잊는다 → 다시 나가면 알림
        val back = AlertRules.allocations(listOf(view(0.42)), first.out, on, today)
        assertEquals(emptySet<String>(), back.out["t"])
        assertEquals(1, AlertRules.allocations(listOf(view(0.47)), back.out, on, today).alerts.size)
        // 끈 비중 설정은 확인하지 않는다
        assertTrue(AlertRules.allocations(listOf(view(0.47)), emptyMap(), on.copy(allocOff = setOf("t")), today).alerts.isEmpty())
    }

    @Test
    fun goalDaysAndOverdue() {
        val account = SavingsAccount(id = "s1", name = "IT기기금", subAccounts = listOf(SavingsSubAccount("CMA", balance = 1_640_000.0)))
        val phone = SavingsGoal(id = "g1", accountId = "s1", name = "휴대폰", type = GoalType.RECURRING, amount = 2_000_000.0, dueDate = today.plusDays(7), intervalMonths = 24)
        val lens = SavingsGoal(id = "g2", accountId = "s1", name = "렌즈 교체", type = GoalType.RECURRING, amount = 100_000.0, dueDate = today.minusDays(2), intervalMonths = 6)
        val summary = GoalCalculator.summarize(account, listOf(phone, lens), today)
        val alerts = AlertRules.goals(listOf(summary), today, on, emptySet())
        assertEquals(listOf("렌즈 교체 · 예정일 지남", "휴대폰 · 7일 남음 (10/21)"), alerts.map { it.title }.sorted())
        val p = alerts.first { it.key.startsWith("GOAL:") }
        // 잔액은 가까운 목표부터: 렌즈 10만원, 휴대폰 154만원
        assertEquals("IT기기금 · 달성 77% (154만원 / 200만원) · 46만원 부족", p.body)
        assertEquals("GOAL:g1:2026-10-21:7", p.key)
        assertTrue(AlertRules.goals(listOf(summary), today, on, alerts.flatMap { it.marks }.toSet()).isEmpty())
        // 고르지 않은 날은 없음
        assertTrue(AlertRules.goals(listOf(summary), today, on.copy(goalDays = setOf(30), goalOverdue = false), emptySet()).isEmpty())
    }

    @Test
    fun closeDateNeedsTimeAndOpenDay() {
        val cal = MarketCalendar()
        val kr = CloseSetting(true, CloseBasis.REGULAR, LocalTime.of(15, 40))
        assertNull(AlertRules.closeDate(MarketGroup.KR, kr, ZonedDateTime.parse("2026-10-14T15:39:00+09:00"), cal))
        assertEquals(today, AlertRules.closeDate(MarketGroup.KR, kr, ZonedDateTime.parse("2026-10-14T15:40:00+09:00"), cal))
        assertNull(AlertRules.closeDate(MarketGroup.KR, kr, ZonedDateTime.parse("2026-10-17T16:00:00+09:00"), cal)) // 토요일
        val us = CloseSetting(true, CloseBasis.REGULAR, LocalTime.of(7, 0))
        // 목요일 아침 = 수요일 미국 거래일
        assertEquals(today, AlertRules.closeDate(MarketGroup.US, us, ZonedDateTime.parse("2026-10-15T07:00:00+09:00"), cal))
        // 추수감사절(11/26) 다음 날 아침은 없음, 월요일 아침(일요일)도 없음
        assertNull(AlertRules.closeDate(MarketGroup.US, us, ZonedDateTime.parse("2026-11-27T07:00:00+09:00"), cal))
        assertNull(AlertRules.closeDate(MarketGroup.US, us, ZonedDateTime.parse("2026-10-19T07:00:00+09:00"), cal))
        assertNull(AlertRules.closeDate(MarketGroup.US, us.copy(on = false), ZonedDateTime.parse("2026-10-15T07:00:00+09:00"), cal))
    }

    @Test
    fun closeSummaryRegularAndExtended() {
        // 미국 일봉은 이미 다음 날로 바뀌었고, 끝난 일봉은 직전 일봉에 남아 있다
        val snap = snapshot(usCandle = Candle(today.plusDays(1), 520.0, 521.0), usPrev = Candle(today, 500.0, 520.0))
        val s = summary(snap)
        val krRegular = AlertRules.closeResult(MarketGroup.KR, today, CloseBasis.REGULAR, s, snap)!!
        assertEquals(12 * 5_000.0, krRegular.changeKrw, 1e-6)
        assertEquals(5_000.0 / 70_000, krRegular.rate!!, 1e-12)
        assertEquals(12 * 76_000.0 - 12 * 1_000.0, krRegular.valueKrw, 1e-6)
        assertEquals(12 * 6_000.0, AlertRules.closeResult(MarketGroup.KR, today, CloseBasis.EXTENDED, s, snap)!!.changeKrw, 1e-6)
        val us = AlertRules.closeResult(MarketGroup.US, today, CloseBasis.REGULAR, s, snap, regularCloses = mapOf("US_VOO" to 518.0))!!
        assertEquals(2 * 18 * 1_400.0, us.changeKrw, 1e-6)
        assertNull(AlertRules.closeResult(MarketGroup.US, today.minusDays(1), CloseBasis.REGULAR, s, snap))

        val alert = AlertRules.closeAlert(MarketGroup.US, today, CloseBasis.REGULAR, us, fxEffectKrw = 11_000.0)
        assertEquals("미국 마감 · 내 종목 +5만원 ▲3.60%", alert.title)
        assertEquals("▲ VOO +3.60%\n미국 평가금 145만원 · 10/14(수) 정규장 마감 기준 · 환율 1,400원 · 환율 효과 +1.1만원", alert.body)
        assertEquals("CLOSE:US:2026-10-14", alert.key)
    }

    @Test
    fun quietDigestAndWake() {
        assertTrue(AlertRules.isQuiet(LocalTime.of(23, 30), on))
        assertTrue(AlertRules.isQuiet(LocalTime.of(6, 59), on))
        assertFalse(AlertRules.isQuiet(LocalTime.of(7, 0), on))
        assertFalse(AlertRules.isQuiet(LocalTime.of(23, 30), on.copy(quietOn = false)))
        assertTrue(AlertRules.isQuiet(LocalTime.of(13, 0), on.copy(quietFrom = LocalTime.of(12, 0), quietTo = LocalTime.of(14, 0))))

        val pending = (1..7).map { com.mymoneytracker.core.alerts.Alert("k$it", com.mymoneytracker.core.alerts.AlertChannel.MOVE, "제목$it", "", AlertTarget.TODAY) }
        val digest = AlertRules.digest(pending, today)!!
        assertEquals("밤사이 알림 7건", digest.title)
        assertEquals("제목1\n제목2\n제목3\n제목4\n제목5\n외 2건", digest.body)
        assertEquals(pending.first(), AlertRules.digest(pending.take(1), today))
        assertNull(AlertRules.digest(emptyList(), today))

        // 다음 깨울 때: 07:00(밤 모으기 끝) → 07:00(미국 정리) · 08:30 · 09:00 · 15:40
        assertEquals(ZonedDateTime.parse("2026-10-15T07:00+09:00[Asia/Seoul]"), AlertRules.nextWake(night, on))
        assertEquals(ZonedDateTime.parse("2026-10-15T15:40+09:00[Asia/Seoul]"), AlertRules.nextWake(ZonedDateTime.parse("2026-10-15T09:00:00+09:00"), on))
        assertNull(AlertRules.nextWake(night, on.copy(enabled = false)))

        assertTrue(AlertRules.dailyDue(LocalDateTime.of(today, LocalTime.of(8, 30)), AlertRules.ALLOCATION_TIME, null))
        assertFalse(AlertRules.dailyDue(LocalDateTime.of(today, LocalTime.of(8, 29)), AlertRules.ALLOCATION_TIME, null))
        assertFalse(AlertRules.dailyDue(LocalDateTime.of(today, LocalTime.of(9, 0)), AlertRules.ALLOCATION_TIME, today))
    }

    @Test
    fun closeTimesClamp() {
        assertEquals(LocalTime.of(20, 10), CloseTimes.clamp(MarketGroup.KR, CloseBasis.EXTENDED, LocalTime.of(15, 40)))
        assertEquals(LocalTime.of(16, 0), CloseTimes.clamp(MarketGroup.KR, CloseBasis.REGULAR, LocalTime.of(16, 0)))
        assertEquals(LocalTime.of(6, 10), CloseTimes.clamp(MarketGroup.US, CloseBasis.REGULAR, LocalTime.of(5, 10)))
        assertEquals(LocalTime.of(10, 10), CloseTimes.clamp(MarketGroup.US, CloseBasis.EXTENDED, LocalTime.of(7, 0)))
        assertEquals(CloseTimes.LATEST, CloseTimes.clamp(MarketGroup.US, CloseBasis.REGULAR, LocalTime.of(23, 59)))
    }
}
