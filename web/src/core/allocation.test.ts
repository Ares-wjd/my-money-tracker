// core AllocationTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { CASH_KEY, allocationView, buyOnly, isHighlighted, keyOf, manualKey, rebalance, type AllocationTarget } from './allocation';
import { summarize } from './portfolio';
import { account, holding, record } from './testData';

const day = '2026-10-01';
const isa = account({ id: 'a', name: 'ISA' });
const other = account({ id: 'b', name: '일반' });
const sp1 = holding({ id: 'h1', accountId: 'a', name: 'TIGER 미국S&P500', code: '360750', manualPrice: 100 });
const bond = holding({ id: 'h2', accountId: 'a', name: '채권 ETF', code: 'aaa', manualPrice: 100 });
const sp2 = holding({ id: 'h3', accountId: 'b', name: 'TIGER 미국S&P500', code: '360750', manualPrice: 100 });
const gold = holding({ id: 'h4', accountId: 'a', name: '금 ETF', code: 'GLD1', manualPrice: 50 });
const records = [
  record({ id: '1', accountId: 'a', type: 'BUY', date: day, holdingId: 'h1', quantity: 10, price: 90 }),
  record({ id: '2', accountId: 'a', type: 'BUY', date: day, holdingId: 'h2', quantity: 5, price: 100 }),
  record({ id: '3', accountId: 'b', type: 'BUY', date: day, holdingId: 'h3', quantity: 5, price: 100 }),
  record({ id: '4', accountId: 'a', type: 'CASH_BALANCE', date: day, amount: 500 }),
];
const accounts = summarize([isa, other], [sp1, bond, sp2, gold], records, null).accounts;
const target: AllocationTarget = {
  id: '', name: '자산배분', accountIds: ['a', 'b'], targets: { 'KR:360750': 50, 'KR:AAA': 30, [CASH_KEY]: 20 },
  includeCash: true, threshold: 3, createdAt: 0, excluded: [], names: {},
};
const byKey = <T extends { key: string }>(rows: T[]) => new Map(rows.map((r) => [r.key, r]));

