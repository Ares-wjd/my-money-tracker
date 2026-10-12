// core OverseasTaxTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { allocationView, isHighlighted, rebalance, toleranceBand, toleranceLabel, toleranceRange, lowerRule, upperRule, type AllocationTarget } from './allocation';
import { averageCostKrwByKey, realizedInYear, TAX_LIMIT_KRW } from './overseasTax';
import { summarize } from './portfolio';
import { account, holding, record } from './testData';

describe('해외 양도세 한도 (OverseasTaxTest)', () => {
  it('올해 실현손익: 이동평균(매수일 환율), 매도일 환율, 국내 종목 제외', () => {
    const voo = holding({ id: 'v', accountId: 'a', name: 'VOO', code: 'VOO', market: 'AMEX' });
    const kr = holding({ id: 'k', accountId: 'a', name: '삼성', code: '005930', market: 'KR' });
    const records = [
      record({ id: '1', accountId: 'a', type: 'BUY', date: '2025-06-01', holdingId: 'v', quantity: 10, price: 100 }),
      record({ id: '2', accountId: 'a', type: 'SELL', date: '2025-12-01', holdingId: 'v', quantity: 1, price: 120 }),
      record({ id: '3', accountId: 'a', type: 'SELL', date: '2026-03-02', holdingId: 'v', quantity: 4, price: 150, cost: 1 }),
      record({ id: '4', accountId: 'a', type: 'BUY', date: '2026-01-02', holdingId: 'k', quantity: 1, price: 1000 }),
      record({ id: '5', accountId: 'a', type: 'SELL', date: '2026-02-02', holdingId: 'k', quantity: 1, price: 9000 }),
    ];
    const fx = new Map([['2025-06-01', 1000], ['2025-12-01', 1100], ['2026-03-02', 1200]]);
    const at = (d: string) => fx.get(d) ?? null;
    const y = realizedInYear([voo, kr], records, 2026, at, 1300);
    expect(y.realizedKrw).toBeCloseTo(318_800, 6);
    expect(y.estimated).toBe(false);
    expect(realizedInYear([voo], records, 2025, at, 1300).realizedKrw).toBeCloseTo(32_000, 6);
    const est = realizedInYear([voo], records, 2026, (d) => (d === '2026-03-02' ? null : at(d)), 1300);
    expect(est.realizedKrw).toBeCloseTo(4 * (150 * 1300 - 100_000) - 1300, 6);
    expect(est.estimated).toBe(true);
    expect([...averageCostKrwByKey([voo, kr], records, at, 1300)]).toEqual([['US:VOO', 100_000]]);
  });

  const acc = account({ id: 'a', name: '해외' });
  const a = holding({ id: 'a1', accountId: 'a', name: 'A', code: 'AAA', market: 'NASDAQ', manualPrice: 200 });
  const b = holding({ id: 'b1', accountId: 'a', name: 'B', code: 'BBB', market: 'NASDAQ', manualPrice: 200 });
  const c = holding({ id: 'c1', accountId: 'a', name: 'C', code: 'CCC', market: 'KR', manualPrice: 100_000 });
  const records = [
    record({ id: '1', accountId: 'a', type: 'BUY', date: '2025-01-02', holdingId: 'a1', quantity: 10, price: 100 }),
    record({ id: '2', accountId: 'a', type: 'BUY', date: '2025-01-02', holdingId: 'b1', quantity: 10, price: 300 }),
    record({ id: '3', accountId: 'a', type: 'BUY', date: '2025-01-02', holdingId: 'c1', quantity: 10, price: 90_000 }),
  ];
  const accounts = summarize([acc], [a, b, c], records, 1000).accounts;
  const target: AllocationTarget = {
    id: '', name: 't', accountIds: ['a'], targets: { 'US:AAA': 20, 'US:BBB': 20, 'KR:CCC': 60 }, includeCash: false,
    threshold: 5, relative: 25, upper: null, createdAt: 0, excluded: [], names: {}, units: {}, prices: {}, noBuy: [],
  };
  const view = allocationView(target, accounts, 1000);
  const costs = new Map([['US:AAA', 100_000], ['US:BBB', 300_000]]);
  const plan = (realized: number) => rebalance(view, { realizedKrw: realized, averageCostKrw: costs, limitKrw: TAX_LIMIT_KRW, estimated: false });
  const amounts = (p: ReturnType<typeof plan>) => new Map(p.trades.map((t) => [t.row.key, t.amountKrw]));

  it('남은 한도 안이면 그대로', () => {
    const p = plan(2_300_000);
    const t = amounts(p);
    expect(t.get('US:AAA')).toBeCloseTo(-1_000_000, 6);
    expect(t.get('US:BBB')).toBeCloseTo(-1_000_000, 6);
    expect(t.get('KR:CCC')).toBeCloseTo(2_000_000, 6);
    expect(p.tax!.reduced).toEqual([]);
    expect(p.tax!.plannedGainKrw).toBeCloseTo(0, 6);
  });

  it('이익 매도를 같은 비율로 줄이고 매수도 줄인다', () => {
    const p = plan(2_800_000);
    const t = amounts(p);
    expect(t.get('US:AAA')).toBeCloseTo(-200_000, 6);
    expect(t.get('US:BBB')).toBeCloseTo(-1_000_000, 6);
    expect(t.get('KR:CCC')).toBeCloseTo(1_200_000, 6);
    expect(p.tax!.reduced.map(([, c]) => c)).toEqual([800_000]);
    expect(p.tax!.plannedGainKrw).toBeCloseTo(-400_000, 6);
    expect(p.tax!.realizedKrw + p.tax!.plannedGainKrw).toBeCloseTo(2_400_000, 6);
  });

  it('이미 한도를 넘었으면 이익 매도는 안 함', () => {
    const t = amounts(plan(3_500_000));
    expect(t.has('US:AAA')).toBe(false);
    expect(t.get('US:BBB')).toBeCloseTo(-1_000_000, 6);
    expect(t.get('KR:CCC')).toBeCloseTo(1_000_000, 6);
    expect(rebalance(view).tax).toBeNull();
    expect(rebalance(view).trades.length).toBe(3);
  });

  it('상단·하단 허용 범위를 따로', () => {
    const rule: AllocationTarget = { ...target, upper: { threshold: 10, relative: 50 } };
    expect(toleranceBand(upperRule(rule), 40)).toBe(10);
    expect(toleranceBand(lowerRule(rule), 40)).toBe(5);
    expect(toleranceBand(upperRule(rule), 10)).toBe(5);
    expect(toleranceBand(lowerRule(rule), 10)).toBe(2.5);
    expect(toleranceBand(upperRule(rule), 2)).toBe(1);
    expect(toleranceBand(lowerRule(rule), 2)).toBe(0.5);
    expect(toleranceLabel(rule)).toBe('상단 10/50 · 하단 5/25');
    const v = allocationView(rule, accounts, 1000);
    const ra = v.rows.find((r) => r.key === 'US:AAA')!;
    expect(ra.diffPoints).toBeCloseTo(20, 9);
    expect(toleranceRange(v, ra)).toEqual([15, 30]);
    expect(isHighlighted(v, ra)).toBe(true);
    const nearTargets = { 'US:AAA': 32, 'US:BBB': 8, 'KR:CCC': 60 };
    const near = allocationView({ ...rule, targets: nearTargets }, accounts, 1000);
    const nearA = near.rows.find((r) => r.key === 'US:AAA')!;
    expect(nearA.diffPoints).toBeCloseTo(8, 9);
    expect(isHighlighted(near, nearA)).toBe(false);
    expect(isHighlighted(allocationView({ ...rule, upper: null, targets: nearTargets }, accounts, 1000), nearA)).toBe(true);
  });
});
