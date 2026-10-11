package com.mymoneytracker.core.goals

import com.mymoneytracker.core.model.GoalType
import com.mymoneytracker.core.model.SavingsAccount
import com.mymoneytracker.core.model.SavingsGoal
import com.mymoneytracker.core.model.SavingsGoalGroup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class GoalProgress(
    val goal: SavingsGoal,
    /** 이번에 채워야 할 날짜 (하나로 계산하는 그룹 안이면 그룹 날짜). */
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

/** 하나로 계산하는 그룹 전체의 진행 상황. 금액·배분·납입액은 안의 목표 합계. */
data class GroupProgress(
    val group: SavingsGoalGroup,
    val members: List<GoalProgress>,
) {
    val amount: Double get() = members.sumOf { it.goal.amount }
    val allocated: Double get() = members.sumOf { it.allocated }
    val monthlyPayment: Double get() = members.sumOf { it.monthlyPayment }
    val monthsLeft: Int get() = members.firstOrNull()?.monthsLeft ?: 0
    val achievedRate: Double get() = if (amount > 0) min(1.0, allocated / amount) else 1.0
    val remaining: Double get() = max(0.0, amount - allocated)
    val overdue: Boolean get() = monthsLeft == 0 && remaining > 0
}

/** 목표 화면의 한 칸: 목표 하나 또는 그룹 하나 (개별 계산 그룹은 [combined] = null 이고 목표들을 따로 계산). */
sealed interface GoalUnit {
    val dueDate: LocalDate

    data class Single(val progress: GoalProgress) : GoalUnit {
        override val dueDate: LocalDate get() = progress.nextDueDate
    }

    /** 그룹. [combined] 가 있으면 하나로 계산, 없으면 [members] 를 각자 계산했다. */
    data class Group(val group: SavingsGoalGroup, val members: List<GoalProgress>, val combined: GroupProgress?) : GoalUnit {
        override val dueDate: LocalDate get() = combined?.group?.dueDate ?: members.minOfOrNull { it.nextDueDate } ?: group.dueDate
    }
}

data class SavingsSummary(
    val account: SavingsAccount,
    /** 계산한 모든 목표 (다음 날짜가 가까운 순서, 그룹 안 목표 포함, 완료한 목표 제외). */
    val goals: List<GoalProgress>,
    /** 화면에 보여줄 칸 (날짜가 가까운 순서). */
    val units: List<GoalUnit> = goals.map { GoalUnit.Single(it) },
    /** 구매 완료한 1회성 목표 (최근 완료 순). */
    val completed: List<SavingsGoal> = emptyList(),
) {
    val totalMonthlyPayment: Double get() = goals.sumOf { it.monthlyPayment }
    val totalGoalAmount: Double get() = goals.sumOf { it.goal.amount }

    /** 모든 목표에 배분하고 남은 잔액. */
    val unallocated: Double get() = max(0.0, account.balance - goals.sumOf { it.allocated })
}

object GoalCalculator {

    /**
     * 반복 목표의 다음 지출일.
     * - 소비 기록이 있으면 저장된 날짜(소비한 날 + 주기)를 그대로 쓴다.
     * - 예정일이 지나도 자동으로 넘기지 않는다 (소비 기록을 기다린다).
     * - 단, 예전 버전에서 "지난 지출일"을 넣어 만든 목표는 목표를 만든 날 이후의 첫 날짜로 본다.
     */
    fun nextDueDate(goal: SavingsGoal, @Suppress("UNUSED_PARAMETER") today: LocalDate): LocalDate {
        if (goal.type == GoalType.ONE_TIME || goal.intervalMonths <= 0) return goal.dueDate
        if (goal.lastSpentDate != null || goal.createdAt <= 0L) return goal.dueDate
        return advancePast(goal.dueDate, goal.intervalMonths, createdDate(goal.createdAt))
    }

    /** [start] 에서 주기만큼 더해 가며 [notBefore] 이후(당일 포함)의 첫 날짜. */
    fun advancePast(start: LocalDate, intervalMonths: Int, notBefore: LocalDate): LocalDate {
        if (intervalMonths <= 0) return start
        var due = start
        var step = 0L
        while (due.isBefore(notBefore)) {
            step++
            due = start.plusMonths(intervalMonths * step)
        }
        return due
    }

    /** 반복 목표에 소비 기록을 남긴다: 다음 지출일 = 소비한 날 + 주기, 기록은 최근 50개. */
    fun recordSpending(goal: SavingsGoal, spentOn: LocalDate): SavingsGoal {
        val history = (goal.spentDates + spentOn).distinct().sorted().takeLast(MAX_SPENT_DATES)
        val last = history.last()
        return goal.copy(
            lastSpentDate = last,
            spentDates = history,
            dueDate = last.plusMonths(goal.intervalMonths.toLong().coerceAtLeast(1)),
        )
    }

    private fun createdDate(createdAt: Long): LocalDate =
        Instant.ofEpochMilli(createdAt).atZone(ZoneId.of("Asia/Seoul")).toLocalDate()

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

    /** 배분 단위: 목표 하나, 또는 하나로 계산하는 그룹 하나. */
    private class Slot(val goals: List<SavingsGoal>, val due: LocalDate, val createdAt: Long, val group: SavingsGoalGroup?) {
        val amount: Double get() = goals.sumOf { max(0.0, it.amount) }
        var allocated = 0.0
    }

    /**
     * 가까운 날짜부터 잔액을 채우고(같은 날짜끼리는 금액 비율), 목표별 달성률과 월 납입액을 계산한다.
     * 하나로 계산하는 그룹은 그룹 날짜·합계 금액으로 한 칸처럼 계산하고, 안의 목표에는 금액 비율로 나눈다.
     */
    fun summarize(
        account: SavingsAccount,
        goals: List<SavingsGoal>,
        today: LocalDate,
        groups: List<SavingsGoalGroup> = emptyList(),
    ): SavingsSummary {
        val rate = monthlyRate(account.annualRate)
        val active = goals.filter { !it.completed }
        val groupById = groups.associateBy { it.id }
        // 그룹은 1회성 목표만 묶는다 (반복 목표에 그룹이 붙어 있으면 무시)
        fun groupOf(goal: SavingsGoal): SavingsGoalGroup? =
            if (goal.type == GoalType.ONE_TIME) goal.groupId?.let(groupById::get) else null

        val slots = mutableListOf<Slot>()
        active.groupBy { groupOf(it)?.takeIf { g -> g.combined } }.forEach { (group, members) ->
            if (group != null) {
                slots += Slot(members, group.dueDate, group.createdAt, group)
            } else {
                members.forEach { slots += Slot(listOf(it), nextDueDate(it, today), it.createdAt, null) }
            }
        }
        slots.sortWith(compareBy({ it.due }, { it.createdAt }))

        // 같은 날짜끼리는 금액 비율로 나눈다
        var left = max(0.0, account.balance)
        slots.groupBy { it.due }.toSortedMap().values.forEach { sameDay ->
            val total = sameDay.sumOf { it.amount }
            if (total <= 0) return@forEach
            val give = min(left, total)
            sameDay.forEach { it.allocated = give * it.amount / total }
            left -= give
        }

        val progressById = mutableMapOf<String, GoalProgress>()
        val ordered = mutableListOf<GoalProgress>()
        slots.forEach { slot ->
            val months = monthsUntil(today, slot.due)
            val payment = monthlyPayment(slot.amount, slot.allocated, months, rate)
            val total = slot.amount
            slot.goals.forEach { goal ->
                val share = if (total > 0) max(0.0, goal.amount) / total else 1.0 / slot.goals.size
                val progress = GoalProgress(
                    goal = goal,
                    nextDueDate = slot.due,
                    allocated = slot.allocated * share,
                    monthsLeft = months,
                    monthlyPayment = payment * share,
                )
                progressById[goal.id] = progress
                ordered += progress
            }
        }

        // 화면 칸: 목표 하나 또는 그룹 하나 (개별 계산 그룹도 한 칸으로 모아 보여준다)
        val units = mutableListOf<GoalUnit>()
        val placedGroups = mutableSetOf<String>()
        ordered.forEach { progress ->
            val group = groupOf(progress.goal)
            if (group == null) {
                units += GoalUnit.Single(progress)
            } else if (placedGroups.add(group.id)) {
                val members = ordered.filter { groupOf(it.goal)?.id == group.id }
                units += GoalUnit.Group(group, members, if (group.combined) GroupProgress(group, members) else null)
            }
        }
        // 목표가 하나도 없는(모두 완료했거나 비운) 그룹은 보여주지 않는다
        units.sortWith(compareBy { it.dueDate })

        return SavingsSummary(
            account = account,
            goals = ordered,
            units = units,
            completed = goals.filter { it.completed }.sortedByDescending { it.completedDate },
        )
    }

    private const val MAX_SPENT_DATES = 50
}
