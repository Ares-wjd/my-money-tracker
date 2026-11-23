package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType

/** 기록 입력에서 고를 수 있는 종목 (v2.17, docs/FEATURES.md 1-2-3, 웹 holdingChoices 와 같은 규칙). */
object HoldingChoices {
    /** [holding] 의 지금 보유 수량. */
    fun quantity(holding: Holding, records: List<Record>): Double =
        PortfolioCalculator.position(holding, records.filter { it.holdingId == holding.id }).quantity

    /** 지금 0주인지 (숨길 수 있는지). */
    fun canHide(holding: Holding, records: List<Record>): Boolean = quantity(holding, records) <= 1e-9

    /**
     * [accountId] 계좌에서 [type] 기록에 고를 수 있는 종목. 매도는 지금 보유 수량이 있는 종목만, 그 밖에는 숨기지 않은 종목.
     * [currentId] 는 고치는 기록의 종목: 숨겼거나 0주여도 남긴다. 순서는 [holdings] 그대로.
     */
    fun forRecord(type: RecordType, accountId: String, holdings: List<Holding>, records: List<Record>, currentId: String? = null): List<Holding> =
        holdings.filter { h ->
            h.accountId == accountId && (
                h.id == currentId ||
                    if (type == RecordType.SELL) quantity(h, records) > 1e-9 else !h.hidden
                )
        }

    /** [accountId] 계좌의 숨긴 종목 수. */
    fun hiddenCount(accountId: String, holdings: List<Holding>): Int = holdings.count { it.accountId == accountId && it.hidden }
}
