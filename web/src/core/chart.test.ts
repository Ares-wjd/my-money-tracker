// core ChartCalculatorTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { hasEarlier, pointDates, series } from './chart';
import { plusDays, plusMonths } from './dates';
import { account, holding, record } from './testData';

const today = '2026-10-09';

describe('자산 추이 (ChartCalculatorTest)', () => {
  it('1달·1년 보기는 말일마다, 마지막은 오늘', () => {
    expect(pointDates('MONTH', today, '2026-07-15')).toEqual(['2026-07-31', '2026-08-31', '2026-09-30', today]);
    expect(pointDates('YEAR', today, '2024-03-01')).toEqual(['2024-12-31', '2025-12-31', today]);
  });

  it('일 단위 보기는 오늘부터 거꾸로', () => {
    expect(pointDates('DAY_5', today, plusDays(today, -12))).toEqual([plusDays(today, -10), plusDays(today, -5), today]);
    const limited = pointDates('DAY_1', today, '2020-01-01');
    expect(limited[0]).toBe(plusMonths(today, -3));
    expect(hasEarlier('DAY_1', today, '2020-01-01', 1)).toBe(true);
    expect(hasEarlier('MONTH', today, '2020-01-01', 1)).toBe(false);
  });

  it('첫 기록이 미래면 오늘 하나', () => {
    expect(pointDates('MONTH', today, '2026-12-01')).toEqual([today]);
  });

  it('날짜마다 그날 종가로 계산', () => {
    const a = account({ id: 'a', name: '국내' });
    const h = holding({ id: 'h', accountId: 'a', name: '삼성전자', code: '005930', market: 'KR' });
    const records = [
      record({ id: '1', accountId: 'a', type: 'DEPOSIT', date: '2026-09-01', amount: 1000, krwAmount: 1000 }),
      record({ id: '2', accountId: 'a', type: 'BUY', date: '2026-09-02', holdingId: 'h', quantity: 10, price: 100, createdAt: 1 }),
    ];
    const snapshot = {
      prices: new Map([['KR_005930', { closes: new Map([['2026-09-29', 120], ['2026-10-08', 150]]), latest: null }]]),
      usdKrw: new Map<string, number>(),
    };
    const points = series(['2026-09-30', today], today, [a], [h], records, snapshot, null);
    expect(points[0].valueKrw).toBeCloseTo(1200, 9);
    expect(points[1].valueKrw).toBeCloseTo(1500, 9);
    expect(points[1].investedKrw).toBeCloseTo(1000, 9);
  });
});
