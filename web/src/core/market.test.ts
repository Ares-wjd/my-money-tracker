// core MarketDataTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { GOLD_CODE, quantityUnit, type MarketSnapshot, type PricePoint } from '../model';
import { currentPrice, dailyChangeRate, keyOf, latestUsdKrw, marketKey, priceAt, tradeUsdKrw, usdKrwAt } from './market';
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
});
