// 저장한 문서를 다시 읽으면 같은 값이 나오는지 (폰 형식과 같은 필드).
import { describe, expect, it } from 'vitest';
import { account, goal, holding, record, savingsAccount } from '../core/testData';
import { toAccount, toHolding, toRecord, toSavingsAccount, toSavingsGoal } from './mappers';
import { accountDoc, cashRecordId, holdingDoc, recordDoc, roundCash, savingsAccountDoc, savingsGoalDoc } from './serialize';

describe('Firestore 저장 형식', () => {
  it('계좌·종목·기록은 저장 후 다시 읽어도 같다', () => {
    const a = account({ id: 'a1', name: '연금저축', kind: 'PENSION', number: '123-45', memo: 'ETF', createdAt: 5 });
    expect(toAccount('a1', accountDoc(a))).toEqual(a);
    expect(Object.keys(accountDoc(a)).sort()).toEqual(['createdAt', 'kind', 'memo', 'name', 'number']);

    const h = holding({ id: 'h1', accountId: 'a1', name: 'Apple', code: 'AAPL', market: 'NASDAQ', manualPrice: 1.5, manualPriceDate: '2026-10-10', createdAt: 7 });
    expect(toHolding('h1', holdingDoc(h))).toEqual(h);

    const r = record({ id: 'r1', accountId: 'a1', type: 'DEPOSIT', date: '2026-10-10', currency: 'USD', amount: 10, krwAmount: 14000, createdAt: 9 });
    expect(toRecord('r1', recordDoc(r))).toEqual(r);
    // 폰과 같은 16개 필드 (보안 규칙의 허용 목록)
    expect(Object.keys(recordDoc(r))).toHaveLength(16);
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
  });

  it('예수금 문서 ID 와 반올림', () => {
    expect(cashRecordId('abc', '2026-10-10', 'USD')).toBe('cash_abc_20261010_USD');
    expect(roundCash('KRW', 1234.6)).toBe(1235);
    expect(roundCash('USD', 84.205)).toBe(84.21);
  });
});
