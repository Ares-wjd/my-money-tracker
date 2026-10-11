// core GoalCalculatorTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { savingsBalance } from '../model';
import { monthlyPayment, monthlyRate, monthsUntil, nextDueDate, recordSpending, summarizeSavings } from './goals';
import type { SavingsGoalGroup } from '../model';
import { goal, savingsAccount } from './testData';

const today = '2026-10-08';

describe('목표 계산 (GoalCalculatorTest)', () => {
  // 목표를 만든 시각 (2026-10-01 서울)
  const created = Date.UTC(2026, 8, 30, 15);

  it('예전 반복 목표는 만든 날 이후의 첫 날짜', () => {
    const g = goal({ accountId: 'a', name: '휴대폰', type: 'RECURRING', amount: 2_000_000, dueDate: '2023-04-08', intervalMonths: 24, createdAt: created });
    expect(nextDueDate(g)).toBe('2027-04-08');
    expect(nextDueDate({ ...g, dueDate: '2026-10-01' })).toBe('2026-10-01');
  });

  it('소비 기록이 없으면 예정일을 넘기지 않는다', () => {
    const g = goal({ id: 'g', accountId: 'a', name: '휴대폰', type: 'RECURRING', amount: 300, dueDate: '2026-10-05', intervalMonths: 24, createdAt: created });
    expect(nextDueDate(g)).toBe('2026-10-05');
    const account = savingsAccount({ id: 'a', name: '지출', subAccounts: [{ name: 'CMA', number: '', balance: 100 }] });
    expect(summarizeSavings(account, [g], today).goals[0].overdue).toBe(true);
  });

  it('소비하면 그날부터 주기를 다시 센다', () => {
    const g = goal({ id: 'g', accountId: 'a', name: '노트북', type: 'RECURRING', amount: 4_000_000, dueDate: '2029-01-15', intervalMonths: 48, createdAt: created });
    const spent = recordSpending(g, '2026-10-20');
    expect(spent.dueDate).toBe('2030-10-20');
    expect(spent.lastSpentDate).toBe('2026-10-20');
    expect(nextDueDate(spent)).toBe('2030-10-20');
    const again = recordSpending(spent, '2030-09-01');
    expect(again.spentDates).toEqual(['2026-10-20', '2030-09-01']);
    expect(again.dueDate).toBe('2034-09-01');
  });

  it('남은 납입 횟수', () => {
    expect(monthsUntil(today, '2027-04-08')).toBe(6);
    expect(monthsUntil(today, '2027-04-01')).toBe(6);
    expect(monthsUntil(today, '2026-10-11')).toBe(1);
    expect(monthsUntil(today, today)).toBe(0);
    expect(monthsUntil(today, '2028-10-08')).toBe(24);
  });

  it('월 납입액 (명세서 예시)', () => {
    const r = monthlyRate(0.035);
    expect(Math.abs(monthlyPayment(10_000_000, 3_000_000, 24, r) - 273_540)).toBeLessThanOrEqual(1);
    expect(monthlyPayment(10_000_000, 3_000_000, 24, 0)).toBeCloseTo(291_666.67, 2);
    expect(monthlyPayment(100, 200, 5, r)).toBe(0);
  });

  it('가까운 목표부터 채운다', () => {
    const account = savingsAccount({
      id: 'a', name: 'IT기기금', annualRate: 0.035,
      subAccounts: [{ name: 'CMA', number: '', balance: 300_000 }, { name: '채권', number: '', balance: 200_000 }],
    });
    const laptop = goal({ id: 'l', accountId: 'a', name: '노트북', amount: 10_000_000, dueDate: '2028-10-08', createdAt: created });
    const phone = goal({ id: 'p', accountId: 'a', name: '휴대폰', type: 'RECURRING', amount: 2_000_000, dueDate: '2025-04-08', intervalMonths: 24, createdAt: created + 1 });
    const summary = summarizeSavings(account, [laptop, phone], today);

    expect(savingsBalance(account)).toBe(500_000);
    expect(summary.goals.map((g) => g.goal.id)).toEqual(['p', 'l']);
    const [p, l] = summary.goals;
    expect(p.allocated).toBe(500_000);
    expect(p.achievedRate).toBeCloseTo(0.25, 9);
    expect(Math.abs(p.monthlyPayment - 246_776)).toBeLessThanOrEqual(1);
    expect(l.allocated).toBe(0);
    expect(Math.abs(l.monthlyPayment - 403_075)).toBeLessThanOrEqual(1);
    expect(Math.abs(summary.totalMonthlyPayment - 649_851)).toBeLessThanOrEqual(2);
    expect(summary.unallocated).toBe(0);
  });

  it('지난 1회성 목표는 남은 금액을 바로', () => {
    const account = savingsAccount({ id: 'a', name: '지출', subAccounts: [{ name: 'CMA', number: '', balance: 100 }] });
    const g = summarizeSavings(account, [goal({ accountId: 'a', name: '여행', amount: 300, dueDate: '2026-10-07' })], today).goals[0];
    expect(g.overdue).toBe(true);
    expect(g.monthlyPayment).toBe(200);
  });

  it('같은 날짜 목표는 금액 비율로 나눈다', () => {
    const account = savingsAccount({ id: 'a', name: 'IT', subAccounts: [{ name: 'CMA', number: '', balance: 300_000 }] });
    const a = goal({ id: 'a1', accountId: 'a', name: 'PC', amount: 2_000_000, dueDate: '2028-11-30', createdAt: created });
    const b = goal({ id: 'b1', accountId: 'a', name: 'NAS', amount: 1_000_000, dueDate: '2028-11-30', createdAt: created + 1 });
    const byId = new Map(summarizeSavings(account, [a, b], today).goals.map((g) => [g.goal.id, g]));
    expect(byId.get('a1')!.allocated).toBeCloseTo(200_000, 6);
    expect(byId.get('b1')!.allocated).toBeCloseTo(100_000, 6);
  });

  it('하나로 계산하는 그룹은 목표 하나처럼', () => {
    const account = savingsAccount({ id: 'a', name: 'IT', annualRate: 0.03, subAccounts: [{ name: 'CMA', number: '', balance: 900_000 }] });
    const group: SavingsGoalGroup = { id: 'grp', accountId: 'a', name: 'IT 세트', dueDate: '2028-11-30', combined: true, createdAt: created };
    const pc = goal({ id: 'pc', accountId: 'a', name: 'PC', amount: 6_000_000, dueDate: '2028-11-30', createdAt: created, groupId: 'grp' });
    const nas = goal({ id: 'nas', accountId: 'a', name: 'NAS', amount: 3_000_000, dueDate: '2027-01-01', createdAt: created, groupId: 'grp' });
    const phone = goal({ id: 'ph', accountId: 'a', name: '휴대폰', amount: 1_000_000, dueDate: '2029-01-01', createdAt: created });
    const summary = summarizeSavings(account, [pc, nas, phone], today, [group]);

    const unit = summary.units[0];
    expect(unit.kind).toBe('group');
    if (unit.kind !== 'group' || unit.combined === null) throw new Error('그룹이어야 함');
    expect(unit.combined.amount).toBe(9_000_000);
    expect(unit.combined.allocated).toBeCloseTo(900_000, 6);
    expect(unit.combined.achievedRate).toBeCloseTo(0.1, 9);
    const expected = monthlyPayment(9_000_000, 900_000, monthsUntil(today, '2028-11-30'), monthlyRate(0.03));
    expect(unit.combined.monthlyPayment).toBeCloseTo(expected, 6);
    const byId = new Map(summary.goals.map((g) => [g.goal.id, g]));
    expect(byId.get('pc')!.allocated).toBeCloseTo(600_000, 6);
    expect(byId.get('nas')!.allocated).toBeCloseTo(300_000, 6);
    expect(byId.get('nas')!.nextDueDate).toBe('2028-11-30');
    expect(byId.get('ph')!.allocated).toBeCloseTo(0, 6);
    expect(summary.units.length).toBe(2);

    // 개별 계산: 각자 원래 날짜 → NAS(2027-01-01) 가 먼저
    const separate = summarizeSavings(account, [pc, nas, phone], today, [{ ...group, combined: false }]);
    const sep = new Map(separate.goals.map((g) => [g.goal.id, g]));
    expect(sep.get('nas')!.allocated).toBeCloseTo(900_000, 6);
    expect(sep.get('pc')!.allocated).toBeCloseTo(0, 6);
    const sepUnit = separate.units.find((u) => u.kind === 'group');
    if (!sepUnit || sepUnit.kind !== 'group') throw new Error('그룹이어야 함');
    expect(sepUnit.combined).toBeNull();
    expect(sepUnit.members.map((m) => m.goal.id)).toEqual(['nas', 'pc']);
  });

  it('구매 완료한 목표는 빼고 계산한다', () => {
    const account = savingsAccount({ id: 'a', name: 'IT', subAccounts: [{ name: 'CMA', number: '', balance: 500 }] });
    const done = goal({ id: 'd', accountId: 'a', name: '모니터', amount: 400, dueDate: '2026-11-08', createdAt: created, completedDate: today });
    const next = goal({ id: 'n', accountId: 'a', name: '키보드', amount: 300, dueDate: '2026-12-08', createdAt: created });
    const summary = summarizeSavings(account, [done, next], today);
    expect(summary.goals.map((g) => g.goal.id)).toEqual(['n']);
    expect(summary.goals[0].allocated).toBe(300);
    expect(summary.completed.map((g) => g.id)).toEqual(['d']);
  });
});
