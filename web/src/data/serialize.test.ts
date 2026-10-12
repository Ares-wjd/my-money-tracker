// 저장한 문서를 다시 읽으면 같은 값이 나오는지 (폰 형식과 같은 필드).
import { describe, expect, it } from 'vitest';
import { account, goal, holding, record, savingsAccount } from '../core/testData';
import { toAccount, toHolding, toRecord, toSavingsAccount, toSavingsGoal, toSavingsGoalGroup, toAllocationTarget } from './mappers';
import { accountDoc, cashRecordId, holdingDoc, recordDoc, roundCash, savingsAccountDoc, savingsGoalDoc, savingsGoalGroupDoc, allocationTargetDoc } from './serialize';

describe('Firestore 저장 형식', () => {
  it('계좌·종목·기록은 저장 후 다시 읽어도 같다', () => {
    const a = account({ id: 'a1', name: '연금저축', kind: 'PENSION', number: '123-45', memo: 'ETF', createdAt: 5 });
    expect(toAccount('a1', accountDoc(a))).toEqual(a);
    expect(Object.keys(accountDoc(a)).sort()).toEqual(['createdAt', 'kind', 'memo', 'name', 'number']);

    const h = holding({ id: 'h1', accountId: 'a1', name: 'Apple', code: 'AAPL', market: 'NASDAQ', manualPrice: 1.5, manualPriceDate: '2026-10-10', createdAt: 7 });
    expect(toHolding('h1', holdingDoc(h))).toEqual(h);

    const r = record({ id: 'r1', accountId: 'a1', type: 'DEPOSIT', date: '2026-10-10', currency: 'USD', amount: 10, krwAmount: 14000, createdAt: 9 });
    expect(toRecord('r1', recordDoc(r))).toEqual(r);
    // 폰과 같은 17개 필드 (보안 규칙의 허용 목록, 제비용합 cost 포함)
    expect(Object.keys(recordDoc(r))).toHaveLength(17);
  });

  it('createdAt 이 없으면 저장 시각을 넣는다', () => {
    const doc = accountDoc(account({ id: '', name: 'x' }));
    expect(doc.createdAt).toBeGreaterThan(1_700_000_000_000);
  });

  it('목적통장은 잔액 합계도 함께 저장하고, 목표 주기는 정수', () => {
    const s = savingsAccount({ id: 's1', name: 'IT기기금', subAccounts: [{ name: 'CMA', number: '1', balance: 300 }, { name: '채권', number: '', balance: 200 }], balanceDate: '2026-10-10', annualRate: 0.035, createdAt: 3 });
    const d = savingsAccountDoc(s);
    expect(d.balance).toBe(500);
    expect(toSavingsAccount('s1', d)).toEqual(s);

    const g = goal({ id: 'g1', accountId: 's1', name: '휴대폰', type: 'RECURRING', amount: 2_000_000, dueDate: '2027-04-08', intervalMonths: 24, createdAt: 4 });
    expect(toSavingsGoal('g1', savingsGoalDoc(g))).toEqual(g);
    expect(Number.isInteger(savingsGoalDoc({ ...g, intervalMonths: 12.0 }).intervalMonths)).toBe(true);
    const spent = { ...g, lastSpentDate: '2026-10-20', spentDates: ['2024-01-02', '2026-10-20'], groupId: null };
    expect(toSavingsGoal('g1', savingsGoalDoc(spent))).toEqual(spent);
    const done = goal({ id: 'g2', accountId: 's1', name: 'PC', amount: 6_000_000, dueDate: '2028-11-30', createdAt: 5, groupId: 'grp', completedDate: '2026-10-11' });
    expect(toSavingsGoal('g2', savingsGoalDoc(done))).toEqual(done);

    const t = { id: 't1', name: 'ISA 자산배분', accountIds: ['a1'], targets: { 'KR:360750': 40, CASH: 10 }, includeCash: true, threshold: 2.5, relative: 25, createdAt: 7, excluded: ['KR:005930'], names: { 'US:QQQM': 'Invesco QQQM' }, units: { 'US:VOO': 1000 }, prices: { 'manual-kr:x1': 50000 }, noBuy: ['manual-kr:x1'] };
    expect(toAllocationTarget('t1', allocationTargetDoc(t))).toEqual(t);
    const group = { id: 'grp', accountId: 's1', name: 'IT 세트', dueDate: '2028-11-30', combined: false, createdAt: 6 };
    expect(toSavingsGoalGroup('grp', savingsGoalGroupDoc(group))).toEqual(group);
  });

  it('예수금 문서 ID 와 반올림', () => {
    expect(cashRecordId('abc', '2026-10-10', 'USD')).toBe('cash_abc_20261010_USD');
    expect(roundCash('KRW', 1234.6)).toBe(1235);
    expect(roundCash('USD', 84.205)).toBe(84.21);
  });
});
