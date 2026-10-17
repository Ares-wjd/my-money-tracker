// core PeriodReturnsTest 와 같은 케이스. 올해·연평균·월별·연도별 숫자는 공통 케이스 파일(testdata/period)에서 검사한다.
import { describe, expect, it } from 'vitest';
import type { StoredChart } from './chartStore';
import { flowSeries, longTermReturn, periodSummaryText, type FlowPoint } from './periodReturns';

const p = (date: string, valueKrw: number, investedKrw: number): FlowPoint => ({ date, valueKrw, investedKrw });

describe('기간 수익률 (PeriodReturnsTest)', () => {
  it('올해 값 없이 장기만 (summaryTextWithoutYearToDate)', () => {
    const s = longTermReturn([p('2026-08-01', 1000, 1000), p('2026-10-01', 1050, 1000)], '2026-10-01');
    expect(periodSummaryText(null, s)).toBe('기간 ▲5.00% (2개월)');
  });

  it('계좌 합치기와 계산 중 (seriesSumsAccountsAndWaitsForStored)', () => {
    const chart = (from: string, days: [string, number, number][]): StoredChart => ({
      version: 2, from, to: days[days.length - 1][0],
      days: new Map(days.map(([d, v, i]) => [d, { valueKrw: v, investedKrw: i, dividendsKrw: 0, missingPrice: false }])),
      recSig: new Map(), accSig: '', priceSig: new Map(), anchor: null,
    });
    const a = chart('2026-10-01', [['2026-10-01', 100, 100], ['2026-10-02', 50, 50]]);
    const b = chart('2026-10-02', [['2026-10-02', 50, 50]]);
    const firsts = new Map([['a', '2026-10-01'], ['b', '2026-10-02']]);
    const s = flowSeries(firsts, new Map([['a', a], ['b', b]]), '2026-10-03', new Map([['a', [50, 50]], ['b', [55, 50]]]))!;
    expect(s.map((x) => x.valueKrw)).toEqual([100, 100, 105]);
    expect(s.map((x) => x.investedKrw)).toEqual([100, 100, 100]);
    expect(flowSeries(firsts, new Map([['a', a]]), '2026-10-03', new Map())).toBeNull();
  });
});
