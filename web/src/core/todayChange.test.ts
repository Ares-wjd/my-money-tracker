// core TodayChangeTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import type { MarketSnapshot } from '../model';
import { currentPrice, keyOf, latestUsdKrw } from './market';
import { summarize } from './portfolio';
import { account, holding, record } from './testData';
import { changeLevel, excludedItems, presentKinds, todayChange, todayLabel, todayTiles, toggleAllChips, toggleChip, topGainers, topLosers, treemap, type TodayFilter, type TodayKind } from './todayChange';

const today = '2026-10-14';
const day = '2026-10-01';
const a = account({ id: 'a', name: '국내' });
const b = account({ id: 'b', name: '해외' });
const sam = holding({ id: 's', accountId: 'a', name: '삼성전자', code: '005930', market: 'KR' });
const fund = holding({ id: 'f', accountId: 'a', name: 'TDF', market: 'KR', assetType: 'FUND', manualPrice: 1_000, manualPriceDate: day });
const voo = holding({ id: 'v', accountId: 'b', name: 'VOO', code: 'VOO', market: 'NYSE', assetType: 'ETF' });
const holdings = [sam, fund, voo];
const records = [
  record({ id: '1', accountId: 'a', type: 'BUY', date: day, holdingId: 's', quantity: 10, price: 60_000, createdAt: 1 }),
  record({ id: '2', accountId: 'a', type: 'BUY', date: day, holdingId: 'f', quantity: 5, price: 1_000, createdAt: 2 }),
  record({ id: '3', accountId: 'b', type: 'BUY', date: day, holdingId: 'v', quantity: 2, price: 450, currency: 'USD', createdAt: 3 }),
  record({ id: '4', accountId: 'b', type: 'CASH_BALANCE', date: '2026-10-02', currency: 'USD', amount: 100, createdAt: 4 }),
];
const snapshot: MarketSnapshot = {
  prices: new Map([
    [keyOf(sam)!, { closes: new Map(), latest: { price: 75_000, date: today }, prevClose: 70_000 }],
    [keyOf(voo)!, { closes: new Map(), latest: { price: 520, date: today }, prevClose: 500 }],
  ]),
  usdKrw: new Map([['2026-10-13', 1_390]]),
  usdKrwLatest: [today, 1_400],
};
const summaryOf = (snap: MarketSnapshot) =>
  summarize([a, b], holdings, records, latestUsdKrw(snap)?.[1] ?? null, (h) => currentPrice(h, snap));

