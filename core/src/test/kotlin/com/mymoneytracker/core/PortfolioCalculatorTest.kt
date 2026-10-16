package com.mymoneytracker.core

import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.PricePoint
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.mymoneytracker.core.model.CostInput
import org.junit.Test
import java.time.LocalDate

class PortfolioCalculatorTest {
    private val day1 = LocalDate.of(2026, 1, 2)
    private val day2 = LocalDate.of(2026, 2, 2)
    private val day3 = LocalDate.of(2026, 3, 2)

    private val kr = InvestmentAccount(id = "kr", name = "국내")
    private val us = InvestmentAccount(id = "us", name = "해외")
    private val samsung = Holding(id = "s", accountId = "kr", name = "삼성전자", code = "005930", market = Market.KR, manualPrice = 80_000.0)
    private val apple = Holding(id = "a", accountId = "us", name = "Apple", code = "AAPL", market = Market.NASDAQ, manualPrice = 200.0)

    private var seq = 0L
    private fun rec(
        account: String,
        type: RecordType,
        date: LocalDate = day1,
        currency: Currency = Currency.KRW,
        amount: Double = 0.0,
        krw: Double = 0.0,
        to: String? = null,
        holding: String? = null,
        qty: Double = 0.0,
        price: Double = 0.0,
        fee: Double = 0.0,
        tax: Double = 0.0,
    ) = Record(
        id = "r${seq}", accountId = account, type = type, date = date, currency = currency, amount = amount,
        krwAmount = krw, toAccountId = to, holdingId = holding, quantity = qty, price = price, fee = fee, tax = tax,
        createdAt = seq++,
    )

    @Test
    fun cumulativeInvestedPerRecord() {
        // 웹 portfolio.test.ts 와 같은 케이스: 입금 100만 → 이체 30만 보냄 → 출금 10만 (같은 날 입금 5만이 먼저 입력됨) → 매수는 제외
        val records = listOf(
            Record(id = "d1", accountId = "kr", type = RecordType.DEPOSIT, date = day1, amount = 1_000_000.0, krwAmount = 1_000_000.0, createdAt = 1),
            Record(id = "t1", accountId = "kr", type = RecordType.TRANSFER, date = day2, toAccountId = "us", amount = 300_000.0, krwAmount = 300_000.0, createdAt = 2),
            Record(id = "w1", accountId = "kr", type = RecordType.WITHDRAW, date = day3, amount = 100_000.0, krwAmount = 100_000.0, createdAt = 4),
            Record(id = "d2", accountId = "kr", type = RecordType.DEPOSIT, date = day3, amount = 50_000.0, krwAmount = 50_000.0, createdAt = 3),
            Record(id = "b1", accountId = "kr", type = RecordType.BUY, date = day3, holdingId = "s", quantity = 1.0, price = 80_000.0, createdAt = 5),
        )
        val kr = PortfolioCalculator.cumulativeInvested("kr", records)
        assertEquals(mapOf("d1" to 1_000_000.0, "t1" to 700_000.0, "d2" to 750_000.0, "w1" to 650_000.0), kr)
        // 받은 계좌는 이체가 +
        assertEquals(mapOf("t1" to 300_000.0), PortfolioCalculator.cumulativeInvested("us", records))
    }

