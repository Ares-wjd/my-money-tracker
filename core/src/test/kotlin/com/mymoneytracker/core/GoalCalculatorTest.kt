package com.mymoneytracker.core

import com.mymoneytracker.core.goals.GoalCalculator
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

    @Test
    fun recurringGoalAdvancesByInterval() {
        val goal = SavingsGoal(accountId = "a", name = "휴대폰", type = GoalType.RECURRING, amount = 2_000_000.0,
            dueDate = LocalDate.of(2023, 4, 8), intervalMonths = 24)
        assertEquals(LocalDate.of(2027, 4, 8), GoalCalculator.nextDueDate(goal, today))
        // 당일은 아직 지나지 않은 것으로 본다
        val dueToday = goal.copy(dueDate = today)
        assertEquals(today, GoalCalculator.nextDueDate(dueToday, today))
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
            dueDate = LocalDate.of(2028, 10, 8), createdAt = 1)
        val phone = SavingsGoal(id = "p", accountId = "a", name = "휴대폰", type = GoalType.RECURRING,
            amount = 2_000_000.0, dueDate = LocalDate.of(2025, 4, 8), intervalMonths = 24, createdAt = 2)
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
}
