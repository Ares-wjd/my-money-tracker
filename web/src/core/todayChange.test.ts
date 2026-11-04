// core TodayChangeTest 와 같은 케이스. 시장 상태·합계 숫자는 공통 테스트 케이스(testdata/clock, testdata/portfolio)에서도 검사한다.
import { describe, expect, it } from 'vitest';
import type { MarketSnapshot } from '../model';
import { currentPrice, dailyChangeRate, isLiveQuote, keyOf, latestUsdKrw } from './market';
import { summarize } from './portfolio';
import { account, holding, record } from './testData';
import { isLiveChange, isStale, marketOf, presentKinds, todayChange, todayLabel, toggleAllChips, toggleChip, type TodayKind } from './todayChange';

const today = '2026-10-14';
const day = '2026-10-01';
/** 수요일 밤: 국내 장 마감, 미국 정규장 (서머타임), 외환시장 열림. */
const night = Date.parse('2026-10-14T23:50:00+09:00');
const a = account({ id: 'a', name: '국내' });
const b = account({ id: 'b', name: '해외' });
const c = account({ id: 'c', name: '연금' });
const sam = holding({ id: 's', accountId: 'a', name: '삼성전자', code: '005930', market: 'KR' });
const sam2 = holding({ id: 's2', accountId: 'c', name: '삼성전자', code: '005930', market: 'KR' });
const fund = holding({ id: 'f', accountId: 'a', name: 'TDF', market: 'KR', assetType: 'FUND', manualPrice: 1_000, manualPriceDate: day });
const voo = holding({ id: 'v', accountId: 'b', name: 'VOO', code: 'VOO', market: 'NYSE', assetType: 'ETF' });
const holdings = [sam, sam2, fund, voo];
const records = [
  record({ id: '1', accountId: 'a', type: 'BUY', date: day, holdingId: 's', quantity: 10, price: 60_000, createdAt: 1 }),
  record({ id: '2', accountId: 'a', type: 'BUY', date: day, holdingId: 'f', quantity: 5, price: 1_000, createdAt: 2 }),
  record({ id: '3', accountId: 'b', type: 'BUY', date: day, holdingId: 'v', quantity: 2, price: 450, currency: 'USD', createdAt: 3 }),
  record({ id: '4', accountId: 'b', type: 'CASH_BALANCE', date: '2026-10-02', currency: 'USD', amount: 100, createdAt: 4 }),
  record({ id: '5', accountId: 'c', type: 'BUY', date: day, holdingId: 's2', quantity: 2, price: 60_000, createdAt: 5 }),
];
const snapshotOf = (usCandleDate = today): MarketSnapshot => ({
  prices: new Map([
    [keyOf(sam)!, { closes: new Map(), latest: { price: 75_000, date: today }, prevClose: 70_000, candle: { date: today, base: 70_000, last: 76_000, regular: 75_000, at: 0 } }],
    [keyOf(voo)!, { closes: new Map(), latest: { price: 520, date: today }, prevClose: 500, candle: { date: usCandleDate, base: 500, last: 520, regular: null, at: 0 } }],
  ]),
  usdKrw: new Map([['2026-10-13', 1_390]]),
  usdKrwLatest: [today, 1_400],
});
const summaryOf = (snap: MarketSnapshot) =>
  summarize([a, b, c], holdings, records, latestUsdKrw(snap)?.[1] ?? null, (h) => currentPrice(h, snap));

describe('시장별 변동 (TodayChangeTest)', () => {
  it('시장별 일봉, 국내 정규·장외 (marketsWithRegularAndExtendedSplit)', () => {
    const snap = snapshotOf();
    const t = todayChange(summaryOf(snap), snap, night)!;
    expect(t.markets.map((m) => m.state.group)).toEqual(['KR', 'US', 'FX']);
    const kr = marketOf(t, 'KR')!;
    expect(kr.changeKrw!).toBeCloseTo(72_000, 6);
    expect(kr.regularKrw!).toBeCloseTo(60_000, 6);
    expect(kr.extendedKrw!).toBeCloseTo(12_000, 6);
    expect(kr.state.phase).toBe('CLOSED');
    expect(kr.live).toBe(false);
    expect(kr.items.map(todayLabel)).toEqual(['삼성전자', 'TDF']);
    expect(kr.items.find((i) => i.holding.id === 'f')!.changeKrw).toBeNull();
    const us = marketOf(t, 'US')!;
    expect(us.changeKrw!).toBeCloseTo(2 * 20 * 1_400, 6);
    expect(us.live).toBe(true);
    expect(marketOf(t, 'FX')!.changeKrw!).toBeCloseTo(11_000, 6);
    expect(t.totalKrw).toBeCloseTo(72_000 + 56_000 + 11_000, 6);
    expect(isLiveChange(t)).toBe(true);
  });

  it('계좌·종류 필터 (filtersAccountsAndKinds)', () => {
    const snap = snapshotOf();
    const s = summaryOf(snap);
    expect(marketOf(todayChange(s, snap, night, { hiddenAccounts: new Set(['c']), hiddenKinds: new Set() })!, 'KR')!.changeKrw!).toBeCloseTo(60_000, 6);
    expect(marketOf(todayChange(s, snap, night, { hiddenAccounts: new Set(), hiddenKinds: new Set<TodayKind>(['USD_CASH']) })!, 'FX')!.changeKrw!).toBeCloseTo(10_000, 6);
    expect(todayChange(s, snap, night, { hiddenAccounts: new Set(['b']), hiddenKinds: new Set() })!.markets.map((m) => m.state.group)).toEqual(['KR']);
    expect(presentKinds(s)).toEqual(['KR_STOCK', 'ETF', 'OTHER', 'USD_CASH']);
  });

  it('받은 일봉이 지금 일봉이 아니면 회색 (staleCandleIsNotLive)', () => {
    const snap = snapshotOf('2026-10-13');
    const us = marketOf(todayChange(summaryOf(snap), snap, night)!, 'US')!;
    expect(us.state.phase).toBe('REGULAR');
    expect(us.live).toBe(false);
    expect(isStale(us)).toBe(true);
    expect(us.candleDate).toBe('2026-10-13');
  });

  it('오늘 수익률은 일봉 (dailyRateUsesCandle)', () => {
    const snap = snapshotOf();
    expect(dailyChangeRate(sam, snap)!).toBeCloseTo(76_000 / 70_000 - 1, 12);
    expect(dailyChangeRate(fund, snap)).toBeNull();
    expect(isLiveQuote(sam, snap, night)).toBe(false);
    expect(isLiveQuote(voo, snap, night)).toBe(true);
  });

  it('칩 전체·하나', () => {
    const present: TodayKind[] = ['KR_STOCK', 'ETF'];
    expect([...toggleAllChips(present, new Set())]).toEqual(['KR_STOCK', 'ETF']);
    expect([...toggleAllChips(present, new Set<TodayKind>(['ETF']))]).toEqual([]);
    expect([...toggleChip<TodayKind>('ETF', new Set())]).toEqual(['ETF']);
  });
});
