package com.mymoneytracker.core

import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.CashFlowImport
import com.mymoneytracker.core.portfolio.DividendImport
import com.mymoneytracker.core.portfolio.ExchangeImport
import com.mymoneytracker.core.portfolio.TradeImport
import com.mymoneytracker.core.portfolio.TradeImport.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RecordImportTest {
    private val d = { m: Int, day: Int -> LocalDate.of(2026, m, day) }
    private fun grid(vararg rows: String) = rows.map { it.split('\t') }

    @Test
    fun cashFlowSignAndSide() {
        val existing = Record(id = "r", accountId = "a", type = RecordType.DEPOSIT, date = d(10, 1), amount = 500_000.0, krwAmount = 500_000.0)
        val rows = CashFlowImport.analyze(
            grid(
                "2026-10-02\t\t1,000,000",
                "2026-10-03\t\t-300,000",
                "2026-10-03\t입금\t-300,000",
                "2026-10-03\t출금\t300,000",
                "2026-10-03\t이체\t300,000",
                "2026-10-03\t입금\t0",
                "2026-10-01\t입금\t500,000",
                "\t\t",
            ),
            "a", listOf(existing),
        )
        assertEquals(RecordType.DEPOSIT, rows[0].type)
        assertEquals(1_000_000.0, rows[0].amount, 1e-9)
        assertEquals(RecordType.WITHDRAW, rows[1].type)
        assertEquals(300_000.0, rows[1].amount, 1e-9)
        assertEquals("입금인데 금액이 음수", rows[2].error)
        assertEquals("출금은 금액을 음수로 (예: -300000)", rows[3].error)
        assertEquals("입출금은 입금·출금 (비워도 됨)", rows[4].error)
        assertEquals("금액 확인 (입금 +, 출금 −)", rows[5].error)
        assertEquals(Status.DUPLICATE, rows[6].status)
        assertEquals(Status.EMPTY, rows[7].status)
        val records = CashFlowImport.build(rows, "a")
        assertEquals(2, records.size)
        assertEquals(1_000_000.0, records[0].krwAmount, 1e-9)
        assertEquals(Currency.KRW, records[1].currency)
        // 제목 줄은 붙여넣기에서 건너뛴다
        val g = TradeImport.paste(TradeImport.emptyGrid(1, CashFlowImport.COLUMNS), 0, 0, "날짜\t입출금\t금액\n2026-10-02\t입금\t100", CashFlowImport.COLUMNS)
        assertEquals(listOf("2026-10-02", "입금", "100"), g[0])
    }

    @Test
    fun dividendsByCodeWithNameCheck() {
        val tiger = Holding(id = "t", accountId = "a", name = "TIGER 미국S&P500", code = "360750", market = Market.KR)
        val voo = Holding(id = "v", accountId = "a", name = "VOO", code = "VOO", market = Market.AMEX)
        val existing = Record(id = "r", accountId = "a", type = RecordType.DIVIDEND, date = d(9, 1), holdingId = "t", amount = 1_000.0)
        val rows = DividendImport.analyze(
            grid(
                "2026-10-02\t360750\tTIGER 미국 S&P500\t12,345\t",
                "2026-10-02\tvoo\t뱅가드\t\t1.84",
                "2026-10-02\t360750\t\t\t3",
                "2026-10-02\tVOO\tVOO\t2,500\t",
                "2026-10-02\t005930\t삼성전자\t1000\t",
                "2026-10-02\t\t삼성전자\t1000\t",
                "2026-10-02\t360750\t\t-5\t",
                "2026-09-01\t360750\t\t1,000\t",
                "2026-10-03\tVOO\t\t2,500\t2.1",
            ),
            listOf(tiger, voo), listOf(existing),
        )
        assertEquals(Status.OK, rows[0].status)
        assertEquals(12_345.0, rows[0].amount, 1e-9)
        assertNull(rows[0].warning) // 띄어쓰기 차이는 같은 이름
        assertEquals(1.84, rows[1].amount, 1e-9) // 해외 종목은 외화배당금
        assertEquals("확인: 앱 종목명은 'VOO'", rows[1].warning)
        assertEquals("국내 종목은 원화배당금을 넣으세요", rows[2].error)
        assertEquals("해외 종목은 외화배당금을 넣으세요", rows[3].error)
        assertEquals("이 계좌에 없는 종목코드", rows[4].error)
        assertEquals("종목코드 필요", rows[5].error)
        assertEquals("배당금 확인", rows[6].error)
        assertEquals(Status.DUPLICATE, rows[7].status)
        assertEquals("원화배당금 칸은 쓰지 않음", rows[8].warning)
        val records = DividendImport.build(rows, "a")
        assertEquals(listOf("t", "v", "v"), records.map { it.holdingId })
        assertEquals(Currency.USD, records[1].currency)
    }

    @Test
    fun exchangeCheckboxAndTwoOfThree() {
        // 웹 recordImport.test.ts 와 같은 케이스. 칸: 날짜 / 달러→원화 / 외화 / 환율 / 원화
        val existing = Record(id = "r", accountId = "a", type = RecordType.EXCHANGE, date = d(10, 1), currency = Currency.KRW, amount = 1_000.0, krwAmount = 1_394_500.0)
        val rows = ExchangeImport.analyze(
            grid(
                "2026-10-02		1,000	1,394.5	",
                "2026-10-03	1		1,390	1,000,000",
                "2026-10-04	TRUE	500		700,000",
                "2026-10-05		1000	1400	1,500,000",
                "2026-10-01		1,000		1,394,500",
                "2026-10-06		1,000		",
                "2026-10-06	뭐지	1,000	1,390	",
                "2026-10-06		-1,000	1,390	",
                "	1			",
            ),
            "a", listOf(existing),
        )
        // 원화 → 달러: 1,000달러 × 1,394.5 = 1,394,500원
        assertEquals(Status.OK, rows[0].status)
        assertEquals(false, rows[0].toKrw)
        assertEquals(1_394_500.0, rows[0].krw, 1e-9)
        // 달러 → 원화: 1,000,000원 ÷ 1,390 = 719.42달러
        assertEquals(true, rows[1].toKrw)
        assertEquals(719.42, rows[1].usd, 1e-9)
        // 붙여 넣은 TRUE 도 체크, 환율은 원화 ÷ 외화
        assertEquals(true, rows[2].toKrw)
        assertEquals(1_400.0, rows[2].rate, 1e-9)
        // 세 칸 모두: 1,500,000 ÷ 1,000 = 1,500 은 1,400 과 0.5% 넘게 달라 경고 (저장은 함)
        assertEquals(Status.OK, rows[3].status)
        assertEquals("확인: 원화÷외화 = 1,500", rows[3].warning)
        assertEquals(Status.DUPLICATE, rows[4].status)
        assertEquals("외화·환율·원화 중 두 칸 필요", rows[5].error)
        assertEquals("방향 칸 확인 (체크 = 달러→원화)", rows[6].error)
        assertEquals("외화·환율·원화는 0보다 크게", rows[7].error)
        assertEquals(Status.EMPTY, rows[8].status)
        val built = ExchangeImport.build(rows, "a")
        assertEquals(4, built.size)
        assertEquals(Currency.USD, built[1].currency)
        assertEquals(1_000_000.0, built[1].krwAmount, 1e-9)
        assertNull(rows[0].warning)
    }
}
