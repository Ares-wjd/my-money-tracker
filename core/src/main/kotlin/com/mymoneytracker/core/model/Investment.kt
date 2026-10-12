package com.mymoneytracker.core.model

import java.time.LocalDate

enum class Currency(val label: String) {
    KRW("원화"),
    USD("달러"),
}

/** 종목이 거래되는 시장. 통화와 (M3 의) 시세 조회 방식이 시장으로 정해진다. */
enum class Market(val label: String, val currency: Currency) {
    KR("국내", Currency.KRW),
    NASDAQ("나스닥", Currency.USD),
    NYSE("뉴욕", Currency.USD),
    AMEX("아멕스", Currency.USD),
    US_OTHER("미국 기타", Currency.USD),
}

enum class AssetType(val label: String) {
    STOCK("주식"),
    ETF("ETF"),
    FUND("펀드"),
    BOND("채권"),
    /** KRX 금시장 금현물 (1g 단위, 가격은 1g당 원). */
    GOLD("금현물"),
    ;

    /** 수량 단위 (금현물은 g). */
    val unit: String get() = if (this == GOLD) "g" else "주"

    companion object {
        /** KRX 금 99.99 1kg 종목코드. */
        const val GOLD_CODE = "M04020000"
    }
}

/** 투자 계좌 종류. */
enum class AccountKind(val label: String) {
    GENERAL("일반(위탁)"),
    ISA("ISA"),
    PENSION("연금저축"),
    IRP("IRP"),
    DC("퇴직연금(DC)"),
    /** 금현물 통장: 금과 원화 예수금만 있다. */
    GOLD("금현물"),
    OTHER("기타"),
}

/** 투자 계좌 (예: 한투 국내, 연금저축). */
data class InvestmentAccount(
    val id: String = "",
    val name: String,
    val kind: AccountKind = AccountKind.GENERAL,
    /** 계좌번호 (표시용, 숫자와 - 만). */
    val number: String = "",
    val memo: String = "",
    val createdAt: Long = 0L,
)

/** 계좌 안의 보유 종목. 수량·평균단가는 기록(Record)으로 계산한다. */
data class Holding(
    val id: String = "",
    val accountId: String,
    val name: String,
    /** 종목 코드 (국내 6자리, 해외 티커). 펀드·채권은 비워 둘 수 있다. */
    val code: String = "",
    val market: Market = Market.KR,
    val assetType: AssetType = AssetType.STOCK,
    /** 직접 입력한 현재가 (종목 통화 기준). */
    val manualPrice: Double? = null,
    val manualPriceDate: LocalDate? = null,
    val createdAt: Long = 0L,
) {
    val currency: Currency get() = market.currency
}

enum class RecordType(val label: String) {
    DEPOSIT("입금"),
    WITHDRAW("출금"),
    TRANSFER("계좌 간 이체"),
    EXCHANGE("환전"),
    BUY("매수"),
    SELL("매도"),
    DIVIDEND("배당"),
    /** 예전 버전의 예수금 조정 (지금은 계산에 쓰지 않는다). */
    CASH_ADJUST("예수금 조정"),
    /** 그날의 예수금 잔액 (직접 입력하거나 한투에서 불러온 값). */
    CASH_BALANCE("예수금"),
}

/**
 * 계좌의 모든 기록을 하나의 형태로 표현한다. 유형별로 쓰는 필드:
 *
 * - DEPOSIT / WITHDRAW: [currency], [amount](해당 통화), [krwAmount](원화 환산, 투자금 계산용)
 * - TRANSFER: [accountId] 보내는 계좌, [toAccountId] 받는 계좌, [currency], [amount], [krwAmount]
 * - EXCHANGE: [currency] 바꾸기 전 통화, [krwAmount] 원화 금액, [amount] 달러 금액 (기록용, 예수금에 영향 없음)
 * - BUY / SELL: [holdingId], [quantity], [price](종목 통화), [fee], [tax], [cost](제비용합)
 * - DIVIDEND: [holdingId], [amount] (종목 통화, 세후)
 * - CASH_ADJUST: [currency], [amount] (예전 버전 기록, 계산에 쓰지 않음)
 * - CASH_BALANCE: [currency], [amount] 그날의 예수금 잔액. 통화별로 가장 최근 값이 그 계좌의 예수금이다.
 */
data class Record(
    val id: String = "",
    val accountId: String,
    val type: RecordType,
    val date: LocalDate,
    val currency: Currency = Currency.KRW,
    val amount: Double = 0.0,
    val krwAmount: Double = 0.0,
    val toAccountId: String? = null,
    val holdingId: String? = null,
    val quantity: Double = 0.0,
    val price: Double = 0.0,
    val fee: Double = 0.0,
    val tax: Double = 0.0,
    /**
     * 제비용합 (종목 통화). 수수료·세금을 쓰면 그 합, 제비용합만 쓰면 수수료·세금은 0.
     * 예전 기록·한투에서 불러온 기록은 null 이고 수수료 + 세금을 쓴다.
     */
    val cost: Double? = null,
    /** 이미 보유하던 종목을 처음 등록할 때 쓴 매수 기록인지 (표시용). */
    val initial: Boolean = false,
    /** 한투 API 에서 불러온 기록이면 원본 식별자 (예: 주문번호). 이런 기록은 메모만 고칠 수 있다. */
    val externalId: String? = null,
    val memo: String = "",
    val createdAt: Long = 0L,
) {
    /** 계산에 쓰는 제비용합. */
    val totalCost: Double get() = cost ?: (fee + tax)
}

/**
 * 매수·매도의 제비용 입력. 수수료·세금을 쓰면 제비용합은 그 합(제비용합 칸은 잠김),
 * 제비용합만 쓰면 수수료·세금은 0 (두 칸은 잠김). 빈 칸은 null.
 */
data class CostInput(val total: Double?, val fee: Double?, val tax: Double?) {
    /** 수수료·세금으로 나눠 쓰는 중인지 */
    val breakdown: Boolean get() = fee != null || tax != null
    /** 제비용합 칸을 잠글지 (수수료·세금을 쓰는 중) */
    val totalLocked: Boolean get() = breakdown
    /** 수수료·세금 칸을 잠글지 (제비용합만 쓰는 중) */
    val partsLocked: Boolean get() = !breakdown && total != null
    /** 제비용합 */
    val effectiveTotal: Double get() = if (breakdown) (fee ?: 0.0) + (tax ?: 0.0) else total ?: 0.0

    /** 기록에 넣는다 (수수료·세금·제비용합을 모두 저장). */
    fun applyTo(record: Record): Record =
        if (breakdown) {
            record.copy(fee = fee ?: 0.0, tax = tax ?: 0.0, cost = effectiveTotal)
        } else {
            record.copy(fee = 0.0, tax = 0.0, cost = total ?: 0.0)
        }

    companion object {
        /** 수정할 기록 → 입력값: 수수료나 세금이 있으면 나눠서, 아니면 제비용합으로. */
        fun of(record: Record?): CostInput = when {
            record == null -> CostInput(null, null, null)
            record.fee != 0.0 || record.tax != 0.0 ->
                CostInput(null, record.fee.takeIf { it != 0.0 }, record.tax.takeIf { it != 0.0 })
            else -> CostInput(record.cost?.takeIf { it != 0.0 }, null, null)
        }
    }
}

/** 가격과 그 기준일. */
data class PricePoint(val price: Double, val date: LocalDate?)
