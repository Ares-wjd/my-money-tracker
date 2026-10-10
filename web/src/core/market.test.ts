// core MarketDataTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import type { MarketSnapshot, PricePoint } from '../model';
import { currentPrice, keyOf, latestUsdKrw, marketKey, priceAt, usdKrwAt } from './market';
import { holding } from './testData';

const d1 = '2026-10-01';
const d2 = '2026-10-02';
const d5 = '2026-10-05';
const stock = holding({ id: 's', accountId: 'a', name: '삼성전자', code: '005930', market: 'KR' });

const snapshot = (latest: PricePoint | null = null): MarketSnapshot => ({
  prices: new Map([[marketKey('KR', '005930'), { closes: new Map([[d1, 100], [d2, 110]]), latest }]]),
  usdKrw: new Map([[d1, 1400], [d2, -1]]),
});

describe('시세 (MarketDataTest)', () => {
  it('펀드는 자동 시세 대상이 아니다', () => {
    expect(keyOf({ ...stock, assetType: 'FUND' })).toBeNull();
    expect(keyOf(stock)).toBe('KR_005930');
    expect(marketKey('NASDAQ', 'aapl')).toBe('US_AAPL');
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
});
