// core ChartStoreTest 와 같은 케이스 (요약값 문자열도 같아야 한다).
import { describe, expect, it } from 'vitest';
import { pointDates, series } from './chart';
import { assembleChart, buildChart, computeChart, firstDates, hash, holdingLine, num, planChart, planDates, recordLine, recordSigs, related, type StoredChart } from './chartStore';
import { keyOf } from './market';
import type { Holding, InvestmentAccount, MarketSnapshot, Record } from '../model';
import { account, holding, record } from './testData';

const today = '2026-10-13';
const end = '2026-10-12';
const a = account({ id: 'a', name: '국내' });
const b = account({ id: 'b', name: '해외' });
const sam = holding({ id: 'h1', accountId: 'a', name: '삼성전자', code: '005930', market: 'KR' });
const voo = holding({ id: 'h2', accountId: 'b', name: 'VOO', code: 'VOO', market: 'NYSE' });
const holdings = [sam, voo];
const records: Record[] = [
  record({ id: '1', accountId: 'a', type: 'DEPOSIT', date: '2026-08-01', amount: 1_000_000, krwAmount: 1_000_000, createdAt: 1 }),
  record({ id: '2', accountId: 'a', type: 'BUY', date: '2026-08-03', holdingId: 'h1', quantity: 10, price: 70_000, createdAt: 2 }),
  record({ id: '3', accountId: 'a', type: 'CASH_BALANCE', date: '2026-09-01', amount: 300_000, createdAt: 3 }),
  record({ id: '4', accountId: 'b', type: 'DEPOSIT', date: '2026-09-10', amount: 2_000_000, krwAmount: 2_000_000, createdAt: 4 }),
  record({ id: '5', accountId: 'b', type: 'BUY', date: '2026-09-11', holdingId: 'h2', quantity: 2.5, price: 500.25, currency: 'USD', createdAt: 5 }),
];
const closes = (o: { [d: string]: number }) => new Map(Object.entries(o));
const history = (o: { [d: string]: number }) => ({ closes: closes(o), latest: null, prevClose: null });
const snapshot: MarketSnapshot = {
  prices: new Map([
    [keyOf(sam)!, history({ '2026-08-03': 70_000, '2026-09-01': 72_000, '2026-10-01': 75_000 })],
    [keyOf(voo)!, history({ '2026-09-11': 500, '2026-10-01': 520 })],
  ]),
  usdKrw: closes({ '2026-09-10': 1_380, '2026-10-01': 1_400 }),
};

function full(acc: InvestmentAccount, recs = records, snap = snapshot, hs: Holding[] = holdings, until = end): StoredChart {
  const plan = planChart(acc, hs, recs, snap, null, null, until)!;
  return buildChart(plan, computeChart(plan, acc, hs, recs, snap, null, planDates(plan)));
}

describe('그래프 일별 값 저장 (ChartStoreTest)', () => {
  it('요약값이 폰과 같다', () => {
    expect(hash('')).toBe('811c9dc5');
    expect(hash('a')).toBe('e40c292c');
    expect(num(1)).toBe('3ff0000000000000');
    expect(num(-0)).toBe('0000000000000000');
    expect(recordLine(records[4])).toBe(
      '5|b|BUY|2026-09-11|USD|0000000000000000|0000000000000000||h2|4004000000000000|407f440000000000|0000000000000000|0000000000000000||0||5',
    );
    expect(holdingLine(voo)).toBe('h2|b|VOO|NYSE|STOCK||');
    expect(recordSigs(related('b', records), end).get('2026-09-11')).toBe(hash(recordLine(records[4])));
  });

  it('저장값으로 만든 그래프 = 바로 계산한 그래프', () => {
    const stored = new Map([['a', full(a)], ['b', full(b)]]);
    expect(stored.get('a')!.from).toBe('2026-08-01');
    expect(stored.get('a')!.to).toBe(end);
    const direct = series(pointDates('DAY_5', today, '2026-08-01'), today, [a, b], holdings, records, snapshot, null);
    const v = assembleChart('DAY_5', today, firstDates([a, b], records), stored, direct[direct.length - 1]);
    expect(v.pending).toBe(false);
    expect(v.points.length).toBe(direct.length);
    direct.forEach((d, i) => {
      expect(v.points[i].date).toBe(d.date);
      expect(v.points[i].valueKrw).toBeCloseTo(d.valueKrw, 6);
      expect(v.points[i].investedKrw).toBeCloseTo(d.investedKrw, 6);
    });
    expect(planChart(a, holdings, records, snapshot, null, stored.get('a')!, end)).toBeNull();
    expect(planChart(b, holdings, records, snapshot, null, stored.get('b')!, end)).toBeNull();
  });

  it('바뀐 날짜부터만 다시 계산', () => {
    const sa = full(a);
    const added = [...records, record({ id: '6', accountId: 'a', type: 'DEPOSIT', date: '2026-09-20', amount: 1, krwAmount: 1 })];
    const plan = planChart(a, holdings, added, snapshot, null, sa, end)!;
    expect(plan.start).toBe('2026-09-20');
    expect([...plan.keep.keys()].sort().pop()).toBe('2026-09-19');
    expect(planChart(b, holdings, added, snapshot, null, full(b), end)).toBeNull();
    const early = [...records, record({ id: '7', accountId: 'a', type: 'WITHDRAW', date: '2026-08-20', amount: 1, krwAmount: 1 })];
    expect(planChart(a, holdings, early, snapshot, null, sa, end)!.start).toBe('2026-08-01');
    const removed = records.filter((r) => r.id !== '5');
    expect(planChart(b, holdings, removed, snapshot, null, full(b), end)!.start).toBe('2026-09-11');
  });

  it('시세·새 날·종목 정보·전체 다시 계산', () => {
    const sb = full(b);
    const newClose: MarketSnapshot = { ...snapshot, prices: new Map([...snapshot.prices, [keyOf(voo)!, history({ '2026-09-11': 500, '2026-10-01': 520, '2026-10-08': 530 })]]) };
    expect(planChart(b, holdings, records, newClose, null, sb, end)!.start).toBe('2026-10-01');
    const newFx: MarketSnapshot = { ...snapshot, usdKrw: closes({ '2026-09-10': 1_380, '2026-09-15': 1_390, '2026-10-01': 1_400 }) };
    expect(planChart(b, holdings, records, newFx, null, sb, end)!.start).toBe(sb.from);
    const next = planChart(b, holdings, records, snapshot, null, sb, today)!;
    expect(next.start).toBe(today);
    expect(planDates(next)).toEqual([today]);
    const edited = [sam, { ...voo, manualPrice: 510, manualPriceDate: '2026-10-05' }];
    expect(planChart(b, edited, records, snapshot, null, sb, end)!.start).toBe(sb.from);
    expect(planChart(b, holdings, records, snapshot, null, sb, end, true)!.start).toBe(sb.from);
    expect(planChart(account({ id: 'c', name: '빈 계좌' }), holdings, records, snapshot, null, null, end)).toBeNull();
  });

  it('아직 계산하지 않은 날은 뺀다', () => {
    const todayPoint = series([today], today, [a, b], holdings, records, snapshot, null)[0];
    const v = assembleChart('MONTH', today, firstDates([a, b], records), new Map([['a', full(a)]]), todayPoint);
    expect(v.pending).toBe(true);
    expect(v.points.map((p) => p.date)).toEqual(['2026-08-31', today]);
  });
});
