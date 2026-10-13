// core AllocationTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { CASH_KEY, allocationView, buyOnly, codelessKey, isCodeless, isHighlighted, keyOf, manualKey, rebalance, toleranceBand, toleranceLabel, toleranceRange, type AllocationTarget } from './allocation';
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
  record({ id: '5', accountId: 'b', type: 'CASH_BALANCE', date: day, amount: 0 }),
];
const accounts = summarize([isa, other], [sp1, bond, sp2, gold], records, null).accounts;
const target: AllocationTarget = {
  id: '', name: '자산배분', accountIds: ['a', 'b'], targets: { 'KR:360750': 50, 'KR:AAA': 30, [CASH_KEY]: 20 },
  includeCash: true, threshold: 3, relative: null, upper: null, createdAt: 0, excluded: [], names: {}, units: {}, prices: {}, noBuy: [],
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

  it('허용 범위 5/25 룰 (목표 0% 는 강조 안 함)', () => {
    const rule = { ...target, threshold: 5, relative: 25 };
    expect(toleranceBand(rule, 40)).toBe(5);
    expect(toleranceBand(rule, 20)).toBe(5);
    expect(toleranceBand(rule, 10)).toBe(2.5);
    expect(toleranceBand(rule, 2)).toBe(0.5);
    expect(toleranceBand({ threshold: 3, relative: null }, 10)).toBe(3);
    expect(toleranceBand({ threshold: 0, relative: 25 }, 10)).toBe(2.5);
    expect(toleranceBand({ threshold: 0, relative: 0 }, 10)).toBeNull();
    expect(toleranceLabel(rule)).toBe('5/25 룰');
    expect(toleranceLabel({ threshold: 3, relative: null, upper: null })).toBe('±3%p');

    const view = allocationView(rule, accounts, null);
    const rows = byKey(view.rows);
    const sp = rows.get('KR:360750')!;
    expect(toleranceRange(view, sp)).toEqual([45, 55]);
    expect(isHighlighted(view, sp)).toBe(true);
    expect(isHighlighted(view, rows.get('KR:AAA')!)).toBe(true);
    expect(isHighlighted(view, rows.get('CASH')!)).toBe(false);

    const zero = allocationView({ ...rule, targets: { 'KR:360750': 0, 'KR:AAA': 80, [CASH_KEY]: 20 } }, accounts, null);
    const zeroRow = zero.rows.find((r) => r.key === 'KR:360750')!;
    expect(zeroRow.diffPoints).toBeCloseTo(60, 9);
    expect(isHighlighted(zero, zeroRow)).toBe(false);
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
    const buys = byKey(buyOnly(view, 500).trades.map((s) => ({ ...s, key: s.row.key })));
    expect([...buys.keys()].sort()).toEqual(['CASH', 'KR:AAA']);
    expect(buys.get('KR:AAA')!.amountKrw).toBeCloseTo(400, 9);
    expect(buys.get('KR:AAA')!.shares).toBe(4);
    expect(buys.get('CASH')!.amountKrw).toBeCloseTo(100, 9);
    expect(buys.get('CASH')!.shares).toBeNull();

    const withGold = [...records, record({ id: '5', accountId: 'a', type: 'BUY', date: day, holdingId: 'h4', quantity: 1, price: 50 })];
    const accounts2 = summarize([isa, other], [sp1, bond, sp2, gold], withGold, null).accounts;
    // 금액 단위를 0.01원으로 두면 내림 영향 없이 비율 그대로
    const fine = { ...target, units: { 'KR:360750': 0.01, 'KR:AAA': 0.01 } };
    const small = byKey(buyOnly(allocationView(fine, accounts2, null), 500).trades.map((s) => ({ ...s, key: s.row.key })));
    expect(Math.abs(small.get('KR:360750')!.amountKrw - (500 * 25) / 550)).toBeLessThan(0.02);
    expect(Math.abs(small.get('KR:AAA')!.amountKrw - (500 * 415) / 550)).toBeLessThan(0.02);
    expect(Math.abs(small.get('CASH')!.amountKrw - (500 * 110) / 550)).toBeLessThan(0.02);
    expect(small.has('KR:GLD1')).toBe(false);
  });

  it('사고팔아 정확히 맞추기', () => {
    const trades = byKey(rebalance(allocationView(target, accounts, null)).trades.map((s) => ({ ...s, key: s.row.key })));
    // 1주 100원 단위로 0 쪽 내림: −250 → −200, +250 → +200
    expect(trades.get('KR:360750')!.amountKrw).toBeCloseTo(-200, 9);
    expect(trades.get('KR:AAA')!.amountKrw).toBeCloseTo(200, 9);
    expect(trades.has('CASH')).toBe(false);
  });

  it('제외한 종목은 빼고 계산한다', () => {
    const view = allocationView({ ...target, targets: { 'KR:360750': 80, [CASH_KEY]: 20, 'KR:AAA': 30 }, excluded: ['KR:AAA'] }, accounts, null);
    expect(view.totalKrw).toBe(2000);
    expect(view.rows.map((r) => r.key)).toEqual(['KR:360750', 'CASH']);
    expect(view.rows[0].currentWeight).toBeCloseTo(0.75, 9);
    expect(buyOnly(view, 1000).trades.some((s) => s.row.key === 'KR:AAA')).toBe(false);
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
    const buy = buyOnly(view, 1000).trades.find((s) => s.row.key === key)!;
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

  it('1주 단위로 내리고 남은 돈은 가장 부족한 종목·예수금으로', () => {
    const view = allocationView(target, accounts, null);
    const plan = buyOnly(view, 450);
    const t = byKey(plan.trades.map((s) => ({ ...s, key: s.row.key })));
    expect(t.get('KR:AAA')!.amountKrw).toBeCloseTo(400, 9);
    expect(t.get('KR:AAA')!.shares).toBe(4);
    expect(t.get('KR:AAA')!.fractional).toBe(false);
    expect(t.get('CASH')!.amountKrw).toBeCloseTo(50, 9);
    expect(plan.leftoverKrw).toBe(0);
    const noCash = allocationView({ ...target, includeCash: false, targets: { 'KR:360750': 60, 'KR:AAA': 40 } }, accounts, null);
    const p2 = buyOnly(noCash, 450);
    expect(p2.leftoverKrw).toBeCloseTo(450 - p2.trades.reduce((s, x) => s + x.amountKrw, 0), 9);
    expect(p2.leftoverKrw).toBeLessThan(100);
  });

  it('금액 단위와 직접 입력한 현재가', () => {
    const view = allocationView({ ...target, units: { 'KR:AAA': 30 } }, accounts, null);
    const aaa = buyOnly(view, 450).trades.find((s) => s.row.key === 'KR:AAA')!;
    expect(aaa.fractional).toBe(true);
    expect(aaa.amountKrw % 30).toBeCloseTo(0, 9);
    expect(aaa.shares).toBeCloseTo(aaa.amountKrw / 100, 9);

    const ipo = codelessKey(true, 'abc');
    expect(isCodeless(ipo)).toBe(true);
    const v2 = allocationView(
      { ...target, targets: { 'KR:360750': 50, 'KR:AAA': 30, [ipo]: 20 }, includeCash: false, names: { [ipo]: '상장 예정 공모주' }, prices: { [ipo]: 10 } },
      accounts,
      1000,
    );
    const row = v2.rows.find((r) => r.key === ipo)!;
    expect(row.name).toBe('상장 예정 공모주');
    expect(row.priceKrw).toBe(10_000);
    expect(row.isForeign).toBe(true);
    const buy = buyOnly(v2, 50_000).trades.find((s) => s.row.key === ipo)!;
    expect(buy.amountKrw % 10_000).toBeCloseTo(0, 9);
    expect(buy.shares).toBeCloseTo(buy.amountKrw / 10_000, 9);
  });

  it('사고팔기도 매수 단위로 0 쪽 내림', () => {
    const trades = byKey(rebalance(allocationView({ ...target, units: { 'KR:360750': 1000 } }, accounts, null)).trades.map((s) => ({ ...s, key: s.row.key })));
    expect(trades.has('KR:360750')).toBe(false);
    expect(trades.get('KR:AAA')!.amountKrw).toBeCloseTo(200, 9);
  });

  it('지금 매수 안 함 종목은 계산기에서만 뺀다', () => {
    const view = allocationView({ ...target, noBuy: ['KR:AAA'], units: { 'KR:360750': 0.01 } }, accounts, null);
    expect(view.rows.some((r) => r.key === 'KR:AAA')).toBe(true);
    const t = byKey(buyOnly(view, 500).trades.map((s) => ({ ...s, key: s.row.key })));
    expect(t.has('KR:AAA')).toBe(false);
    expect(Math.abs(t.get('KR:360750')!.amountKrw - (400 * 50) / 70)).toBeLessThan(0.02);
    expect(t.get('CASH')!.amountKrw).toBeCloseTo(500 - t.get('KR:360750')!.amountKrw, 6);
    expect(rebalance(view).trades.some((s) => s.row.key === 'KR:AAA')).toBe(false);
  });

  it('동점이면 단위 때문에 못 산 더 부족한 종목을 산다', () => {
    const pricey = holding({ id: 'p', accountId: 'a', name: '비싼 종목', code: 'PRC', manualPrice: 300 });
    const acc = summarize([isa, other], [sp1, bond, sp2, gold, pricey], records, null).accounts;
    const t = { ...target, targets: { 'KR:360750': 40, 'KR:AAA': 20, 'KR:PRC': 20, [CASH_KEY]: 20 } };
    const prc = buyOnly(allocationView(t, acc, null), 400).trades.find((s) => s.row.key === 'KR:PRC');
    expect(prc?.amountKrw ?? 0).toBeCloseTo(300, 9);
  });

  it('예수금 목표 0% 면 남는 돈을 최대한 줄인다', () => {
    const t = { ...target, includeCash: false, targets: { 'KR:360750': 60, 'KR:AAA': 40 }, units: { 'KR:360750': 7, 'KR:AAA': 7 } };
    const plan = buyOnly(allocationView(t, accounts, null), 1000);
    expect(plan.leftoverKrw).toBeLessThan(7);
    expect(plan.trades.reduce((s, x) => s + x.amountKrw, 0) + plan.leftoverKrw).toBeCloseTo(1000, 6);
  });

  it('현재 예수금도 함께 쓰기', () => {
    const t = { ...target, includeCash: false, targets: { 'KR:360750': 60, 'KR:AAA': 40 }, units: { 'KR:360750': 1, 'KR:AAA': 1 } };
    const view = allocationView(t, accounts, null);
    expect(view.cashKrw).toBe(500);
    const plan = buyOnly(view, 0, true);
    expect(plan.poolKrw).toBe(500);
    expect(plan.cashUsedKrw).toBe(500);
    expect(plan.leftoverKrw).toBeLessThan(1);
    expect(plan.trades.reduce((s, x) => s + x.amountKrw, 0) + plan.leftoverKrw).toBeCloseTo(500, 6);
    expect(buyOnly(view, 0, false).trades.length).toBe(0);

    const withCash = allocationView({ ...target, units: { 'KR:AAA': 0.01 } }, accounts, null);
    const p2 = byKey(buyOnly(withCash, 0, true).trades.map((s) => ({ ...s, key: s.row.key })));
    expect(Math.abs(p2.get('KR:AAA')!.amountKrw - (500 * 250) / 750)).toBeLessThan(0.02);
    expect(p2.get('CASH')!.amountKrw).toBeCloseTo(500 - p2.get('KR:AAA')!.amountKrw, 6);
  });
});
