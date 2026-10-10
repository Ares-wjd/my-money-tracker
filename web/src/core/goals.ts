// core 의 GoalCalculator 와 같은 규칙.
import type { SavingsAccount, SavingsGoal } from '../model';
import { savingsBalance } from '../model';
import { monthsBetween, plusMonths } from './dates';

export interface GoalProgress {
  goal: SavingsGoal;
  /** 이번에 채워야 할 날짜 (반복 목표는 오늘 이후의 가장 가까운 지출일) */
  nextDueDate: string;
  /** 통장 잔액에서 이 목표에 배분된 금액 */
  allocated: number;
  /** 남은 납입 개월 수 (목표일이 지났으면 0) */
  monthsLeft: number;
  /** 매달 넣어야 할 금액 (이자 반영, 0 이상) */
  monthlyPayment: number;
  achievedRate: number;
  remaining: number;
  overdue: boolean;
}

export interface SavingsSummary {
  account: SavingsAccount;
  /** 다음 날짜가 가까운 순서 */
  goals: GoalProgress[];
  totalMonthlyPayment: number;
  totalGoalAmount: number;
  /** 모든 목표에 배분하고 남은 잔액 */
  unallocated: number;
}

/** 반복 목표의 다음 지출일: 기준일에서 주기만큼 더해 가며 [today] 이후(당일 포함)의 첫 날짜. */
export function nextDueDate(goal: SavingsGoal, today: string): string {
  if (goal.type === 'ONE_TIME' || goal.intervalMonths <= 0) return goal.dueDate;
  let due = goal.dueDate;
  let step = 0;
  while (due < today) {
    step++;
    due = plusMonths(goal.dueDate, goal.intervalMonths * step);
  }
  return due;
}

/** 오늘부터 목표일까지 매달 한 번씩(오늘 포함) 넣을 수 있는 횟수. 예: 10/9 → 4/9 는 6회, 10/9 → 4/1 도 6회. */
export function monthsUntil(today: string, due: string): number {
  if (!(due > today)) return 0;
  const whole = monthsBetween(today, due);
  const months = plusMonths(today, whole) < due ? whole + 1 : whole;
  return Math.max(1, months);
}

/** 월 이율. 연 3.5% → (1.035)^(1/12) − 1 */
export function monthlyRate(annualRate: number): number {
  return Math.pow(1 + annualRate, 1 / 12) - 1;
}

/** 지금 배분된 금액이 이자로 불어난 뒤, 매달 같은 금액을 넣어 목표에 도달하도록 하는 월 납입액. */
export function monthlyPayment(target: number, allocated: number, months: number, rate: number): number {
  if (months <= 0) return Math.max(0, target - allocated);
  const growth = Math.pow(1 + rate, months);
  const needed = target - allocated * growth;
  if (needed <= 0) return 0;
  const factor = rate === 0 ? months : (growth - 1) / rate;
  return needed / factor;
}

/** 가까운 목표부터 잔액을 채우고, 목표별 달성률과 월 납입액을 계산한다. */
export function summarizeSavings(account: SavingsAccount, goals: readonly SavingsGoal[], today: string): SavingsSummary {
  const rate = monthlyRate(account.annualRate);
  const balance = savingsBalance(account);
  let left = Math.max(0, balance);
  const progresses = goals
    .map((goal) => ({ goal, due: nextDueDate(goal, today) }))
    .sort((a, b) => (a.due !== b.due ? (a.due < b.due ? -1 : 1) : a.goal.createdAt - b.goal.createdAt))
    .map(({ goal, due }): GoalProgress => {
      const allocated = Math.min(left, Math.max(0, goal.amount));
      left -= allocated;
      const months = monthsUntil(today, due);
      const remaining = Math.max(0, goal.amount - allocated);
      return {
        goal,
        nextDueDate: due,
        allocated,
        monthsLeft: months,
        monthlyPayment: monthlyPayment(goal.amount, allocated, months, rate),
        achievedRate: goal.amount > 0 ? Math.min(1, allocated / goal.amount) : 1,
        remaining,
        overdue: months === 0 && remaining > 0,
      };
    });
  const allocatedTotal = progresses.reduce((s, g) => s + g.allocated, 0);
  return {
    account,
    goals: progresses,
    totalMonthlyPayment: progresses.reduce((s, g) => s + g.monthlyPayment, 0),
    totalGoalAmount: progresses.reduce((s, g) => s + g.goal.amount, 0),
    unallocated: Math.max(0, balance - allocatedTotal),
  };
}
