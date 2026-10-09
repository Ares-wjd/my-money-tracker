package com.mymoneytracker.app.ui.records

import com.mymoneytracker.app.ui.PortfolioData
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType

/** 기록 목록에 보여줄 제목. [accountId] 는 지금 보고 있는 계좌 (이체 방향 표시용). */
fun recordTitle(record: Record, data: PortfolioData, accountId: String): String {
    val holdingName = record.holdingId?.let { data.holding(it)?.name } ?: "삭제된 종목"
    return when (record.type) {
        RecordType.BUY -> if (record.initial) "초기 보유 · $holdingName" else "매수 · $holdingName"
        RecordType.SELL -> "매도 · $holdingName"
        RecordType.DIVIDEND -> "배당 · $holdingName"
        RecordType.TRANSFER -> if (record.accountId == accountId) {
            "이체 → ${record.toAccountId?.let { data.account(it)?.name } ?: "삭제된 계좌"}"
        } else {
            "이체 ← ${data.account(record.accountId)?.name ?: "삭제된 계좌"}"
        }
        RecordType.EXCHANGE -> if (record.currency == Currency.KRW) "환전 원화 → 달러" else "환전 달러 → 원화"
        RecordType.CASH_ADJUST -> "예수금 조정 (예전 기록, 계산 안 함)"
        RecordType.CASH_BALANCE -> "예수금 (${record.currency.label})"
        else -> record.type.label
    }
}

/** 계좌 입장에서의 금액 변화 표시. */
fun recordAmountText(record: Record, data: PortfolioData, accountId: String): String {
    val holdingCurrency = record.holdingId?.let { data.holding(it)?.currency } ?: Currency.KRW
    return when (record.type) {
        RecordType.DEPOSIT -> MoneyFormat.signedAmount(record.currency, record.amount)
        RecordType.WITHDRAW -> MoneyFormat.signedAmount(record.currency, -record.amount)
        RecordType.TRANSFER -> {
            val sign = if (record.accountId == accountId) -1 else 1
            MoneyFormat.signedAmount(record.currency, sign * record.amount)
        }
        RecordType.EXCHANGE -> if (record.currency == Currency.KRW) {
            "${MoneyFormat.signedWon(-record.krwAmount)} / ${MoneyFormat.signedUsd(record.amount)}"
        } else {
            "${MoneyFormat.signedUsd(-record.amount)} / ${MoneyFormat.signedWon(record.krwAmount)}"
        }
        RecordType.BUY -> MoneyFormat.signedAmount(holdingCurrency, -(record.quantity * record.price + record.fee + record.tax))
        RecordType.SELL -> MoneyFormat.signedAmount(holdingCurrency, record.quantity * record.price - record.fee - record.tax)
        RecordType.DIVIDEND -> MoneyFormat.signedAmount(holdingCurrency, record.amount)
        RecordType.CASH_ADJUST -> MoneyFormat.signedAmount(record.currency, record.amount)
        RecordType.CASH_BALANCE -> MoneyFormat.amount(record.currency, record.amount)
    }
}

/** 매매 기록의 부가 설명 (수량 × 단가). */
fun recordDetailText(record: Record, data: PortfolioData): String? {
    val holdingCurrency = record.holdingId?.let { data.holding(it)?.currency } ?: Currency.KRW
    return when (record.type) {
        RecordType.BUY, RecordType.SELL ->
            "${MoneyFormat.decimal(record.quantity)}주 × ${MoneyFormat.amount(holdingCurrency, record.price)}"
        else -> record.memo.ifBlank { null }
    }
}
