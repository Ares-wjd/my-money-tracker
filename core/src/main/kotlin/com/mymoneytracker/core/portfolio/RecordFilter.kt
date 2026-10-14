package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType

/**
 * 기록 유형 필터 (웹 recordFilter.ts 와 같은 규칙). 뺀 유형([hidden])을 기억하므로 기본은 전부 보임(= 전체),
 * 새로 생긴 유형도 보인다.
 */
object RecordFilter {
    /** 칩 순서. */
    val ORDER = listOf(
        RecordType.DEPOSIT, RecordType.WITHDRAW, RecordType.TRANSFER, RecordType.BUY, RecordType.SELL,
        RecordType.DIVIDEND, RecordType.CASH_BALANCE, RecordType.EXCHANGE, RecordType.CASH_ADJUST,
    )

    fun label(type: RecordType): String = when (type) {
        RecordType.TRANSFER -> "이체"
        else -> type.label
    }

    /** 칩으로 보여줄 유형: 기록에 있는 유형만, 정해진 순서로. */
    fun present(records: List<Record>): List<RecordType> {
        val types = records.map { it.type }.toSet()
        return ORDER.filter { it in types }
    }

    fun apply(records: List<Record>, hidden: Set<RecordType>): List<Record> =
        if (hidden.isEmpty()) records else records.filter { it.type !in hidden }

    /** 칩에 있는 유형이 모두 보이는지 ([전체] 칩이 켜진 상태). */
    fun allShown(present: List<RecordType>, hidden: Set<RecordType>): Boolean = present.none { it in hidden }

    /** [전체] 칩: 모두 보이면 전체 해제(모든 유형을 뺌), 아니면 전체 선택. */
    fun toggleAll(present: List<RecordType>, hidden: Set<RecordType>): Set<RecordType> =
        if (allShown(present, hidden)) ORDER.toSet() else emptySet()

    /** 유형 칩 하나 켜기·끄기. */
    fun toggle(type: RecordType, hidden: Set<RecordType>): Set<RecordType> =
        if (type in hidden) hidden - type else hidden + type
}
