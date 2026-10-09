package com.mymoneytracker.core.model

import java.time.LocalDate

/** 목적통장 (예: IT기기금, 지출). 잔액은 사용자가 가끔 직접 입력한다. 투자 자산과 완전히 분리된다. */
data class SavingsAccount(
    val id: String = "",
    val name: String,
    /** 현재 잔액 (CMA + 채권 등 합계, 원). */
    val balance: Double = 0.0,
    val balanceDate: LocalDate? = null,
    /** 기대수익률 (연, 0.035 = 3.5%). */
    val annualRate: Double = 0.0,
    val memo: String = "",
    val createdAt: Long = 0L,
)

enum class GoalType(val label: String) {
    ONE_TIME("1회성"),
    RECURRING("반복"),
}

/** 목적통장의 목표. */
data class SavingsGoal(
    val id: String = "",
    val accountId: String,
    val name: String,
    val type: GoalType = GoalType.ONE_TIME,
    /** 목표 금액 (원). */
    val amount: Double,
    /** 1회성: 목표일, 반복: 기준이 되는 지출일 (지나면 주기만큼 자동으로 넘어간다). */
    val dueDate: LocalDate,
    /** 반복 주기 (개월). 반복 목표에서만 사용. */
    val intervalMonths: Int = 12,
    val createdAt: Long = 0L,
)
