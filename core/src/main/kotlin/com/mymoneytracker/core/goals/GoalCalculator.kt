package com.mymoneytracker.core.goals

import com.mymoneytracker.core.model.GoalType
import com.mymoneytracker.core.model.SavingsAccount
import com.mymoneytracker.core.model.SavingsGoal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class GoalProgress(
    val goal: SavingsGoal,
    /** 이번에 채워야 할 날짜 (반복 목표는 오늘 이후의 가장 가까운 지출일). */
    val nextDueDate: LocalDate,
    /** 통장 잔액에서 이 목표에 배분된 금액. */
    val allocated: Double,
    /** 남은 납입 개월 수 (목표일이 지났으면 0). */
    val monthsLeft: Int,
    /** 매달 넣어야 할 금액 (이자 반영, 0 이상). */
    val monthlyPayment: Double,
) {
    val achievedRate: Double get() = if (goal.amount > 0) min(1.0, allocated / goal.amount) else 1.0
    val remaining: Double get() = max(0.0, goal.amount - allocated)
    val overdue: Boolean get() = monthsLeft == 0 && remaining > 0
}

data class SavingsSummary(
    val account: SavingsAccount,
    /** 다음 날짜가 가까운 순서. */
    val goals: List<GoalProgress>,
) {
    val totalMonthlyPayment: Double get() = goals.sumOf { it.monthlyPayment }
    val totalGoalAmount: Double get() = goals.sumOf { it.goal.amount }

    /** 모든 목표에 배분하고 남은 잔액. */
    val unallocated: Double get() = max(0.0, account.balance - goals.sumOf { it.allocated })
}

object GoalCalculator {

    /** 반복 목표의 다음 지출일: 기준일에서 주기만큼 더해 가며 [today] 이후(당일 포함)의 첫 날짜. */
    fun nextDueDate(goal: SavingsGoal, today: LocalDate): LocalDate {
        if (goal.type == GoalType.ONE_TIME || goal.intervalMonths <= 0) return goal.dueDate
        var due = goal.dueDate
        var step = 0L
        while (due.isBefore(today)) {
            step++
            due = goal.dueDate.plusMonths(goal.intervalMonths * step)
        }
        return due
    }

    /**
     * 오늘부터 목표일까지 매달 한 번씩(오늘 포함) 넣을 수 있는 횟수.
     * 예: 10/9 → 4/9 는 6회, 10/9 → 4/1 도 6회 (10/9, 11/9, ..., 3/9).
     */
    fun monthsUntil(today: LocalDate, due: LocalDate): Int {
        if (!due.isAfter(today)) return 0
        val whole = ChronoUnit.MONTHS.between(today, due)
        val months = if (today.plusMonths(whole).isBefore(due)) whole + 1 else whole
        return max(1, months.toInt())
    }

    /** 월 이율. 연 3.5% → (1.035)^(1/12) − 1 */
    fun monthlyRate(annualRate: Double): Double = (1 + annualRate).pow(1.0 / 12) - 1

    /**
     * 매달 넣어야 할 금액: 지금 배분된 [allocated] 가 [months] 개월 동안 이자로 불어난 뒤,
     * 매달 같은 금액을 넣어 [target] 에 도달하도록 계산한다.
     */
    fun monthlyPayment(target: Double, allocated: Double, months: Int, monthlyRate: Double): Double {
        if (months <= 0) return max(0.0, target - allocated)
        val growth = (1 + monthlyRate).pow(months)
        val needed = target - allocated * growth
        if (needed <= 0) return 0.0
        val factor = if (monthlyRate == 0.0) months.toDouble() else (growth - 1) / monthlyRate
        return needed / factor
    }

    /** 가까운 목표부터 잔액을 채우고, 목표별 달성률과 월 납입액을 계산한다. */
    fun summarize(account: SavingsAccount, goals: List<SavingsGoal>, today: LocalDate): SavingsSummary {
        val rate = monthlyRate(account.annualRate)
        var left = max(0.0, account.balance)
        val progresses = goals
            .map { it to nextDueDate(it, today) }
            .sortedWith(compareBy({ it.second }, { it.first.createdAt }))
            .map { (goal, due) ->
                val allocated = min(left, max(0.0, goal.amount))
                left -= allocated
                val months = monthsUntil(today, due)
                GoalProgress(
                    goal = goal,
                    nextDueDate = due,
                    allocated = allocated,
                    monthsLeft = months,
                    monthlyPayment = monthlyPayment(goal.amount, allocated, months, rate),
                )
            }
        return SavingsSummary(account, progresses)
    }
}
