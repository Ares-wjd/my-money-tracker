package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.TradeImport.Status
import java.time.LocalDate
import kotlin.math.abs

/**
 * 표로 일괄 입력 — 입출금. 칸: 날짜 / 입출금 / 금액 (원화).
 * 금액 부호로 구분 (양수 = 입금, 음수 = 출금). 입출금 칸은 비워도 되고, 적었으면 부호와 맞아야 한다.
 * 붙여넣기·칸 읽기는 [TradeImport] 와 같다.
 */
object CashFlowImport {
    val HEADERS = listOf("날짜", "입출금", "금액")
    const val COLUMNS = 3
    const val DATE = 0
    const val SIDE = 1
    const val AMOUNT = 2

    data class Row(
        val index: Int,
        val status: Status,
        val date: LocalDate? = null,
        val type: RecordType? = null,
        /** 원화 금액 (양수) */
        val amount: Double = 0.0,
        val error: String? = null,
    )

    fun parseSide(text: String): RecordType? {
        val t = text.trim()
        return when {
            t.contains("입금") -> RecordType.DEPOSIT
            t.contains("출금") -> RecordType.WITHDRAW
            else -> null
        }
    }

    /** 표를 읽는다. [records] 는 이 계좌의 기록 (중복 확인용). */
    fun analyze(grid: List<List<String>>, accountId: String, records: List<Record>): List<Row> = grid.mapIndexed { index, raw ->
        val cells = List(COLUMNS) { raw.getOrNull(it)?.trim().orEmpty() }
        if (cells.all { it.isEmpty() }) return@mapIndexed Row(index, Status.EMPTY)
        val date = TradeImport.parseDate(cells[DATE])
        val side = parseSide(cells[SIDE])
        val signed = TradeImport.parseNumber(cells[AMOUNT])
        val error = when {
            date == null -> "날짜를 읽지 못함"
            cells[SIDE].isNotEmpty() && side == null -> "입출금은 입금·출금 (비워도 됨)"
            signed == null || signed == 0.0 -> "금액 확인 (입금 +, 출금 −)"
            side == RecordType.DEPOSIT && signed < 0 -> "입금인데 금액이 음수"
            side == RecordType.WITHDRAW && signed > 0 -> "출금은 금액을 음수로 (예: -300000)"
            else -> null
        }
        if (error != null) return@mapIndexed Row(index, Status.ERROR, error = error)
        val type = if (signed!! > 0) RecordType.DEPOSIT else RecordType.WITHDRAW
        val amount = abs(signed)
        // 중복 의심: 같은 날·같은 구분·같은 금액의 원화 입출금이 이미 있음
        val duplicate = records.any {
            it.accountId == accountId && it.type == type && it.date == date && it.currency == Currency.KRW && abs(it.amount - amount) < 0.5
        }
        Row(index, if (duplicate) Status.DUPLICATE else Status.OK, date, type, amount)
    }

    /** 저장할 줄 → 입출금 기록 (원화, 투자금 = 금액). */
    fun build(rows: List<Row>, accountId: String): List<Record> = rows.filter { it.status == Status.OK }.map { row ->
        Record(
            accountId = accountId,
            type = row.type!!,
            date = row.date!!,
            currency = Currency.KRW,
            amount = row.amount,
            krwAmount = row.amount,
        )
    }
}

/**
 * 표로 일괄 입력 — 배당. 칸: 날짜 / 종목코드 / 종목명 / 원화배당금 / 외화배당금 (세후 실수령액).
 * 종목은 이 계좌의 **종목코드**로만 찾는다 (종목명은 확인용 — 앱의 종목명과 다르면 경고만).
 * 배당은 종목 통화로 저장하므로 국내 종목은 원화배당금, 해외 종목은 외화배당금 칸을 쓴다.
 */
object DividendImport {
    val HEADERS = listOf("날짜", "종목코드", "종목명", "원화배당금", "외화배당금")
    const val COLUMNS = 5
    const val DATE = 0
    const val CODE = 1
    const val NAME = 2
    const val KRW = 3
    const val USD = 4

    data class Row(
        val index: Int,
        val status: Status,
        val date: LocalDate? = null,
        val holding: Holding? = null,
        /** 종목 통화 금액 */
        val amount: Double = 0.0,
        /** 저장은 하지만 확인할 것 (종목명이 다름 등) */
        val warning: String? = null,
        val error: String? = null,
    )

    private fun normalize(name: String) = name.replace(" ", "").uppercase()

    /** 표를 읽는다. [accountHoldings] 이 계좌의 종목, [records] 이 계좌의 기록 (중복 확인용). */
    fun analyze(grid: List<List<String>>, accountHoldings: List<Holding>, records: List<Record>): List<Row> = grid.mapIndexed { index, raw ->
        val cells = List(COLUMNS) { raw.getOrNull(it)?.trim().orEmpty() }
        if (cells.all { it.isEmpty() }) return@mapIndexed Row(index, Status.EMPTY)
        val date = TradeImport.parseDate(cells[DATE])
        val code = cells[CODE]
        val holding = accountHoldings.firstOrNull { code.isNotEmpty() && it.code.equals(code, ignoreCase = true) }
        val foreign = holding?.currency == Currency.USD
        val column = if (foreign) USD else KRW
        val other = if (foreign) KRW else USD
        val amount = TradeImport.parseNumber(cells[column])
        val error = when {
            date == null -> "날짜를 읽지 못함"
            code.isEmpty() -> "종목코드 필요"
            holding == null -> "이 계좌에 없는 종목코드"
            cells[column].isEmpty() -> if (foreign) "해외 종목은 외화배당금을 넣으세요" else "국내 종목은 원화배당금을 넣으세요"
            amount == null || amount <= 0 -> "배당금 확인"
            else -> null
        }
        if (error != null) return@mapIndexed Row(index, Status.ERROR, error = error)
        val name = cells[NAME]
        val warning = listOfNotNull(
            if (name.isNotEmpty() && normalize(name) != normalize(holding!!.name)) "확인: 앱 종목명은 '${holding.name}'" else null,
            if (cells[other].isNotEmpty()) (if (foreign) "원화배당금 칸은 쓰지 않음" else "외화배당금 칸은 쓰지 않음") else null,
        ).joinToString(" · ").ifEmpty { null }
        // 중복 의심: 같은 날·같은 종목·같은 금액의 배당이 이미 있음
        val duplicate = records.any {
            it.type == RecordType.DIVIDEND && it.holdingId == holding!!.id && it.date == date && abs(it.amount - amount!!) < 1e-6
        }
        Row(index, if (duplicate) Status.DUPLICATE else Status.OK, date, holding, amount!!, warning)
    }

    /** 저장할 줄 → 배당 기록 (종목 통화, 세후). */
    fun build(rows: List<Row>, accountId: String): List<Record> = rows.filter { it.status == Status.OK }.map { row ->
        Record(
            accountId = accountId,
            type = RecordType.DIVIDEND,
            date = row.date!!,
            currency = row.holding!!.currency,
            holdingId = row.holding.id,
            amount = row.amount,
        )
    }
}