    @Test
    fun shortSellsInCalculationOrder() {
        // 웹 portfolio.test.ts 와 같은 케이스
        val d = LocalDate.of(2026, 10, 7)
        fun trade(id: String, type: RecordType, qty: Double, created: Long, date: LocalDate = d) =
            Record(id = id, accountId = "a", type = type, date = date, holdingId = "s", quantity = qty, price = 100.0, createdAt = created)
        val r = PortfolioCalculator
        // 같은 날 매도(먼저 입력) → 매수: 매도 시점 보유 0주
        val sellFirst = listOf(trade("s1", RecordType.SELL, 2.0, 1), trade("b1", RecordType.BUY, 2.0, 2))
        assertEquals(listOf("s1" to 0.0), r.shortSells(sellFirst).map { it.record.id to it.held })
        // 매수 → 매도: 정상
        assertTrue(r.shortSells(listOf(trade("b1", RecordType.BUY, 2.0, 1), trade("s1", RecordType.SELL, 2.0, 2))).isEmpty())
        // 전날 2주 보유 + 같은 날 매도 → 매수: 매도 시점 2주라 정상
        val held = listOf(trade("b0", RecordType.BUY, 2.0, 1, d.minusDays(1))) + sellFirst.map { it.copy(createdAt = it.createdAt + 1) }
        assertTrue(r.shortSells(held).isEmpty())

        // 단건 입력: 새 매도는 그날 맨 뒤 → 같은 날 매수 뒤에 들어간다
        val newSell = trade("", RecordType.SELL, 2.0, 0)
        assertEquals(2.0, r.quantityBefore("s", listOf(trade("b1", RecordType.BUY, 2.0, 5)), newSell), 1e-9)
        // 수정: 먼저 입력된 매도는 같은 날 뒤 매수 앞 (자기 자신의 수정 전 값은 뺌)
        assertEquals(0.0, r.quantityBefore("s", sellFirst, sellFirst[0].copy(quantity = 1.0)), 1e-9)

        // 매수를 지우거나 매도 뒤로 옮기면 그 매도가 새로 계산되지 않음
        val ok = listOf(trade("b1", RecordType.BUY, 2.0, 1), trade("s1", RecordType.SELL, 2.0, 2, d.plusDays(1)))
        assertEquals(listOf("s1"), r.newlyShortSells(ok, ok[0], null).map { it.record.id })
        assertEquals(listOf("s1"), r.newlyShortSells(ok, ok[0], ok[0].copy(date = d.plusDays(2))).map { it.record.id })
        // 이미 계산되지 않던 매도는 다시 알리지 않고, 저장하는 기록 자신도 빼고 본다
        assertTrue(r.newlyShortSells(sellFirst, sellFirst[1], sellFirst[1].copy(quantity = 1.0)).isEmpty())
        assertTrue(r.newlyShortSells(ok, ok[1], ok[1].copy(quantity = 5.0)).isEmpty())
    }

    @Test
    fun noRateReason() {
        // 웹 portfolio.test.ts 와 같은 케이스
        val deposit = Record(id = "d", accountId = "a", type = RecordType.DEPOSIT, date = day1, amount = 100.0, krwAmount = 100.0, createdAt = 1)
        val outAll = Record(id = "t", accountId = "a", type = RecordType.TRANSFER, date = day2, toAccountId = "b", amount = 100.0, krwAmount = 100.0, createdAt = 2)
        val outMore = outAll.copy(id = "t2", amount = 150.0, krwAmount = 150.0)
        val r = PortfolioCalculator
        assertNull(r.noRateReason("a", 100.0, listOf(deposit)))
        assertEquals(PortfolioCalculator.NoRateReason.NO_DEPOSIT, r.noRateReason("a", 0.0, emptyList()))
        assertEquals(PortfolioCalculator.NoRateReason.BALANCED, r.noRateReason("a", 0.0, listOf(deposit, outAll)))
        assertEquals(PortfolioCalculator.NoRateReason.NEGATIVE, r.noRateReason("a", -50.0, listOf(deposit, outMore)))
        // 다른 계좌에서 받은 이체만 있어도 들어온 기록 (받은 계좌 b 는 이체가 +)
        assertEquals(PortfolioCalculator.NoRateReason.BALANCED, r.noRateReason("b", 0.0, listOf(outAll)))
        // 나가기만 했으면 들어온 기록 없음
        assertEquals(PortfolioCalculator.NoRateReason.NO_DEPOSIT, r.noRateReason("a", -100.0, listOf(outAll)))
    }

    @Test
    fun movingAverageAndRealizedProfit() {
        val records = listOf(
            rec("kr", RecordType.BUY, day1, holding = "s", qty = 10.0, price = 70_000.0, fee = 100.0),
            rec("kr", RecordType.BUY, day2, holding = "s", qty = 10.0, price = 80_000.0, fee = 100.0),
            rec("kr", RecordType.SELL, day3, holding = "s", qty = 5.0, price = 90_000.0, fee = 100.0, tax = 900.0),
        )
        val p = PortfolioCalculator.position(samsung, records)
        assertEquals(15.0, p.quantity, 1e-9)
        assertEquals(75_000.0, p.averagePrice, 1e-9)
        // (90,000 - 75,000) × 5 - 100 - 900
        assertEquals(74_000.0, p.realizedProfit, 1e-9)
        assertEquals(1_200.0, p.feesAndTaxes, 1e-9)
    }

