// core PortfolioCalculatorTest · DisplayCurrencyTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import type { Record, RecordType } from '../model';
import { convert, displayConversion, position, quantityAt, summarize } from './portfolio';
import { account, holding, record } from './testData';

const day1 = '2026-01-02';
const day2 = '2026-02-02';
const day3 = '2026-03-02';
const kr = account({ id: 'kr', name: '국내' });
const us = account({ id: 'us', name: '해외' });
const samsung = holding({ id: 's', accountId: 'kr', name: '삼성전자', code: '005930', market: 'KR', manualPrice: 80_000 });
const apple = holding({ id: 'a', accountId: 'us', name: 'Apple', code: 'AAPL', market: 'NASDAQ', manualPrice: 200 });

let seq = 0;
function rec(accountId: string, type: RecordType, date: string, p: Partial<Record> = {}): Record {
  const createdAt = seq++;
  return record({ id: `r${createdAt}`, accountId, type, date, createdAt, ...p });
}

describe('투자 계산 (PortfolioCalculatorTest)', () => {
  it('이동평균 단가와 실현손익', () => {
    const p = position(samsung, [
      rec('kr', 'BUY', day1, { holdingId: 's', quantity: 10, price: 70_000, fee: 100 }),
      rec('kr', 'BUY', day2, { holdingId: 's', quantity: 10, price: 80_000, fee: 100 }),
      rec('kr', 'SELL', day3, { holdingId: 's', quantity: 5, price: 90_000, fee: 100, tax: 900 }),
    ]);
    expect(p.quantity).toBeCloseTo(15, 9);
    expect(p.averagePrice).toBeCloseTo(75_000, 9);
    expect(p.realizedProfit).toBeCloseTo(74_000, 9);
    expect(p.feesAndTaxes).toBeCloseTo(1_200, 9);
  });

  it('전부 팔면 평균단가가 초기화된다', () => {
    const p = position(samsung, [
      rec('kr', 'BUY', day1, { holdingId: 's', quantity: 2, price: 100 }),
      rec('kr', 'SELL', day2, { holdingId: 's', quantity: 2, price: 150 }),
      rec('kr', 'BUY', day3, { holdingId: 's', quantity: 1, price: 120 }),
    ]);
    expect(p.quantity).toBeCloseTo(1, 9);
    expect(p.averagePrice).toBeCloseTo(120, 9);
    expect(p.realizedProfit).toBeCloseTo(100, 9);
  });

  it('투자금·예수금·수익률', () => {
    const summary = summarize([kr], [samsung], [
      rec('kr', 'DEPOSIT', day1, { amount: 1_000_000, krwAmount: 1_000_000 }),
      rec('kr', 'BUY', day1, { holdingId: 's', quantity: 10, price: 70_000, fee: 500 }),
      rec('kr', 'DIVIDEND', day2, { holdingId: 's', amount: 3_000 }),
      rec('kr', 'CASH_BALANCE', day2, { amount: 302_700 }),
    ], null);
    const a = summary.accounts[0];
    expect(a.investedKrw).toBeCloseTo(1_000_000, 9);
    expect(a.cash.krw).toBeCloseTo(302_700, 9);
    expect(a.cashDate).toBe(day2);
    expect(a.valueKrw).toBeCloseTo(1_102_700, 9);
    expect(a.profitKrw).toBeCloseTo(102_700, 9);
    expect(a.profitExDividendsKrw).toBeCloseTo(99_700, 9);
    expect(a.returnRate!).toBeCloseTo(0.1027, 9);
    expect(a.returnRateExDividends!).toBeCloseTo(0.0997, 9);
    const h = a.holdings[0];
    expect(h.unrealizedProfit!).toBeCloseTo(100_000, 9);
    expect(h.returnRate!).toBeCloseTo(100_000 / 700_000, 9);
    expect(h.returnRateWithDividends!).toBeCloseTo(103_000 / 700_000, 9);
    expect(a.missingFx).toBe(false);
  });

  it('계좌 간 이체', () => {
    const summary = summarize([kr, us], [samsung, apple], [
      rec('kr', 'DEPOSIT', day1, { amount: 2_000_000, krwAmount: 2_000_000 }),
      rec('kr', 'TRANSFER', day1, { amount: 1_000_000, krwAmount: 1_000_000, toAccountId: 'us' }),
      rec('us', 'EXCHANGE', day2, { currency: 'KRW', amount: 700, krwAmount: 980_000 }),
      rec('us', 'BUY', day2, { holdingId: 'a', quantity: 3, price: 180, fee: 1 }),
      rec('us', 'DIVIDEND', day3, { holdingId: 'a', amount: 2 }),
      rec('us', 'CASH_BALANCE', day3, { currency: 'KRW', amount: 20_000 }),
      rec('us', 'CASH_BALANCE', day3, { currency: 'USD', amount: 161 }),
    ], 1_400);
    const krSummary = summary.accounts.find((a) => a.account.id === 'kr')!;
    const usSummary = summary.accounts.find((a) => a.account.id === 'us')!;
    expect(krSummary.investedKrw).toBeCloseTo(1_000_000, 9);
    expect(usSummary.investedKrw).toBeCloseTo(1_000_000, 9);
    expect(summary.investedKrw).toBeCloseTo(2_000_000, 9);
    expect(usSummary.cash.krw).toBeCloseTo(20_000, 9);
    expect(usSummary.cash.usd).toBeCloseTo(161, 9);
    expect(krSummary.cash.krw).toBe(0);
    expect(usSummary.valueKrw).toBeCloseTo(840_000 + 20_000 + 225_400, 9);
    expect(usSummary.dividendsKrw).toBeCloseTo(2 * 1_400, 9);
  });

  it('가격·환율이 없으면 표시', () => {
    const summary = summarize([us], [{ ...apple, manualPrice: null }], [rec('us', 'BUY', day1, { holdingId: 'a', quantity: 1, price: 100 })], null);
    const a = summary.accounts[0];
    expect(a.missingFx).toBe(true);
    expect(a.missingPrice).toBe(true);
    expect(a.holdings[0].marketValueKrw).toBeNull();
  });

  it('asOf 이후 기록은 빼고 계산', () => {
    const summary = summarize([kr], [], [
      rec('kr', 'DEPOSIT', day1, { amount: 100, krwAmount: 100 }),
      rec('kr', 'DEPOSIT', day3, { amount: 50, krwAmount: 50 }),
    ], null, undefined, day2);
    expect(summary.investedKrw).toBeCloseTo(100, 9);
  });

  it('그날 보유 수량 (수정 중인 기록 제외)', () => {
    const buy = rec('kr', 'BUY', day1, { holdingId: 's', quantity: 10, price: 1 });
    const sell = rec('kr', 'SELL', day2, { holdingId: 's', quantity: 4, price: 1 });
    expect(quantityAt(samsung, [buy, sell], day3)).toBeCloseTo(6, 9);
    expect(quantityAt(samsung, [buy, sell], day3, sell.id)).toBeCloseTo(10, 9);
    expect(quantityAt(samsung, [buy, sell], '2026-01-01')).toBe(0);
  });

  it('예수금은 계산하지 않고 가장 최근 기록을 쓴다', () => {
    const records = [
      rec('kr', 'BUY', day1, { holdingId: 's', quantity: 10, price: 70_000 }),
      rec('kr', 'CASH_BALANCE', day1, { amount: 10_000 }),
      rec('kr', 'CASH_BALANCE', day2, { amount: 50_000 }),
      rec('kr', 'CASH_BALANCE', day2, { currency: 'USD', amount: 12.5 }),
      rec('kr', 'CASH_ADJUST', day2, { amount: 999 }),
      rec('kr', 'CASH_BALANCE', day3, { amount: 70_000 }),
    ];
    const now = summarize([kr], [samsung], records, 1_000).accounts[0];
    expect(now.cash.krw).toBeCloseTo(70_000, 9);
    expect(now.cash.usd).toBeCloseTo(12.5, 9);
    expect(now.cashDate).toBe(day3);
    expect(now.valueKrw).toBeCloseTo(800_000 + 70_000 + 12_500, 9);
    expect(summarize([kr], [samsung], records, 1_000, undefined, day2).accounts[0].cash.krw).toBeCloseTo(50_000, 9);
    const none = summarize([kr], [samsung], records, null, undefined, '2026-01-01').accounts[0];
    expect(none.cash.krw).toBe(0);
    expect(none.cashDate).toBeNull();
  });

  it('가격 함수를 쓴다', () => {
    const summary = summarize([kr], [samsung], [rec('kr', 'BUY', day1, { holdingId: 's', quantity: 1, price: 1 })], null, () => ({ price: 5, date: day1 }));
    expect(summary.accounts[0].holdings[0].marketValue).toBeCloseTo(5, 9);
  });
});

describe('해외 종목 원화/외화 보기 (DisplayCurrencyTest)', () => {
  it('원화 보기는 해외 종목만 환산', () => {
    const usInKrw = displayConversion('USD', 'KRW', 1_400);
    expect(usInKrw.currency).toBe('KRW');
    expect(convert(usInKrw, 100)).toBeCloseTo(140_000, 9);
    const krInKrw = displayConversion('KRW', 'KRW', 1_400);
    expect(krInKrw.currency).toBe('KRW');
    expect(convert(krInKrw, 100)).toBe(100);
  });

  it('외화 보기는 종목 통화 그대로', () => {
    const c = displayConversion('USD', 'FOREIGN', 1_400);
    expect(c.currency).toBe('USD');
    expect(convert(c, 100)).toBe(100);
  });

  it('환율이 없으면 환산할 수 없다', () => {
    expect(convert(displayConversion('USD', 'KRW', null), 100)).toBeNull();
    expect(convert(displayConversion('USD', 'FOREIGN', null), 100)).toBe(100);
  });
});
