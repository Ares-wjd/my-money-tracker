import { describe, expect, it } from 'vitest';
import { buildRecord, recordProblem, type RecordInput } from './recordForm';
import { holding, record } from './testData';

const h = holding({ id: 'h', accountId: 'a', name: '삼성전자' });
const base: RecordInput = {
  type: 'DEPOSIT', date: '2026-10-10', currency: 'KRW', amount: 1000, krw: null, rate: null,
  toAccountId: null, holdingId: null, quantity: null, price: null, fee: 0, tax: 0, memo: ' 메모 ',
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

  it('수정하면 id·처음 만든 시각·한투 표시는 그대로', () => {
    const old = record({ id: 'r1', accountId: 'a', type: 'DIVIDEND', date: '2026-10-01', holdingId: 'h', amount: 5, createdAt: 99, externalId: null, initial: false });
    const r = buildRecord({ ...base, type: 'DIVIDEND', holdingId: 'h', amount: 7 }, 'a', old);
    expect(r.id).toBe('r1');
    expect(r.createdAt).toBe(99);
    expect(r.amount).toBe(7);
  });
});