describe('비중 (AllocationTest)', () => {
  it('같은 종목은 계좌가 달라도 합친다', () => {
    const view = allocationView(target, accounts, null);
    expect(view.totalKrw).toBe(2500);
    const rows = byKey(view.rows);
    expect([...rows.keys()].sort()).toEqual(['CASH', 'KR:360750', 'KR:AAA']);
    const sp = rows.get('KR:360750')!;
    expect(sp.quantity).toBe(15);
    expect(sp.currentWeight).toBeCloseTo(0.6, 9);
    expect(sp.diffPoints).toBeCloseTo(10, 9);
    expect(rows.get('KR:AAA')!.diffPoints).toBeCloseTo(-10, 9);
    expect(rows.get('CASH')!.diffPoints).toBeCloseTo(0, 9);
    expect(isHighlighted(view, sp)).toBe(true);
    expect(isHighlighted(view, rows.get('CASH')!)).toBe(false);
    expect(view.rows.map((r) => r.key)).toEqual(['KR:360750', 'KR:AAA', 'CASH']);
  });

  it('예수금을 빼면 종목만으로', () => {
    const view = allocationView({ ...target, includeCash: false, targets: { 'KR:360750': 60, 'KR:AAA': 40 } }, accounts, null);
    expect(view.totalKrw).toBe(2000);
    expect(byKey(view.rows).get('KR:360750')!.currentWeight).toBeCloseTo(0.75, 9);
  });

  it('아직 안 산 목표 종목도 보여준다', () => {
    const view = allocationView({ ...target, targets: { ...target.targets, 'KR:GLD1': 10 } }, accounts, null);
    const g = byKey(view.rows).get('KR:GLD1')!;
    expect(g.valueKrw).toBe(0);
    expect(g.priceKrw).toBe(50);
    expect(g.name).toBe('금 ETF');
  });

  it('팔지 않고 사기만 해서 부족분을 채운다', () => {
    const view = allocationView(target, accounts, null);
    const buys = byKey(buyOnly(view, 500).map((s) => ({ ...s, key: s.row.key })));
    expect([...buys.keys()].sort()).toEqual(['CASH', 'KR:AAA']);
    expect(buys.get('KR:AAA')!.amountKrw).toBeCloseTo(400, 9);
    expect(buys.get('KR:AAA')!.shares).toBe(4);
    expect(buys.get('CASH')!.amountKrw).toBeCloseTo(100, 9);
    expect(buys.get('CASH')!.shares).toBeNull();

    const withGold = [...records, record({ id: '5', accountId: 'a', type: 'BUY', date: day, holdingId: 'h4', quantity: 1, price: 50 })];
    const accounts2 = summarize([isa, other], [sp1, bond, sp2, gold], withGold, null).accounts;
    const small = byKey(buyOnly(allocationView(target, accounts2, null), 500).map((s) => ({ ...s, key: s.row.key })));
    expect(small.get('KR:360750')!.amountKrw).toBeCloseTo((500 * 25) / 550, 9);
    expect(small.get('KR:AAA')!.amountKrw).toBeCloseTo((500 * 415) / 550, 9);
    expect(small.get('CASH')!.amountKrw).toBeCloseTo((500 * 110) / 550, 9);
    expect(small.has('KR:GLD1')).toBe(false);
  });

  it('사고팔아 정확히 맞추기', () => {
    const trades = byKey(rebalance(allocationView(target, accounts, null)).map((s) => ({ ...s, key: s.row.key })));
    expect(trades.get('KR:360750')!.amountKrw).toBeCloseTo(-250, 9);
    expect(trades.get('KR:AAA')!.amountKrw).toBeCloseTo(250, 9);
    expect(trades.has('CASH')).toBe(false);
  });

  it('제외한 종목은 빼고 계산한다', () => {
    const view = allocationView({ ...target, targets: { 'KR:360750': 80, [CASH_KEY]: 20, 'KR:AAA': 30 }, excluded: ['KR:AAA'] }, accounts, null);
    expect(view.totalKrw).toBe(2000);
    expect(view.rows.map((r) => r.key)).toEqual(['KR:360750', 'CASH']);
    expect(view.rows[0].currentWeight).toBeCloseTo(0.75, 9);
    expect(buyOnly(view, 1000).some((s) => s.row.key === 'KR:AAA')).toBe(false);
  });

  it('계좌에 없는 종목을 직접 추가', () => {
    const key = manualKey(true, 'qqqm');
    expect(key).toBe('US:QQQM');
    const view = allocationView(
      { ...target, targets: { 'KR:360750': 50, 'KR:AAA': 30, [key]: 20 }, includeCash: false, names: { [key]: 'Invesco QQQM' } },
      accounts,
      null,
    );
    const q = view.rows.find((r) => r.key === key)!;
    expect(q.name).toBe('Invesco QQQM');
    expect(q.currentWeight).toBe(0);
    expect(q.isForeign).toBe(true);
    const buy = buyOnly(view, 1000).find((s) => s.row.key === key)!;
    expect(buy.amountKrw).toBeGreaterThan(0);
    expect(buy.shares).toBeNull();
  });

  it('해외는 거래소와 상관없이 같은 키', () => {
    const nyse = holding({ id: 'x', accountId: 'a', name: 'VOO', code: 'voo', market: 'NYSE' });
    const other = holding({ id: 'y', accountId: 'b', name: 'VOO', code: 'VOO', market: 'US_OTHER' });
    expect(keyOf(nyse)).toBe('US:VOO');
    expect(keyOf(other)).toBe(keyOf(nyse));
    expect(keyOf(sp1)).toBe('KR:360750');
    expect(keyOf(holding({ id: 'f', accountId: 'a', name: '펀드' }))).toBe('id:f');
  });
});