    @Test
    fun costTotalIsUsedWhenGiven() {
        // 제비용합만 쓴 기록 (수수료·세금 0) 과 예전 기록 (제비용합 없음 → 수수료 + 세금)
        val records = listOf(
            rec("kr", RecordType.BUY, day1, holding = "s", qty = 10.0, price = 70_000.0).copy(cost = 300.0),
            rec("kr", RecordType.SELL, day2, holding = "s", qty = 5.0, price = 80_000.0, fee = 100.0, tax = 900.0),
        )
        val p = PortfolioCalculator.position(samsung, records)
        assertEquals(10_000.0 * 5 - 1_000.0, p.realizedProfit, 1e-9)
        assertEquals(1_300.0, p.feesAndTaxes, 1e-9)
    }

    @Test
    fun costInputModes() {
        val base = rec("kr", RecordType.BUY, day1, holding = "s", qty = 1.0, price = 1.0)
        // 수수료·세금을 쓰면 제비용합은 합, 제비용합 칸은 잠김
        val parts = CostInput(total = 999.0, fee = 100.0, tax = null)
        assertTrue(parts.totalLocked)
        assertEquals(100.0, parts.effectiveTotal, 1e-9)
        val r1 = parts.applyTo(base)
        assertEquals(100.0, r1.fee, 1e-9)
        assertEquals(100.0, r1.cost!!, 1e-9)
        // 제비용합만 쓰면 수수료·세금 0, 두 칸은 잠김
        val total = CostInput(total = 1_500.0, fee = null, tax = null)
        assertTrue(total.partsLocked)
        val r2 = total.applyTo(base)
        assertEquals(0.0, r2.fee + r2.tax, 1e-9)
        assertEquals(1_500.0, r2.totalCost, 1e-9)
        // 다시 열 때: 수수료·세금이 있으면 나눠서, 없으면 제비용합으로
        assertEquals(CostInput(null, 100.0, null), CostInput.of(r1))
        assertEquals(CostInput(1_500.0, null, null), CostInput.of(r2))
        assertEquals(CostInput(null, null, null), CostInput.of(base))
        assertFalse(CostInput(null, null, null).partsLocked)
    }

    @Test
    fun sellingEverythingResetsAverage() {
        val records = listOf(
            rec("kr", RecordType.BUY, day1, holding = "s", qty = 2.0, price = 100.0),
            rec("kr", RecordType.SELL, day2, holding = "s", qty = 2.0, price = 150.0),
            rec("kr", RecordType.BUY, day3, holding = "s", qty = 1.0, price = 120.0),
        )
        val p = PortfolioCalculator.position(samsung, records)
        assertEquals(1.0, p.quantity, 1e-9)
        assertEquals(120.0, p.averagePrice, 1e-9)
        assertEquals(100.0, p.realizedProfit, 1e-9)
    }

    @Test
    fun accountCashInvestedAndReturns() {
        val records = listOf(
            rec("kr", RecordType.DEPOSIT, day1, amount = 1_000_000.0, krw = 1_000_000.0),
            rec("kr", RecordType.BUY, day1, holding = "s", qty = 10.0, price = 70_000.0, fee = 500.0),
            rec("kr", RecordType.DIVIDEND, day2, holding = "s", amount = 3_000.0),
            rec("kr", RecordType.CASH_BALANCE, day2, amount = 302_700.0), // 직접 입력한 예수금
        )
        val summary = PortfolioCalculator.summarize(listOf(kr), listOf(samsung), records, usdKrw = null)
        val a = summary.accounts.single()
        assertEquals(1_000_000.0, a.investedKrw, 1e-9)
        assertEquals(302_700.0, a.cash.krw, 1e-9)
        assertEquals(day2, a.cashDate)
        // 종목 800,000 + 예수금 302,700
        assertEquals(1_102_700.0, a.valueKrw, 1e-9)
        assertEquals(102_700.0, a.profitKrw, 1e-9)
        assertEquals(99_700.0, a.profitExDividendsKrw, 1e-9)
        assertEquals(0.1027, a.returnRate!!, 1e-9)
        assertEquals(0.0997, a.returnRateExDividends!!, 1e-9)

        val h = a.holdings.single()
        assertEquals(100_000.0, h.unrealizedProfit!!, 1e-9)
        assertEquals(100_000.0 / 700_000.0, h.returnRate!!, 1e-9)
        assertEquals(103_000.0 / 700_000.0, h.returnRateWithDividends!!, 1e-9)
        assertFalse(a.missingFx)
    }

