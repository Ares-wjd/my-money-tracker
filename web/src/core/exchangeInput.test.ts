// core ExchangeInputTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { computeExchange, exchangeAutoField, touchExchange, type ExchangeField } from './exchangeInput';

describe('환전 입력 (ExchangeInputTest)', () => {
  it('최근 두 칸으로 자동 칸이 정해진다', () => {
    let edited: ExchangeField[] = [];
    expect(exchangeAutoField(edited)).toBeNull();
    edited = touchExchange(edited, 'KRW');
    expect(exchangeAutoField(edited)).toBeNull();
    edited = touchExchange(edited, 'RATE');
    expect(exchangeAutoField(edited)).toBe('USD');
    edited = touchExchange(edited, 'USD');
    expect(exchangeAutoField(edited)).toBe('KRW');
    edited = touchExchange(edited, 'KRW');
    expect(exchangeAutoField(edited)).toBe('RATE');
  });

  it('나머지 한 칸 계산', () => {
    expect(computeExchange('RATE', 1_394_500, 1_000, null)).toBeCloseTo(1_394.5, 9);
    expect(computeExchange('USD', 1_000_000, null, 1_394.5)).toBeCloseTo(717.1, 9);
    expect(computeExchange('KRW', null, 717.1, 1_394.5)).toBe(999_996);
    expect(computeExchange('KRW', null, 717.1, null)).toBeNull();
    expect(computeExchange('RATE', 1_000, 0, null)).toBeNull();
  });
});
