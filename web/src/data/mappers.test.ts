import { describe, expect, it } from 'vitest';
import { linkedAccountIds } from '../model';
import { latestQuoteDate, toAccount, toMarketSnapshot, toRecord, toSavingsAccount, toSavingsGoal } from './mappers';

describe('Firestore 문서 읽기', () => {
  it('계좌: 모르는 종류는 일반(위탁), 빠진 값은 기본값', () => {
    expect(toAccount('a1', { name: '한투', kind: 'WHAT', createdAt: 5 })).toEqual({
      id: 'a1', name: '한투', kind: 'GENERAL', number: '', memo: '', createdAt: 5,
    });
    expect(toAccount('a2', { kind: 'ISA' })).toBeNull();
  });

  it('기록: 모르는 유형·날짜 없는 기록은 건너뛴다', () => {
    const base = { accountId: 'a1', date: '2026-10-09', type: 'BUY', holdingId: 'h1', quantity: 3, price: 100 };
    const r = toRecord('r1', base);
    expect(r?.type).toBe('BUY');
    expect(r?.currency).toBe('KRW');
    expect(r?.initial).toBe(false);
    expect(toRecord('r2', { ...base, type: 'FUTURE_TYPE' })).toBeNull();
    expect(toRecord('r3', { ...base, date: '2026-1-9' })).toBeNull();
  });

  it('목적통장: 예전 버전의 잔액 하나는 "기본 계좌" 로 옮긴다', () => {
    expect(toSavingsAccount('s1', { name: 'IT기기금', balance: 500000 })?.subAccounts).toEqual([
      { name: '기본 계좌', number: '', balance: 500000 },
    ]);
    expect(
      toSavingsAccount('s2', { name: '지출', subAccounts: [{ name: 'CMA', number: '1-2', balance: 3 }, 'x'] })?.subAccounts,
    ).toEqual([{ name: 'CMA', number: '1-2', balance: 3 }]);
  });

  it('목표: 날짜가 없으면 건너뛴다', () => {
    expect(toSavingsGoal('g1', { accountId: 's1', name: '휴대폰', amount: 1, dueDate: '2027-04-08' })?.type).toBe('ONE_TIME');
    expect(toSavingsGoal('g2', { accountId: 's1', name: '휴대폰', amount: 1 })).toBeNull();
  });

  it('시세: price_ 문서와 환율 문서를 읽는다', () => {
    const snapshot = toMarketSnapshot([
      { id: 'price_KR_005930', data: { latest: 80000, latestDate: '2026-10-09', closes: { '2026-10-08': 79000, bad: 1 } } },
      { id: 'price_US_AAPL', data: { closes: { '2026-10-07': 200 } } },
      { id: 'fx_USD', data: { rates: { '2026-10-08': 1400.5 } } },
      { id: 'other', data: {} },
    ]);
    expect(snapshot.prices.get('KR_005930')?.latest).toEqual({ price: 80000, date: '2026-10-09' });
    expect(snapshot.prices.get('KR_005930')?.closes.size).toBe(1);
    expect(snapshot.prices.get('US_AAPL')?.latest).toBeNull();
    expect(snapshot.usdKrw.get('2026-10-08')).toBe(1400.5);
    expect(latestQuoteDate(snapshot)).toBe('2026-10-09');
  });

  it('한투에서 불러온 기록이 있는 계좌를 연결 계좌로 본다', () => {
    const records = [
      toRecord('r1', { accountId: 'kis', date: '2026-10-09', type: 'BUY', externalId: 'KIS:123' }),
      toRecord('r2', { accountId: 'other', date: '2026-10-09', type: 'DEPOSIT' }),
    ].filter((r) => r !== null);
    expect([...linkedAccountIds(records)]).toEqual(['kis']);
  });
});
