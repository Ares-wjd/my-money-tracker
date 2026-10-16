// core PeriodReturnsTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import type { StoredChart } from './chartStore';
import { flowSeries, longTermReturn, monthlyReturns, periodSummaryText, yearToDate, yearlyReturns, type FlowPoint } from './periodReturns';

const p = (date: string, valueKrw: number, investedKrw: number): FlowPoint => ({ date, valueKrw, investedKrw });

describe('기간 수익률 (PeriodReturnsTest)', () => {
  it('올해 수익률은 연중 입금을 남은 기간만큼 (yearToDateWeightsMidYearDeposit)', () => {
    const s = [p('2025-12-31', 1000, 1000), p('2026-07-02', 2000, 2000), p('2026-12-31', 2200, 2000)];
    const ytd = yearToDate(s, '2026-12-31')!;
    expect(ytd.profitKrw).toBeCloseTo(200, 9);
    expect(ytd.netFlowKrw).toBeCloseTo(1000, 9);
    expect(ytd.rate!).toBeCloseTo(200 / (1000 + (1000 * 183) / 365), 12);
  });

  it('1년 이상이면 연평균, 미만이면 기간 수익률 (annualAfterOneYearElsePeriod)', () => {
    const long = longTermReturn([p('2025-01-01', 1000, 1000), p('2026-01-01', 1100, 1000)], '2026-01-01')!;
    expect(long.annual!).toBeCloseTo(0.1, 6);
    expect(long.period).toBeNull();

    const short = [p('2026-08-01', 1000, 1000), p('2026-10-01', 1050, 1000)];
    const s = longTermReturn(short, '2026-10-01')!;
    expect(s.annual).toBeNull();
    expect(s.period!).toBeCloseTo(0.05, 12);
    expect(s.days).toBe(61);
    expect(periodSummaryText(null, s)).toBe('기간 ▲5.00% (2개월)');
    expect(periodSummaryText(yearToDate(short, '2026-10-01'), s)).toBe('올해 ▲5.00% (+50원) · 기간 ▲5.00% (2개월)');
  });

  it('월별·연도별 (monthsAndYears)', () => {
    const s = [p('2026-07-31', 1000, 1000), p('2026-08-31', 1100, 1000), p('2026-09-30', 1210, 1000)];
    const m = monthlyReturns(s, '2026-09-30', 3);
    expect(m.map((r) => r.from)).toEqual(['2026-07-31', '2026-08-01', '2026-09-01']);
    expect(m[0].rate!).toBeCloseTo(0, 12);
    expect(m[1].profitKrw).toBeCloseTo(100, 9);
    expect(m[1].rate!).toBeCloseTo(0.1, 12);
    expect(m[2].rate!).toBeCloseTo(0.1, 12);
    const y = yearlyReturns(s, '2026-09-30');
    expect(y.length).toBe(1);
    expect(y[0].profitKrw).toBeCloseTo(210, 9);
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
