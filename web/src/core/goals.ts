// core 의 GoalCalculator 와 같은 규칙.
import type { SavingsAccount, SavingsGoal, SavingsGoalGroup } from '../model';
import { isCompleted, savingsBalance } from '../model';
import { monthsBetween, plusMonths } from './dates';

export interface GoalProgress {
  goal: SavingsGoal;
  /** 이번에 채워야 할 날짜 (하나로 계산하는 그룹 안이면 그룹 날짜) */
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

/** 하나로 계산하는 그룹 전체의 진행 상황 (안의 목표 합계). */
export interface GroupProgress {
  amount: number;
  allocated: number;
  monthlyPayment: number;
  monthsLeft: number;
  achievedRate: number;
  remaining: number;
  overdue: boolean;
}

/** 목표 화면의 한 칸: 목표 하나 또는 그룹 하나 (개별 계산 그룹은 combined = null). */
export type GoalUnit =
  | { kind: 'single'; dueDate: string; progress: GoalProgress }
  | { kind: 'group'; dueDate: string; group: SavingsGoalGroup; members: GoalProgress[]; combined: GroupProgress | null };

export interface SavingsSummary {
  account: SavingsAccount;
  /** 계산한 모든 목표 (가까운 순서, 그룹 안 목표 포함, 완료한 목표 제외) */
  goals: GoalProgress[];
  /** 화면에 보여줄 칸 (날짜가 가까운 순서) */
  units: GoalUnit[];
  /** 구매 완료한 1회성 목표 (최근 완료 순) */
  completed: SavingsGoal[];
  totalMonthlyPayment: number;
  totalGoalAmount: number;
  /** 모든 목표에 배분하고 남은 잔액 */
  unallocated: number;
}

const MAX_SPENT_DATES = 50;

/** 밀리초 → 서울 날짜 (YYYY-MM-DD). */
function seoulDate(ms: number): string {
  return new Date(ms + 9 * 3600 * 1000).toISOString().slice(0, 10);
}

/** [start] 에서 주기만큼 더해 가며 [notBefore] 이후(당일 포함)의 첫 날짜. */
export function advancePast(start: string, intervalMonths: number, notBefore: string): string {
  if (intervalMonths <= 0) return start;
  let due = start;
  let step = 0;
  while (due < notBefore) {
    step++;
    due = plusMonths(start, intervalMonths * step);
  }
  return due;
}

/**
 * 반복 목표의 다음 지출일. 소비 기록이 있으면 저장된 날짜(소비한 날 + 주기), 예정일이 지나도 자동으로 넘기지 않는다.
 * 예전 버전에서 "지난 지출일"을 넣어 만든 목표는 목표를 만든 날 이후의 첫 날짜로 본다.
 */
export function nextDueDate(goal: SavingsGoal): string {
  if (goal.type === 'ONE_TIME' || goal.intervalMonths <= 0) return goal.dueDate;
  if (goal.lastSpentDate !== null || goal.createdAt <= 0) return goal.dueDate;
  return advancePast(goal.dueDate, goal.intervalMonths, seoulDate(goal.createdAt));
}

/** 반복 목표에 소비 기록을 남긴다: 다음 지출일 = 소비한 날 + 주기, 기록은 최근 50개. */
export function recordSpending(goal: SavingsGoal, spentOn: string): SavingsGoal {
  const history = [...new Set([...goal.spentDates, spentOn])].sort().slice(-MAX_SPENT_DATES);
  const last = history[history.length - 1];
  return { ...goal, lastSpentDate: last, spentDates: history, dueDate: plusMonths(last, Math.max(1, goal.intervalMonths)) };
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

interface Slot {
  goals: SavingsGoal[];
  due: string;
  createdAt: number;
  amount: number;
  allocated: number;
}

const byDate = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

function progressOf(goal: SavingsGoal, due: string, allocated: number, months: number, payment: number): GoalProgress {
  const remaining = Math.max(0, goal.amount - allocated);
  return {
    goal,
    nextDueDate: due,
    allocated,
    monthsLeft: months,
    monthlyPayment: payment,
    achievedRate: goal.amount > 0 ? Math.min(1, allocated / goal.amount) : 1,
    remaining,
    overdue: months === 0 && remaining > 0,
  };
}

function groupProgress(members: readonly GoalProgress[]): GroupProgress {
  const amount = members.reduce((s, m) => s + m.goal.amount, 0);
  const allocated = members.reduce((s, m) => s + m.allocated, 0);
  const monthsLeft = members[0]?.monthsLeft ?? 0;
  const remaining = Math.max(0, amount - allocated);
  return {
    amount,
    allocated,
    monthlyPayment: members.reduce((s, m) => s + m.monthlyPayment, 0),
    monthsLeft,
    achievedRate: amount > 0 ? Math.min(1, allocated / amount) : 1,
    remaining,
    overdue: monthsLeft === 0 && remaining > 0,
  };
}

/**
 * 가까운 날짜부터 잔액을 채우고(같은 날짜끼리는 금액 비율), 목표별 달성률과 월 납입액을 계산한다.
 * 하나로 계산하는 그룹은 그룹 날짜·합계 금액으로 한 칸처럼 계산하고, 안의 목표에는 금액 비율로 나눈다.
 */
export function summarizeSavings(
  account: SavingsAccount,
  goals: readonly SavingsGoal[],
  today: string,
  groups: readonly SavingsGoalGroup[] = [],
): SavingsSummary {
  const rate = monthlyRate(account.annualRate);
  const balance = savingsBalance(account);
  const active = goals.filter((g) => !isCompleted(g));
  const groupById = new Map(groups.map((g) => [g.id, g]));
  // 그룹은 1회성 목표만 묶는다
  const groupOf = (goal: SavingsGoal) => (goal.type === 'ONE_TIME' && goal.groupId ? groupById.get(goal.groupId) ?? null : null);

  const slots: Slot[] = [];
  const combinedSlots = new Map<string, Slot>();
  for (const goal of active) {
    const group = groupOf(goal);
    if (group && group.combined) {
      let slot = combinedSlots.get(group.id);
      if (!slot) {
        slot = { goals: [], due: group.dueDate, createdAt: group.createdAt, amount: 0, allocated: 0 };
        combinedSlots.set(group.id, slot);
        slots.push(slot);
      }
      slot.goals.push(goal);
      slot.amount += Math.max(0, goal.amount);
    } else {
      slots.push({ goals: [goal], due: nextDueDate(goal), createdAt: goal.createdAt, amount: Math.max(0, goal.amount), allocated: 0 });
    }
  }
  slots.sort((a, b) => byDate(a.due, b.due) || a.createdAt - b.createdAt);

  // 같은 날짜끼리는 금액 비율로 나눈다
  let left = Math.max(0, balance);
  for (let i = 0; i < slots.length; ) {
    let j = i;
    while (j < slots.length && slots[j].due === slots[i].due) j++;
    const sameDay = slots.slice(i, j);
    const total = sameDay.reduce((s, x) => s + x.amount, 0);
    if (total > 0) {
      const give = Math.min(left, total);
      for (const slot of sameDay) slot.allocated = (give * slot.amount) / total;
      left -= give;
    }
    i = j;
  }

  const ordered: GoalProgress[] = [];
  for (const slot of slots) {
    const months = monthsUntil(today, slot.due);
    const payment = monthlyPayment(slot.amount, slot.allocated, months, rate);
    for (const goal of slot.goals) {
      const share = slot.amount > 0 ? Math.max(0, goal.amount) / slot.amount : 1 / slot.goals.length;
      ordered.push(progressOf(goal, slot.due, slot.allocated * share, months, payment * share));
    }
  }

  // 화면 칸: 목표 하나 또는 그룹 하나 (개별 계산 그룹도 한 칸으로 모아 보여준다)
  const units: GoalUnit[] = [];
  const placed = new Set<string>();
  for (const progress of ordered) {
    const group = groupOf(progress.goal);
    if (!group) {
      units.push({ kind: 'single', dueDate: progress.nextDueDate, progress });
    } else if (!placed.has(group.id)) {
      placed.add(group.id);
      const members = ordered.filter((p) => groupOf(p.goal)?.id === group.id);
      const dueDate = group.combined ? group.dueDate : members.map((m) => m.nextDueDate).sort()[0] ?? group.dueDate;
      units.push({ kind: 'group', dueDate, group, members, combined: group.combined ? groupProgress(members) : null });
    }
  }
  // 날짜 순 (같으면 원래 순서 유지)
  const indexed = units.map((u, i) => [u, i] as const);
  indexed.sort((a, b) => byDate(a[0].dueDate, b[0].dueDate) || a[1] - b[1]);

  const allocatedTotal = ordered.reduce((s, g) => s + g.allocated, 0);
  return {
    account,
    goals: ordered,
    units: indexed.map(([u]) => u),
    completed: goals.filter(isCompleted).sort((a, b) => byDate(b.completedDate ?? '', a.completedDate ?? '')),
    totalMonthlyPayment: ordered.reduce((s, g) => s + g.monthlyPayment, 0),
    totalGoalAmount: ordered.reduce((s, g) => s + g.goal.amount, 0),
    unallocated: Math.max(0, balance - allocatedTotal),
  };
}
