import { describe, expect, it } from 'vitest';
import { formatDate, monthsBetween, plusDays, plusMonths, today } from './dates';

describe('날짜 (java.time 과 같은 규칙)', () => {
  it('plusMonths 는 말일로 맞춘다', () => {
    expect(plusMonths('2026-01-31', 1)).toBe('2026-02-28');
    expect(plusMonths('2023-04-08', 48)).toBe('2027-04-08');
    expect(plusMonths('2026-10-08', -10)).toBe('2025-12-08');
  });

  it('monthsBetween 은 꽉 찬 개월 수', () => {
    expect(monthsBetween('2026-10-08', '2027-04-08')).toBe(6);
    expect(monthsBetween('2026-10-08', '2027-04-01')).toBe(5);
    expect(monthsBetween('2026-01-31', '2026-02-28')).toBe(0);
  });

  it('plusDays, 오늘, 표시', () => {
    expect(plusDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(plusDays('2026-03-01', -1)).toBe('2026-02-28');
    expect(today(new Date(2026, 9, 9, 23, 59))).toBe('2026-10-09');
    expect(formatDate('2026-10-09')).toBe('2026.10.9 (금)');
  });
});
