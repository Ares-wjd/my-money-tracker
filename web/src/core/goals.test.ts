// core GoalCalculatorTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { savingsBalance } from '../model';
import { monthlyPayment, monthlyRate, monthsUntil, nextDueDate, summarizeSavings } from './goals';
import { goal, savingsAccount } from './testData';

const today = '2026-10-08';

describe('목표 계산 (GoalCalculatorTest)', () => {
  it('반복 목표는 주기만큼 넘어간다', () => {
    const g = goal({ accountId: 'a', name: '휴대폰', type: 'RECURRING', amount: 2_000_000, dueDate: '2023-04-08', intervalMonths: 24 });
    expect(nextDueDate(g, today)).toBe('2027-04-08');
    expect(nextDueDate({ ...g, dueDate: today }, today)).toBe(today);
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
    const laptop = goal({ id: 'l', accountId: 'a', name: '노트북', amount: 10_000_000, dueDate: '2028-10-08', createdAt: 1 });
    const phone = goal({ id: 'p', accountId: 'a', name: '휴대폰', type: 'RECURRING', amount: 2_000_000, dueDate: '2025-04-08', intervalMonths: 24, createdAt: 2 });
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
});
