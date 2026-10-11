package com.mymoneytracker.core

import com.mymoneytracker.core.goals.GoalCalculator
import com.mymoneytracker.core.goals.GoalUnit
import com.mymoneytracker.core.model.SavingsGoalGroup
import com.mymoneytracker.core.model.GoalType
import com.mymoneytracker.core.model.SavingsAccount
import com.mymoneytracker.core.model.SavingsGoal
import com.mymoneytracker.core.model.SavingsSubAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GoalCalculatorTest {
    private val today = LocalDate.of(2026, 10, 8)

    /** 목표를 만든 시각 (2026-10-01 서울). */
    private val created = LocalDate.of(2026, 10, 1).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()

    @Test
    fun legacyRecurringGoalAdvancesUntilCreation() {
        // 예전 버전처럼 "지난 지출일"을 넣어 만든 목표: 만든 날 이후의 첫 날짜
        val goal = SavingsGoal(accountId = "a", name = "휴대폰", type = GoalType.RECURRING, amount = 2_000_000.0,
            dueDate = LocalDate.of(2023, 4, 8), intervalMonths = 24, createdAt = created)
        assertEquals(LocalDate.of(2027, 4, 8), GoalCalculator.nextDueDate(goal, today))
        // 당일은 아직 지나지 않은 것으로 본다
        assertEquals(LocalDate.of(2026, 10, 1), GoalCalculator.nextDueDate(goal.copy(dueDate = LocalDate.of(2026, 10, 1)), today))
    }

    @Test
    fun recurringGoalDoesNotAdvanceWithoutSpending() {
        // 만든 뒤에 지난 예정일은 넘기지 않는다 → 예정일 지남
        val goal = SavingsGoal(id = "g", accountId = "a", name = "휴대폰", type = GoalType.RECURRING, amount = 300.0,
            dueDate = LocalDate.of(2026, 10, 5), intervalMonths = 24, createdAt = created)
        assertEquals(LocalDate.of(2026, 10, 5), GoalCalculator.nextDueDate(goal, today))
        val account = SavingsAccount(id = "a", name = "지출", subAccounts = listOf(SavingsSubAccount("CMA", balance = 100.0)))
        val g = GoalCalculator.summarize(account, listOf(goal), today).goals.single()
        assertTrue(g.overdue)
    }

    @Test
    fun spendingRestartsInterval() {
        val goal = SavingsGoal(id = "g", accountId = "a", name = "노트북", type = GoalType.RECURRING, amount = 4_000_000.0,
            dueDate = LocalDate.of(2029, 1, 15), intervalMonths = 48, createdAt = created)
        val spent = GoalCalculator.recordSpending(goal, LocalDate.of(2026, 10, 20))
        assertEquals(LocalDate.of(2030, 10, 20), spent.dueDate)
        assertEquals(LocalDate.of(2026, 10, 20), spent.lastSpentDate)
        assertEquals(LocalDate.of(2030, 10, 20), GoalCalculator.nextDueDate(spent, today))
        val again = GoalCalculator.recordSpending(spent, LocalDate.of(2030, 9, 1))
        assertEquals(listOf(LocalDate.of(2026, 10, 20), LocalDate.of(2030, 9, 1)), again.spentDates)
        assertEquals(LocalDate.of(2034, 9, 1), again.dueDate)
    }

    @Test
    fun monthsUntilCountsContributions() {
        assertEquals(6, GoalCalculator.monthsUntil(today, LocalDate.of(2027, 4, 8)))
        assertEquals(6, GoalCalculator.monthsUntil(today, LocalDate.of(2027, 4, 1)))
        assertEquals(1, GoalCalculator.monthsUntil(today, today.plusDays(3)))
        assertEquals(0, GoalCalculator.monthsUntil(today, today))
        assertEquals(24, GoalCalculator.monthsUntil(today, LocalDate.of(2028, 10, 8)))
    }

    @Test
    fun monthlyPaymentMatchesSpecExample() {
        val r = GoalCalculator.monthlyRate(0.035)
        assertEquals(273_540.0, GoalCalculator.monthlyPayment(10_000_000.0, 3_000_000.0, 24, r), 1.0)
        assertEquals(291_666.67, GoalCalculator.monthlyPayment(10_000_000.0, 3_000_000.0, 24, 0.0), 0.01)
        assertEquals(0.0, GoalCalculator.monthlyPayment(100.0, 200.0, 5, r), 1e-9)
    }

    @Test
    fun allocatesNearestGoalFirst() {
        val account = SavingsAccount(
            id = "a", name = "IT기기금", annualRate = 0.035,
            subAccounts = listOf(SavingsSubAccount("CMA", balance = 300_000.0), SavingsSubAccount("채권", balance = 200_000.0)),
        )
        val laptop = SavingsGoal(id = "l", accountId = "a", name = "노트북", amount = 10_000_000.0,
            dueDate = LocalDate.of(2028, 10, 8), createdAt = created)
        val phone = SavingsGoal(id = "p", accountId = "a", name = "휴대폰", type = GoalType.RECURRING,
            amount = 2_000_000.0, dueDate = LocalDate.of(2025, 4, 8), intervalMonths = 24, createdAt = created + 1)
        val summary = GoalCalculator.summarize(account, listOf(laptop, phone), today)

        assertEquals(500_000.0, account.balance, 1e-9)
        assertEquals(listOf("p", "l"), summary.goals.map { it.goal.id })
        val p = summary.goals[0]
        val l = summary.goals[1]
        assertEquals(500_000.0, p.allocated, 1e-9)
        assertEquals(0.25, p.achievedRate, 1e-9)
        assertEquals(246_776.0, p.monthlyPayment, 1.0)
        assertEquals(0.0, l.allocated, 1e-9)
        assertEquals(403_075.0, l.monthlyPayment, 1.0)
        assertEquals(649_851.0, summary.totalMonthlyPayment, 2.0)
        assertEquals(0.0, summary.unallocated, 1e-9)
    }

    @Test
    fun overdueOneTimeGoalNeedsRemainingNow() {
        val account = SavingsAccount(id = "a", name = "지출", subAccounts = listOf(SavingsSubAccount("CMA", balance = 100.0)))
        val goal = SavingsGoal(accountId = "a", name = "여행", amount = 300.0, dueDate = today.minusDays(1))
        val g = GoalCalculator.summarize(account, listOf(goal), today).goals.single()
        assertTrue(g.overdue)
        assertEquals(200.0, g.monthlyPayment, 1e-9)
    }

    @Test
    fun sameDayGoalsShareByAmount() {
        val account = SavingsAccount(id = "a", name = "IT", subAccounts = listOf(SavingsSubAccount("CMA", balance = 300_000.0)))
        val due = LocalDate.of(2028, 11, 30)
        val a = SavingsGoal(id = "a1", accountId = "a", name = "PC", amount = 2_000_000.0, dueDate = due, createdAt = created)
        val b = SavingsGoal(id = "b1", accountId = "a", name = "NAS", amount = 1_000_000.0, dueDate = due, createdAt = created + 1)
        val goals = GoalCalculator.summarize(account, listOf(a, b), today).goals.associateBy { it.goal.id }
        assertEquals(200_000.0, goals.getValue("a1").allocated, 1e-6)
        assertEquals(100_000.0, goals.getValue("b1").allocated, 1e-6)
    }

    @Test
    fun combinedGroupIsOneGoal() {
        val account = SavingsAccount(id = "a", name = "IT", annualRate = 0.03, subAccounts = listOf(SavingsSubAccount("CMA", balance = 900_000.0)))
        val group = SavingsGoalGroup(id = "grp", accountId = "a", name = "IT 세트", dueDate = LocalDate.of(2028, 11, 30), createdAt = created)
        val pc = SavingsGoal(id = "pc", accountId = "a", name = "PC", amount = 6_000_000.0, dueDate = LocalDate.of(2028, 11, 30), createdAt = created, groupId = "grp")
        val nas = SavingsGoal(id = "nas", accountId = "a", name = "NAS", amount = 3_000_000.0, dueDate = LocalDate.of(2027, 1, 1), createdAt = created, groupId = "grp")
        val phone = SavingsGoal(id = "ph", accountId = "a", name = "휴대폰", amount = 1_000_000.0, dueDate = LocalDate.of(2029, 1, 1), createdAt = created)
        val summary = GoalCalculator.summarize(account, listOf(pc, nas, phone), today, listOf(group))

        // 그룹이 먼저(2028-11-30), NAS 의 원래 날짜(2027-01-01)는 쓰지 않는다
        val unit = summary.units.first() as GoalUnit.Group
        val combined = unit.combined!!
        assertEquals(9_000_000.0, combined.amount, 1e-6)
        assertEquals(900_000.0, combined.allocated, 1e-6)
        assertEquals(0.1, combined.achievedRate, 1e-9)
        val months = GoalCalculator.monthsUntil(today, LocalDate.of(2028, 11, 30))
        val expected = GoalCalculator.monthlyPayment(9_000_000.0, 900_000.0, months, GoalCalculator.monthlyRate(0.03))
        assertEquals(expected, combined.monthlyPayment, 1e-6)
        val byId = summary.goals.associateBy { it.goal.id }
        assertEquals(600_000.0, byId.getValue("pc").allocated, 1e-6)
        assertEquals(300_000.0, byId.getValue("nas").allocated, 1e-6)
        assertEquals(LocalDate.of(2028, 11, 30), byId.getValue("nas").nextDueDate)
        assertEquals(0.0, byId.getValue("ph").allocated, 1e-6)
        assertEquals(2, summary.units.size)

        // 개별 계산: 각자 원래 날짜 → NAS(2027-01-01) 가 먼저 채워진다
        val separate = GoalCalculator.summarize(account, listOf(pc, nas, phone), today, listOf(group.copy(combined = false)))
        val sepById = separate.goals.associateBy { it.goal.id }
        assertEquals(900_000.0, sepById.getValue("nas").allocated, 1e-6)
        assertEquals(0.0, sepById.getValue("pc").allocated, 1e-6)
        val sepUnit = separate.units.first { it is GoalUnit.Group } as GoalUnit.Group
        assertEquals(null, sepUnit.combined)
        assertEquals(listOf("nas", "pc"), sepUnit.members.map { it.goal.id })
    }

    @Test
    fun completedGoalsAreExcluded() {
        val account = SavingsAccount(id = "a", name = "IT", subAccounts = listOf(SavingsSubAccount("CMA", balance = 500.0)))
        val done = SavingsGoal(id = "d", accountId = "a", name = "모니터", amount = 400.0, dueDate = today.plusMonths(1),
            createdAt = created, completedDate = today)
        val next = SavingsGoal(id = "n", accountId = "a", name = "키보드", amount = 300.0, dueDate = today.plusMonths(2), createdAt = created)
        val summary = GoalCalculator.summarize(account, listOf(done, next), today)
        assertEquals(listOf("n"), summary.goals.map { it.goal.id })
        assertEquals(300.0, summary.goals.single().allocated, 1e-9)
        assertEquals(listOf("d"), summary.completed.map { it.id })
    }
}
