// core PriceEstimatorTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import type { Holding, MarketSnapshot } from '../model';
import { keyOf, PriceEstimator } from './market';
import { holding, record } from './testData';

const gold = holding({ id: 'g', accountId: 'a', name: '금현물', code: 'M04020000', market: 'KR', assetType: 'GOLD' });
const goldB = { ...gold, id: 'g2', accountId: 'b' };
const buy = (id: string, h: Holding, date: string, price: number, quantity = 1) =>
  record({ id, accountId: h.accountId, type: 'BUY', date, holdingId: h.id, quantity, price });
const snap = (closes: { [d: string]: number }): MarketSnapshot => ({
  prices: new Map([[keyOf(gold)!, { closes: new Map(Object.entries(closes)), latest: null, prevClose: null }]]),
  usdKrw: new Map(),
});
const empty: MarketSnapshot = { prices: new Map(), usdKrw: new Map() };

describe('시세가 없는 날의 가격 추정 (PriceEstimatorTest)', () => {
  it('매수 단가와 종가 사이를 날짜 비율로', () => {
    // 1/1 매수 20만원, 1/3 종가 18만원 → 1/2 는 19만원
    const e = new PriceEstimator([gold], [buy('1', gold, '2026-01-01', 200_000)], snap({ '2026-01-03': 180_000 }));
    expect(e.at(gold, '2026-01-01')!.price).toBeCloseTo(200_000, 6);
    expect(e.at(gold, '2026-01-02')!.price).toBeCloseTo(190_000, 6);
    expect(e.at(gold, '2026-01-03')!.price).toBeCloseTo(180_000, 6);
    expect(e.at(gold, '2025-12-20')!.price).toBeCloseTo(200_000, 6);
    expect(e.at(gold, '2026-02-01')!.price).toBeCloseTo(180_000, 6);
  });

  it('휴일은 전 거래일 종가, 긴 공백은 날짜 비율로', () => {
    const e = new PriceEstimator([gold], [], snap({ '2026-01-09': 100, '2026-01-12': 110, '2026-02-11': 140 }));
    expect(e.at(gold, '2026-01-10')!.price).toBe(100);
    expect(e.at(gold, '2026-01-11')!.price).toBe(100);
    expect(e.at(gold, '2026-01-27')!.price).toBeCloseTo(125, 9);
  });

  it('같은 날 종가 우선, 매매는 다른 계좌까지 수량 가중평균', () => {
    const e = new PriceEstimator(
      [gold, goldB],
      [buy('1', gold, '2026-01-05', 100, 1), buy('2', goldB, '2026-01-05', 130, 2), buy('3', gold, '2026-01-07', 999)],
      snap({ '2026-01-07': 150 }),
    );
    expect(e.at(gold, '2026-01-05')!.price).toBeCloseTo(120, 9);
    expect(e.at(goldB, '2026-01-07')!.price).toBe(150);
    expect(e.at(gold, '2026-01-06')!.price).toBeCloseTo(135, 9);
  });

  it('직접 입력 가격, 알고 있는 가격 없음', () => {
    const fund = holding({ id: 'f', accountId: 'a', name: '펀드', market: 'KR', assetType: 'FUND', manualPrice: 1_200, manualPriceDate: '2026-03-01' });
    const e = new PriceEstimator([fund], [buy('1', fund, '2026-01-01', 1_000)], empty);
    expect(e.at(fund, '2026-02-01')!.price).toBeCloseTo(1_000 + (200 * 31) / 59, 9);
    expect(e.at(fund, '2026-04-01')!.price).toBe(1_200);
    expect(new PriceEstimator([gold], [], empty).at(gold, '2026-01-01')).toBeNull();
  });
});
