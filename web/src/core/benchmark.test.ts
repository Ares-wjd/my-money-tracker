// core BenchmarkTest 와 같은 케이스. 숫자 계산은 공통 테스트 케이스(testdata/benchmark)에서도 검사한다.
import { describe, expect, it } from 'vitest';
import type { MarketSnapshot, PriceHistory } from '../model';
import { compareText, covers, simulate } from './benchmark';

describe('지수 비교 (BenchmarkTest)', () => {
  it('비교 한 줄: 낮은 것 · 높은 것 · 같은 것 (compareTextGroupsLowerHigherSame)', () => {
    expect(compareText(0.082, [['SPX', 0.121], ['COMP', 0.15], ['KOSPI', 0.053]])).toBe('S&P500보다 3.90%p, 나스닥보다 6.80%p 낮고, KOSPI보다 2.90%p 높아요');
    expect(compareText(0.06, [['SPX', null], ['KOSPI', 0.05]])).toBe('KOSPI보다 1.00%p 높아요');
    expect(compareText(0.05, [['SPX', 0.05]])).toBe('S&P500 수익률과 같아요');
    expect(compareText(null, [['SPX', 0.05]])).toBeNull();
    expect(compareText(0.05, [['SPX', null]])).toBeNull();
  });

  it('출금은 좌수를 팔고, 달러 지수에 환율이 없으면 null (withdrawalSellsUnitsAndMissingFxGivesNull)', () => {
    const kospi: PriceHistory = { closes: new Map([['2026-03-02', 100], ['2026-03-03', 200]]), latest: null, prevClose: null };
    const snap: MarketSnapshot = { prices: new Map([['IDX_KOSPI', kospi], ['IDX_SPX', kospi]]), usdKrw: new Map() };
    const series = [
      { date: '2026-03-02', valueKrw: 1000, investedKrw: 1000 },
      { date: '2026-03-03', valueKrw: 900, investedKrw: 500 },
    ];
    expect(simulate(series, 'KOSPI', snap, '2026-03-10')!.map((p) => p.valueKrw)).toEqual([1000, 1500]);
    expect(simulate(series, 'SPX', snap, '2026-03-10')).toBeNull();
    expect(simulate(series, 'COMP', snap, '2026-03-10')).toBeNull();
    expect(covers('KOSPI', snap, '2026-02-25')).toBe(true);
    expect(covers('KOSPI', snap, '2026-02-20')).toBe(false);
  });
});
