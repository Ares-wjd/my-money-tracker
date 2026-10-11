package com.mymoneytracker.core.model

import java.time.LocalDate

/** 목적통장을 이루는 실제 계좌 하나 (예: CMA, 채권 계좌). */
data class SavingsSubAccount(
    val name: String,
    val number: String = "",
    val balance: Double = 0.0,
)

/**
 * 목적통장 (예: IT기기금, 지출). 여러 실제 계좌로 이뤄질 수 있고, 잔액은 그 합계다.
 * 잔액은 사용자가 가끔 직접 입력한다. 투자 자산과 완전히 분리된다.
 */
data class SavingsAccount(
    val id: String = "",
    val name: String,
    val subAccounts: List<SavingsSubAccount> = emptyList(),
    val balanceDate: LocalDate? = null,
    /** 기대수익률 (연, 0.035 = 3.5%). */
    val annualRate: Double = 0.0,
    val memo: String = "",
    val createdAt: Long = 0L,
) {
    /** 현재 잔액 = 계좌별 잔액 합계 (원). */
    val balance: Double get() = subAccounts.sumOf { it.balance }
}

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
    /** 속한 그룹 (1회성만). 없으면 null. */
    val groupId: String? = null,
    /** 반복 목표: 마지막으로 실제 소비한 날. */
    val lastSpentDate: LocalDate? = null,
    /** 반복 목표: 지난 소비 날짜들 (오래된 순, 최근 50개). */
    val spentDates: List<LocalDate> = emptyList(),
    /** 1회성 목표: 구매 완료한 날. 있으면 배분·납입 계산에서 뺀다. */
    val completedDate: LocalDate? = null,
) {
    val completed: Boolean get() = type == GoalType.ONE_TIME && completedDate != null
}

/** 함께 살 1회성 목표들의 묶음 (예: IT 세트 = PC + NAS). 목표는 [SavingsGoal.groupId] 로 연결한다. */
data class SavingsGoalGroup(
    val id: String = "",
    val accountId: String,
    val name: String,
    /** 그룹 구매 날짜 (하나로 계산할 때 쓰는 날짜). */
    val dueDate: LocalDate,
    /** true: 하나로 계산 (기본), false: 그룹 안 목표를 각자 날짜로 따로 계산. */
    val combined: Boolean = true,
    val createdAt: Long = 0L,
)
