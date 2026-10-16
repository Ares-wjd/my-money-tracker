package com.mymoneytracker.core

import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.TradeImport
import com.mymoneytracker.core.portfolio.TradeImport.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TradeImportTest {
    private val samsung = Holding(id = "h1", accountId = "a", name = "삼성전자", code = "005930", market = Market.KR)
    private val fund = Holding(id = "h2", accountId = "a", name = "TDF 2045", code = "", market = Market.KR, assetType = AssetType.FUND)
    private val otherVoo = Holding(id = "h3", accountId = "b", name = "VOO", code = "VOO", market = Market.AMEX, assetType = AssetType.ETF)
    private val holdings = listOf(samsung, fund)
    private val all = holdings + otherVoo
    private val buy = Record(id = "r1", accountId = "a", type = RecordType.BUY, date = LocalDate.of(2026, 10, 1), holdingId = "h1", quantity = 10.0, price = 70000.0)

    private fun grid(vararg rows: String) = rows.map { it.split('\t') }

    @Test
    fun parsesCells() {
        assertEquals(LocalDate.of(2026, 10, 12), TradeImport.parseDate("2026-10-12"))
        assertEquals(LocalDate.of(2026, 10, 2), TradeImport.parseDate("2026. 10. 2"))
        assertEquals(LocalDate.of(2026, 10, 2), TradeImport.parseDate("2026.10.02"))
        assertEquals(LocalDate.of(2026, 1, 5), TradeImport.parseDate("2026/1/5"))
        assertEquals(LocalDate.of(2026, 10, 12), TradeImport.parseDate("20261012"))
        assertNull(TradeImport.parseDate("2026-13-01"))
        assertEquals(RecordType.BUY, TradeImport.parseSide("현금매수"))
        assertEquals(RecordType.SELL, TradeImport.parseSide("매도"))
        assertNull(TradeImport.parseSide("배당"))
        assertEquals(10160.0, TradeImport.parseNumber("10,160원")!!, 1e-9)
        assertEquals(12.5, TradeImport.parseNumber("$12.50")!!, 1e-9)
        assertNull(TradeImport.parseNumber(""))
        assertEquals(-3.0, TradeImport.parseNumber("-3")!!, 1e-9)
        assertEquals(-3.0, TradeImport.parseNumber("−3")!!, 1e-9)
        assertEquals(-1200.0, TradeImport.parseNumber("(1,200)")!!, 1e-9)
        assertNull(TradeImport.parseNumber("NaN"))
    }

    @Test
    fun pasteFillsFromCellAndGrows() {
        var g = TradeImport.emptyGrid(2)
        // 1~2열 3줄 (줄이 모자라 늘어남), 제목 줄은 건너뜀
        g = TradeImport.paste(g, 0, 0, "날짜\t매매구분\n2026-10-01\t매수\n2026-10-02\t매도\n2026-10-03\t매수\n")
        assertEquals(3, g.size)
        assertEquals("2026-10-02", g[1][0])
        // 3~6열을 따로 (첫 줄부터)
        g = TradeImport.paste(g, 0, 2, "삼성전자\t005930\t70,000\t10")
        assertEquals("005930", g[0][3])
        assertEquals("10", g[0][5])
        // 오른쪽으로 넘치는 칸은 버림
        g = TradeImport.paste(g, 2, 5, "1\t0\t넘침")
        assertEquals("0", g[2][6])
        assertEquals(7, g[2].size)
        // 아래로 이어 붙이기
        g = TradeImport.paste(g, 3, 0, "2026-10-04\t매수\r\n2026-10-05\t매수")
        assertEquals(5, g.size)
        assertEquals("2026-10-05", g[4][0])
    }

    @Test
    fun headerRowDetection() {
        assertTrue(TradeImport.isHeaderRow(TradeImport.HEADERS))
        assertTrue(TradeImport.isHeaderRow(listOf("일자", "구분", "종목명")))
        assertFalse(TradeImport.isHeaderRow(listOf("2026-10-01", "매수", "삼성전자")))
    }

    @Test
    fun analyzeMatchesExistingAndNewHoldings() {
        val a = TradeImport.analyze(
            grid(
                "2026-10-02\t매수\t삼성전자\t005930\t71,000\t5\t150",
                "2026-10-02\t매수\tTDF 2045\t\t1,000\t3\t",
                "2026-10-03\t매수\tVanguard S&P500\tvoo\t500.5\t2\t1.2",
                "2026-10-03\t매수\tKODEX 200\t069500\t35,000\t4\t",
                "2026-10-04\t매수\t금\tM04020000\t150,000\t10\t",
                "\t\t\t\t\t\t",
            ),
            "a", holdings, all, listOf(buy),
        )
        val rows = a.rows
        assertEquals(Status.OK, rows[0].status)
        assertEquals("h1", rows[0].holding?.id)
        assertEquals(150.0, rows[0].cost, 1e-9)
        assertEquals("h2", rows[1].holding?.id) // 코드 없으면 이름으로
        assertEquals(0.0, rows[1].cost, 1e-9)
        assertEquals("VOO", rows[2].newKey)
        assertEquals(Status.EMPTY, rows[5].status)
        assertEquals(5, a.saveCount)
        val voo = a.newHoldings.first { it.key == "VOO" }
        assertEquals(Market.AMEX, voo.market) // 다른 계좌의 같은 코드
        assertEquals(AssetType.ETF, voo.assetType)
        val kodex = a.newHoldings.first { it.key == "069500" }
        assertEquals(Market.KR, kodex.market)
        assertEquals(AssetType.ETF, kodex.assetType)
        assertEquals(AssetType.GOLD, a.newHoldings.first { it.key == "M04020000" }.assetType)
    }

    @Test
    fun newHoldingGuessesUsTicker() {
        assertEquals(Market.NASDAQ, TradeImport.guessMarket("AAPL", emptyList()))
        assertEquals(Market.KR, TradeImport.guessMarket("0091P0", emptyList()))
        assertEquals(Market.KR, TradeImport.guessMarket("", emptyList()))
    }

    @Test
    fun errorsDuplicatesAndOversell() {
        val a = TradeImport.analyze(
            grid(
                "10/01\t매수\t삼성전자\t005930\t70000\t1\t",
                "2026-10-01\t배당\t삼성전자\t005930\t70000\t1\t",
                "2026-10-01\t매수\t\t\t70000\t1\t",
                "2026-10-01\t매수\t삼성전자\t005930\t70000\t0\t",
                "2026-10-01\t매수\t삼성전자\t005930\t70,000\t10\t",
                "2026-10-05\t매도\t삼성전자\t005930\t80000\t-11\t",
                "2026-10-05\t\t삼성전자\t005930\t80000\t-10\t",
            ),
            "a", holdings, all, listOf(buy),
        )
        val r = a.rows
        assertEquals("날짜를 읽지 못함", r[0].error)
        assertEquals("매매구분은 매수·매도 (비워도 됨)", r[1].error)
        assertEquals("종목명이나 종목코드 필요", r[2].error)
        assertEquals("수량 확인 (매수 +, 매도 −)", r[3].error)
        assertEquals(Status.DUPLICATE, r[4].status) // 이미 있는 기록과 같음
        // 매도는 계산 순서로 그 줄 시점 보유 수량이 모자라면 경고만 하고 저장 (v2.8)
        assertEquals(Status.OK, r[5].status)
        assertTrue(r[5].warning!!.startsWith("이 줄 시점 보유 수량(10)")) // 10주 보유 → 11주 매도
        assertTrue(r[6].warning!!.startsWith("이 줄 시점 보유 수량(0)")) // 앞 줄이 다 팔아 남은 것이 없음
    }

    @Test
    fun sellWarningFollowsRowOrder() {
        // 웹 tradeImport.test.ts 와 같은 케이스: 같은 날 매도 줄이 매수 줄보다 위면 경고, 아래면 정상
        val sellFirst = TradeImport.analyze(
            grid("2026-10-07\t매도\t삼성전자\t005930\t80000\t-2\t", "2026-10-07\t매수\t삼성전자\t005930\t80000\t2\t"),
            "a", holdings, all, emptyList(),
        ).rows
        assertTrue(sellFirst[0].warning != null)
        assertEquals(Status.OK, sellFirst[0].status)
        assertEquals(null, sellFirst[1].warning)
        val buyFirst = TradeImport.analyze(
            grid("2026-10-07\t매수\t삼성전자\t005930\t80000\t2\t", "2026-10-07\t매도\t삼성전자\t005930\t80000\t-2\t"),
            "a", holdings, all, emptyList(),
        ).rows
        assertTrue(buyFirst.all { it.warning == null })
        // 표의 매도 때문에 뒤의 기존 매도가 계산되지 않게 되면 brokenSells
        val laterSell = buy.copy(id = "s9", type = RecordType.SELL, date = LocalDate.of(2026, 10, 20), quantity = 10.0, createdAt = 9)
        val broken = TradeImport.analyze(
            grid("2026-10-07\t매도\t삼성전자\t005930\t80000\t-5\t"),
            "a", holdings, all, listOf(buy, laterSell),
        )
        assertEquals(null, broken.rows[0].warning)
        assertEquals(listOf("s9"), broken.brokenSells.map { it.record.id })
    }

    @Test
    fun quantitySignDecidesAndChecksSide() {
        val a = TradeImport.analyze(
            grid(
                "2026-10-02\t\t삼성전자\t005930\t70000\t3\t", // 구분 없이 + → 매수
                "2026-10-03\t\t삼성전자\t005930\t70000\t-2\t", // 구분 없이 − → 매도
                "2026-10-03\t매수\t삼성전자\t005930\t70000\t-2\t", // 매수인데 −
                "2026-10-03\t매도\t삼성전자\t005930\t70000\t2\t", // 매도인데 +
                "2026-10-03\t배당\t삼성전자\t005930\t70000\t2\t",
            ),
            "a", holdings, all, listOf(buy),
        )
        val r = a.rows
        assertEquals(RecordType.BUY, r[0].type)
        assertEquals(3.0, r[0].quantity, 1e-9)
        assertEquals(RecordType.SELL, r[1].type)
        assertEquals(2.0, r[1].quantity, 1e-9) // 저장은 양수
        assertEquals("매수인데 수량이 음수", r[2].error)
        assertEquals("매도는 수량을 음수로 (예: -3)", r[3].error)
        assertEquals("매매구분은 매수·매도 (비워도 됨)", r[4].error)
    }

    @Test
    fun sellAfterBuyInSamePaste() {
        val a = TradeImport.analyze(
            grid(
                "2026-10-02\t매수\t애플\tAAPL\t200\t3\t",
                "2026-10-03\t\t애플\tAAPL\t210\t-2\t",
            ),
            "a", holdings, all, emptyList(),
        )
        assertEquals(2, a.saveCount)
        val records = TradeImport.build(a.rows, "a") { key -> if (key == "AAPL") "new1" else null }
        assertEquals(listOf("new1", "new1"), records.map { it.second.holdingId })
        assertEquals(RecordType.SELL, records[1].second.type)
        assertEquals("", records[0].second.id)
    }
}