describe('오늘 변동 (TodayChangeTest)', () => {
  it('국내·해외·환율 효과', () => {
    const t = todayChange(summaryOf(snapshot), snapshot, today)!;
    expect(t.domesticKrw).toBeCloseTo(50_000, 6);
    expect(t.foreignKrw).toBeCloseTo(56_000, 6);
    expect(t.fxKrw).toBeCloseTo(11_000, 6);
    expect(t.totalKrw).toBeCloseTo(117_000, 6);
    expect(t.rate!).toBeCloseTo(117_000 / 2_229_000, 12);
    expect(t.fxFrom).toBe(1_390);
    expect(excludedItems(t).map((i) => i.holding.id)).toEqual(['f']);
    expect(topGainers(t).map((i) => i.holding.id)).toEqual(['s', 'v']);
    expect(topLosers(t)).toEqual([]);
    expect(todayLabel(t.items.find((i) => i.holding.id === 's')!)).toBe('삼성전자');
  });

  it('계좌별 줄과 필터', () => {
    const filter = (accounts: string[], kinds: TodayKind[]): TodayFilter => ({ hiddenAccounts: new Set(accounts), hiddenKinds: new Set(kinds) });
    const all = todayChange(summaryOf(snapshot), snapshot, today)!;
    // 계좌별 가격 변동 + 환율 = 합계
    expect(all.accounts.map((x) => [x.account.id, x.changeKrw])).toEqual([['a', 50_000], ['b', 56_000]]);
    expect(all.accounts.reduce((s, x) => s + (x.changeKrw ?? 0), 0) + all.fxKrw).toBeCloseTo(all.totalKrw, 6);
    expect(presentKinds(summaryOf(snapshot))).toEqual(['KR_STOCK', 'ETF', 'OTHER', 'USD_CASH']);

    // 계좌 b 제외: 국내만, 환율 효과 없음
    const noB = todayChange(summaryOf(snapshot), snapshot, today, filter(['b'], []))!;
    expect(noB.totalKrw).toBeCloseTo(50_000, 6);
    expect(noB.fxKrw).toBe(0);
    expect(noB.rate!).toBeCloseTo(50_000 / 700_000, 12);
    expect(noB.accounts.map((x) => x.account.id)).toEqual(['a']);

    // 달러 예수금 제외: 환율 효과는 VOO 몫만 (2 × $500 × 10 = 10,000)
    const noCash = todayChange(summaryOf(snapshot), snapshot, today, filter([], ['USD_CASH']))!;
    expect(noCash.fxKrw).toBeCloseTo(10_000, 6);
    expect(noCash.totalKrw).toBeCloseTo(116_000, 6);
    expect(noCash.rate!).toBeCloseTo(116_000 / 2_090_000, 12);

    // 개별주만: 삼성전자만, 종목이 남지 않은 계좌 b 는 줄이 없다
    const onlyStocks = todayChange(summaryOf(snapshot), snapshot, today, filter([], ['ETF', 'GOLD', 'OTHER', 'USD_CASH']))!;
    expect(onlyStocks.totalKrw).toBeCloseTo(50_000, 6);
    expect(onlyStocks.accounts.map((x) => [x.account.id, x.changeKrw])).toEqual([['a', 50_000]]);

    // 펀드만 남은 계좌는 시세 없음 (null)
    const fundOnly = todayChange(summaryOf(snapshot), snapshot, today, filter(['b'], ['KR_STOCK']))!;
    expect(fundOnly.accounts.map((x) => [x.account.id, x.changeKrw])).toEqual([['a', null]]);
    expect(fundOnly.totalKrw).toBe(0);

    // 전부 빼면 null
    expect(todayChange(summaryOf(snapshot), snapshot, today, filter(['a', 'b'], []))).toBeNull();

    // [전체] 칩
    expect(toggleAllChips(['a', 'b'], new Set<string>())).toEqual(new Set(['a', 'b']));
    expect(toggleAllChips(['a', 'b'], new Set(['a']))).toEqual(new Set());
    expect(toggleChip('a', new Set(['a', 'b']))).toEqual(new Set(['b']));
  });

  it('오늘 환율 발표 전에는 환율 효과 0', () => {
    const early: MarketSnapshot = { ...snapshot, usdKrwLatest: null };
    const t = todayChange(summaryOf(early), early, today)!;
    expect(t.fxKrw).toBe(0);
    expect(t.fxFrom).toBeNull();
    expect(t.foreignKrw).toBeCloseTo(2 * 20 * 1_390, 6);
  });

  it('색 단계·타일·트리맵', () => {
    expect(changeLevel(0.0005)).toBe(0);
    expect(changeLevel(0.005)).toBe(1);
    expect(changeLevel(-0.015)).toBe(-2);
    expect(changeLevel(0.031)).toBe(3);
    expect(changeLevel(null)).toBeNull();
    const many = Array.from({ length: 20 }, (_, k) => ({ key: `k${k + 1}`, holding: { ...sam, id: `h${k + 1}` }, valueKrw: (k + 1) * 100, changeKrw: 0, rate: 0 }));
    const tiles = todayTiles(many);
    expect(tiles.length).toBe(16);
    expect(tiles[15].item).toBeNull();
    expect(tiles[15].weight).toBe(100 + 200 + 300 + 400 + 500);
    const weights = [6, 6, 4, 3, 2, 2, 1];
    const rects = treemap(weights, 6, 4);
    rects.forEach((r, k) => {
      expect(r.w * r.h).toBeCloseTo(weights[k], 9);
      expect(r.x >= -1e-9 && r.y >= -1e-9 && r.x + r.w <= 6 + 1e-9 && r.y + r.h <= 4 + 1e-9).toBe(true);
    });
  });
});
