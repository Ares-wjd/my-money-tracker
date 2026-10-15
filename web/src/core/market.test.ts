// core MarketDataTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { GOLD_CODE, quantityUnit, type MarketSnapshot, type PricePoint } from '../model';
import { agreedFx, currentPrice, dailyChangeRate, fxSourceLabel, isRecentFxDate, keyOf, latestUsdKrw, latestUsdKrwSourceLabel, marketKey, priceAt, tradeUsdKrw, usdKrwAt } from './market';
import { toMarketSnapshot } from '../data/mappers';
import { holding } from './testData';

const d1 = '2026-10-01';
const d2 = '2026-10-02';
const d5 = '2026-10-05';
const stock = holding({ id: 's', accountId: 'a', name: '삼성전자', code: '005930', market: 'KR' });

const snapshot = (latest: PricePoint | null = null): MarketSnapshot => ({
  prices: new Map([[marketKey('KR', '005930'), { closes: new Map([[d1, 100], [d2, 110]]), latest, prevClose: null }]]),
  usdKrw: new Map([[d1, 1400], [d2, -1]]),
});

describe('시세 (MarketDataTest)', () => {
  it('펀드는 자동 시세 대상이 아니다', () => {
    expect(keyOf({ ...stock, assetType: 'FUND' })).toBeNull();
    expect(keyOf(stock)).toBe('KR_005930');
    expect(marketKey('NASDAQ', 'aapl')).toBe('US_AAPL');
  });

  it('금현물은 KRX 금 코드로 시세를 받는다', () => {
    const gold = holding({ id: 'g', accountId: 'a', name: '금', code: GOLD_CODE, market: 'KR', assetType: 'GOLD' });
    expect(keyOf(gold)).toBe('KR_M04020000');
    expect(keyOf({ ...gold, code: '' })).toBeNull();
    expect(quantityUnit('GOLD')).toBe('g');
    expect(quantityUnit('ETF')).toBe('주');
  });

  it('휴장일은 직전 종가', () => {
    const s = snapshot();
    expect(priceAt(stock, s, d5)?.price).toBe(110);
    expect(priceAt(stock, s, d1)?.price).toBe(100);
    expect(priceAt(stock, s, '2026-09-30')).toBeNull();
  });

  it('환율은 "데이터 없음" 표시를 건너뛴다', () => {
    const s = snapshot();
    expect(usdKrwAt(s, d5)).toBe(1400);
    expect(latestUsdKrw(s)).toEqual([d1, 1400]);
  });

  it('현재가는 가장 최근 값', () => {
    const s = snapshot({ price: 120, date: d5 });
    expect(currentPrice(stock, s)?.price).toBe(120);
    expect(currentPrice({ ...stock, manualPrice: 130, manualPriceDate: '2026-10-06' }, s)?.price).toBe(130);
    expect(currentPrice({ ...stock, manualPrice: 90, manualPriceDate: d1 }, s)?.price).toBe(120);
  });

  it('과거 가격이 없으면 직접 입력 가격', () => {
    const fund = { ...stock, code: '', assetType: 'FUND' as const, manualPrice: 1000, manualPriceDate: d5 };
    expect(priceAt(fund, { prices: new Map(), usdKrw: new Map() }, d1)?.price).toBe(1000);
  });

  it('매매일 환율은 받아 둔 날만 (옛 환율을 쓰지 않는다)', () => {
    const today = '2026-10-09';
    const s = { prices: new Map(), usdKrw: new Map([[d2, 1400], ['2026-10-03', -1], ['2026-10-04', -1], ['2026-10-08', 1410]]) };
    expect(tradeUsdKrw(s, '2026-10-04', today)).toBe(1400);
    expect(tradeUsdKrw(s, d2, today)).toBe(1400);
    expect(tradeUsdKrw(s, '2026-10-05', today)).toBeNull();
    expect(tradeUsdKrw(s, today, today)).toBe(1410);
  });

  it('오늘 수익률은 자동 시세일 때만 (MarketDataTest)', () => {
    const base = snapshot({ price: 110, date: d5 });
    const s = { ...base, prices: new Map([...base.prices].map(([k, h]) => [k, { ...h, prevClose: 100 }])) };
    expect(dailyChangeRate(stock, s)).toBeCloseTo(0.1, 9);
    expect(dailyChangeRate({ ...stock, manualPrice: 130, manualPriceDate: '2026-10-06' }, s)).toBeNull();
    expect(dailyChangeRate(stock, snapshot({ price: 110, date: d5 }))).toBeNull();
  });

  it('환율: 최근 1주일, 두 번 같은 값, 오늘 환율 (fxRulesRecentWeekAndTwoAgreeingFetches)', () => {
    const today = '2026-10-14';
    expect(isRecentFxDate('2026-10-13', today)).toBe(true);
    expect(isRecentFxDate('2026-10-07', today)).toBe(true);
    expect(isRecentFxDate('2026-10-06', today)).toBe(false);
    expect(isRecentFxDate(today, today)).toBe(false);
    expect(agreedFx([1394.5, 1394.5])).toBe(1394.5);
    expect(agreedFx([1394.5, 1390, 1394.5])).toBe(1394.5);
    expect(agreedFx([1394.5, 1390, 1391])).toBeNull();
    expect(agreedFx([-1, -1])).toBe(-1);
    // fx_USD 위에 고정된 값을 덮고, 오늘 환율은 따로
    const s = toMarketSnapshot([
      { id: 'fx_USD', data: { rates: { '2026-10-12': 1388, '2026-10-13': 1390 } } },
      { id: 'fx_USD_checked', data: { rates: { '2026-10-12': 1389 }, latest: 1400, latestDate: today } },
    ]);
    expect(s.usdKrw.get('2026-10-12')).toBe(1389);
    expect(s.usdKrw.get('2026-10-13')).toBe(1390);
    expect(s.usdKrw.has(today)).toBe(false);
    expect(latestUsdKrw(s)).toEqual([today, 1400]);
    expect(usdKrwAt(s, '2026-10-13')).toBe(1390);
  });

  it('환율 출처 표시 (v2.8): 오늘 받은 환율일 때만', () => {
    expect(fxSourceLabel('KIS')).toBe('한투');
    expect(fxSourceLabel('EXIM')).toBe('수출입은행');
    expect(fxSourceLabel(null)).toBeNull();
    const s = toMarketSnapshot([
      { id: 'fx_USD', data: { rates: { '2026-10-14': 1342.3 } } },
      { id: 'fx_USD_checked', data: { rates: {}, latest: 1347.7, latestDate: '2026-10-15', latestSource: 'KIS' } },
    ]);
    expect(s.usdKrwLatestSource).toBe('KIS');
    expect(latestUsdKrw(s)).toEqual(['2026-10-15', 1347.7]);
    expect(latestUsdKrwSourceLabel(s)).toBe('한투');
    // 오늘 환율이 과거 목록보다 오래됐으면 (과거 목록 값을 쓰므로) 출처 표시 없음
    const old = toMarketSnapshot([
      { id: 'fx_USD', data: { rates: { '2026-10-14': 1342.3 } } },
      { id: 'fx_USD_checked', data: { latest: 1338.6, latestDate: '2026-10-13', latestSource: 'EXIM' } },
    ]);
    expect(latestUsdKrwSourceLabel(old)).toBeNull();
  });
});
