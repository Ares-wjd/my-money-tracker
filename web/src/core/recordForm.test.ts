import { describe, expect, it } from 'vitest';
import { NO_COST, buildRecord, costInputOf, costPartsLocked, costBreakdown, effectiveCost, inputFromRecord, recordProblem, type RecordInput } from './recordForm';
import { holding, record } from './testData';

const h = holding({ id: 'h', accountId: 'a', name: '삼성전자' });
const base: RecordInput = {
  type: 'DEPOSIT', date: '2026-10-10', currency: 'KRW', amount: 1000, krw: null, rate: null,
  toAccountId: null, holdingId: null, quantity: null, price: null, cost: NO_COST, memo: ' 메모 ',
};

describe('기록 입력 검사 (폰과 같은 규칙)', () => {
  it('입출금·이체', () => {
    expect(recordProblem(base, [], [], null)).toBeNull();
    expect(recordProblem({ ...base, amount: 0 }, [], [], null)).toBe('금액을 입력하세요.');
    expect(recordProblem({ ...base, currency: 'USD' }, [], [], null)).toBe('원화 환산에 쓸 환율을 입력하세요.');
    expect(recordProblem({ ...base, type: 'TRANSFER' }, [], [], null)).toBe('받는 계좌를 고르세요.');
  });

  it('매도는 그날 보유 수량까지만 (수정 중인 기록은 빼고)', () => {
    const buy = record({ id: 'b', accountId: 'a', type: 'BUY', date: '2026-10-01', holdingId: 'h', quantity: 10, price: 1 });
    const sell = record({ id: 's', accountId: 'a', type: 'SELL', date: '2026-10-05', holdingId: 'h', quantity: 4, price: 1 });
    const input: RecordInput = { ...base, type: 'SELL', holdingId: 'h', quantity: 7, price: 1 };
    expect(recordProblem(input, [h], [buy, sell], null)).toBe('매도 수량이 그날 보유 수량(6)보다 많습니다.');
    expect(recordProblem(input, [h], [buy, sell], 's')).toBeNull();
    expect(recordProblem({ ...input, holdingId: null }, [], [], null)).toBe('먼저 계좌 화면에서 종목을 추가하세요.');
  });

  it('달러 입금은 원화 환산 투자금을 함께 저장', () => {
    const r = buildRecord({ ...base, currency: 'USD', amount: 10, rate: 1400 }, 'a', null);
    expect(r.krwAmount).toBe(14000);
    expect(r.memo).toBe('메모');
    expect(r.holdingId).toBeNull();
  });

  it('제비용: 수수료·세금을 쓰면 합계, 제비용합만 쓰면 수수료·세금 0 (모두 저장)', () => {
    const buy: RecordInput = { ...base, type: 'BUY', holdingId: 'h', quantity: 1, price: 100 };
    const parts = { total: 999, fee: 100, tax: null };
    expect(costBreakdown(parts)).toBe(true);
    expect(effectiveCost(parts)).toBe(100);
    const r1 = buildRecord({ ...buy, cost: parts }, 'a', null);
    expect([r1.fee, r1.tax, r1.cost]).toEqual([100, 0, 100]);
    const total = { total: 1500, fee: null, tax: null };
    expect(costPartsLocked(total)).toBe(true);
    const r2 = buildRecord({ ...buy, cost: total }, 'a', null);
    expect([r2.fee, r2.tax, r2.cost]).toEqual([0, 0, 1500]);
    expect(costInputOf(r1)).toEqual({ total: null, fee: 100, tax: null });
    expect(costInputOf(r2)).toEqual({ total: 1500, fee: null, tax: null });
    expect(costInputOf(record({ id: 'x', accountId: 'a', type: 'BUY', date: '2026-10-01' }))).toEqual(NO_COST);
    expect(inputFromRecord(r2, null).cost.total).toBe(1500);
    expect(costPartsLocked(NO_COST)).toBe(false);
  });

  it('수정하면 id·처음 만든 시각·한투 표시는 그대로', () => {
    const old = record({ id: 'r1', accountId: 'a', type: 'DIVIDEND', date: '2026-10-01', holdingId: 'h', amount: 5, createdAt: 99, externalId: null, initial: false });
    const r = buildRecord({ ...base, type: 'DIVIDEND', holdingId: 'h', amount: 7 }, 'a', old);
    expect(r.id).toBe('r1');
    expect(r.createdAt).toBe(99);
    expect(r.amount).toBe(7);
  });
});