    @Test
    fun transferBetweenAccounts() {
        val records = listOf(
            rec("kr", RecordType.DEPOSIT, day1, amount = 2_000_000.0, krw = 2_000_000.0),
            rec("kr", RecordType.TRANSFER, day1, amount = 1_000_000.0, krw = 1_000_000.0, to = "us"),
            rec("us", RecordType.EXCHANGE, day2, currency = Currency.KRW, amount = 700.0, krw = 980_000.0),
            rec("us", RecordType.BUY, day2, holding = "a", qty = 3.0, price = 180.0, fee = 1.0),
            rec("us", RecordType.DIVIDEND, day3, holding = "a", amount = 2.0),
            rec("us", RecordType.CASH_BALANCE, day3, currency = Currency.KRW, amount = 20_000.0),
            rec("us", RecordType.CASH_BALANCE, day3, currency = Currency.USD, amount = 161.0),
        )
        val summary = PortfolioCalculator.summarize(listOf(kr, us), listOf(samsung, apple), records, usdKrw = 1_400.0)
        val krSummary = summary.accounts.first { it.account.id == "kr" }
        val usSummary = summary.accounts.first { it.account.id == "us" }

        assertEquals(1_000_000.0, krSummary.investedKrw, 1e-9)
        assertEquals(1_000_000.0, usSummary.investedKrw, 1e-9)
        assertEquals(2_000_000.0, summary.investedKrw, 1e-9)

        assertEquals(20_000.0, usSummary.cash.krw, 1e-9)
        assertEquals(161.0, usSummary.cash.usd, 1e-9)
        // 받은 계좌의 예수금 기록은 보낸 계좌에 섞이지 않는다. 보낸 계좌는 입력값이 없어 기록으로 계산: 입금 200만 − 이체 100만
        assertEquals(1_000_000.0, krSummary.cash.krw, 1e-9)
        assertTrue(krSummary.cashDetails.getValue(Currency.KRW).estimated)
        // 종목 600$ × 1400 + 20,000 + 161$ × 1400
        assertEquals(840_000.0 + 20_000.0 + 225_400.0, usSummary.valueKrw, 1e-9)
        assertEquals(2.0 * 1_400.0, usSummary.dividendsKrw, 1e-9)
    }

    @Test
    fun missingFxAndPriceAreFlagged() {
        val noPrice = apple.copy(manualPrice = null)
        val records = listOf(rec("us", RecordType.BUY, day1, holding = "a", qty = 1.0, price = 100.0))
        val summary = PortfolioCalculator.summarize(listOf(us), listOf(noPrice), records, usdKrw = null)
        val a = summary.accounts.single()
        assertTrue(a.missingFx)
        assertTrue(a.missingPrice)
        assertNull(a.holdings.single().marketValueKrw)
    }

    @Test
    fun asOfIgnoresLaterRecords() {
        val records = listOf(
            rec("kr", RecordType.DEPOSIT, day1, amount = 100.0, krw = 100.0),
            rec("kr", RecordType.DEPOSIT, day3, amount = 50.0, krw = 50.0),
        )
        val summary = PortfolioCalculator.summarize(listOf(kr), emptyList(), records, null, asOf = day2)
        assertEquals(100.0, summary.investedKrw, 1e-9)
    }

    @Test
    fun quantityAtDateExcludesEditedRecord() {
        val buy = rec("kr", RecordType.BUY, day1, holding = "s", qty = 10.0, price = 1.0)
        val sell = rec("kr", RecordType.SELL, day2, holding = "s", qty = 4.0, price = 1.0)
        assertEquals(6.0, PortfolioCalculator.quantityAt(samsung, listOf(buy, sell), day3), 1e-9)
        assertEquals(10.0, PortfolioCalculator.quantityAt(samsung, listOf(buy, sell), day3, excludeRecordId = sell.id), 1e-9)
        assertEquals(0.0, PortfolioCalculator.quantityAt(samsung, listOf(buy, sell), day1.minusDays(1)), 1e-9)
    }

    @Test
    fun cashIsAnchorPlusLaterRecords() {
        val records = listOf(
            rec("kr", RecordType.CASH_BALANCE, day1, amount = 1_000_000.0), // 입력한 예수금 (기준점)
            rec("kr", RecordType.BUY, day2, holding = "s", qty = 3.0, price = 100_000.0, fee = 1_000.0),
            rec("kr", RecordType.DIVIDEND, day3, holding = "s", amount = 5_000.0),
            rec("kr", RecordType.EXCHANGE, day3, currency = Currency.KRW, amount = 100.0, krw = 140_000.0), // 원화 → 달러
            rec("kr", RecordType.BUY, day3, holding = "s", qty = 1.0, price = 50_000.0).copy(initial = true), // 초기 보유분은 빼지 않음
        )
        val at = { d: LocalDate? -> PortfolioCalculator.summarize(listOf(kr), listOf(samsung), records, 1_000.0, asOf = d).accounts.single() }
        assertEquals(1_000_000.0, at(day1).cash.krw, 1e-9)
        assertFalse(at(day1).cashDetails.getValue(Currency.KRW).estimated)
        // 기준점 + 매수 −301,000
        val d2 = at(day2).cashDetails.getValue(Currency.KRW)
        assertEquals(699_000.0, d2.amount, 1e-9)
        assertEquals(1_000_000.0, d2.anchor!!, 1e-9)
        assertEquals(-301_000.0, d2.change, 1e-9)
        assertTrue(d2.estimated)
        // + 배당 5,000 − 환전 140,000, 달러는 기준점 없이 기록만으로 +100
        val now = at(null)
        assertEquals(564_000.0, now.cash.krw, 1e-9)
        assertEquals(100.0, now.cash.usd, 1e-9)
        assertFalse(now.brokerLinked)
    }

    @Test
    fun brokerLinkedAccountUsesFetchedCashOnly() {
        val records = listOf(
            rec("kr", RecordType.BUY, day1, holding = "s", qty = 1.0, price = 70_000.0).copy(externalId = "KIS:1"),
            rec("kr", RecordType.CASH_BALANCE, day1, amount = 10_000.0).copy(externalId = "KIS:balance"),
            rec("kr", RecordType.BUY, day2, holding = "s", qty = 1.0, price = 70_000.0).copy(externalId = "KIS:2"),
        )
        val now = PortfolioCalculator.summarize(listOf(kr), listOf(samsung), records, null).accounts.single()
        assertTrue(now.brokerLinked)
        assertEquals(10_000.0, now.cash.krw, 1e-9) // 매수로 계산하지 않고 불러온 값 그대로
        assertFalse(now.cashDetails.getValue(Currency.KRW).estimated)
        val before = PortfolioCalculator.summarize(listOf(kr), listOf(samsung), records, null, asOf = day1.minusDays(1)).accounts.single()
        assertEquals(0.0, before.cash.krw, 1e-9) // 첫 기록 이전은 0
    }

    @Test
    fun cashBeforeFirstAnchorIsEstimatedBackward() {
        val records = listOf(
            rec("kr", RecordType.BUY, day1, holding = "s", qty = 10.0, price = 70_000.0),
            rec("kr", RecordType.CASH_BALANCE, day1, amount = 10_000.0),
            rec("kr", RecordType.CASH_BALANCE, day2, amount = 50_000.0),
            rec("kr", RecordType.CASH_BALANCE, day2, currency = Currency.USD, amount = 12.5),
            rec("kr", RecordType.CASH_ADJUST, day2, amount = 999.0), // 예전 버전 기록은 무시
            rec("kr", RecordType.CASH_BALANCE, day3, amount = 70_000.0),
        )
        val now = PortfolioCalculator.summarize(listOf(kr), listOf(samsung), records, 1_000.0).accounts.single()
        assertEquals(70_000.0, now.cash.krw, 1e-9)
        assertEquals(12.5, now.cash.usd, 1e-9)
        assertEquals(day3, now.cashDate)
        // 기준점 이후 기록이 없으면 입력한 값 그대로
        assertEquals(800_000.0 + 70_000.0 + 12_500.0, now.valueKrw, 1e-9)

        val past = PortfolioCalculator.summarize(listOf(kr), listOf(samsung), records, 1_000.0, asOf = day2).accounts.single()
        assertEquals(50_000.0, past.cash.krw, 1e-9)

        // 첫 입력 이전: 첫 입력값 10,000 − 그 사이 기록(매수 −700,000) = 710,000 (추정)
        val before = PortfolioCalculator.summarize(listOf(kr), listOf(samsung), records, null, asOf = day1.minusDays(1)).accounts.single()
        assertEquals(710_000.0, before.cash.krw, 1e-9)
        assertTrue(before.cashDetails.getValue(Currency.KRW).estimated)
    }

    @Test
    fun priceOverrideIsUsed() {
        val records = listOf(rec("kr", RecordType.BUY, day1, holding = "s", qty = 1.0, price = 1.0))
        val summary = PortfolioCalculator.summarize(
            listOf(kr), listOf(samsung), records, null,
            priceOf = { PricePoint(5.0, day1) },
        )
        assertEquals(5.0, summary.accounts.single().holdings.single().marketValue!!, 1e-9)
    }
}
