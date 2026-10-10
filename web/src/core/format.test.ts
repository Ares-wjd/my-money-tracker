// core MoneyFormatTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { decimal, parseDecimal, percent, signedAmount, signedWon, usd, won } from './format';

describe('금액 표시 (MoneyFormatTest)', () => {
  it('천 단위 쉼표', () => {
    expect(won(1_234_567)).toBe('1,234,567원');
    expect(signedWon(1000)).toBe('+1,000원');
    expect(signedWon(-1000)).toBe('-1,000원');
    expect(won(1234.6)).toBe('1,235원');
    expect(won(-0.4)).toBe('0원');
  });

  it('달러와 수익률', () => {
    expect(usd(1234.5)).toBe('$1,234.50');
    expect(usd(-3.0)).toBe('-$3.00');
    expect(signedAmount('USD', 1.25)).toBe('+$1.25');
    expect(percent(0.12345)).toBe('+12.35%');
    expect(percent(-0.05)).toBe('-5.00%');
    expect(percent(null)).toBe('-');
  });

  it('Java DecimalFormat 과 같은 반올림 (실제 값 기준, 정확히 가운데면 짝수)', () => {
    // Java 로 확인한 값: 0.125→0.12, 0.135→0.14, 1.005→1.00, 2.675→2.67, 1234.565→1,234.57
    expect(usd(0.125)).toBe('$0.12');
    expect(usd(0.135)).toBe('$0.14');
    expect(usd(1.005)).toBe('$1.00');
    expect(usd(2.675)).toBe('$2.67');
    expect(usd(1234.565)).toBe('$1,234.57');
  });

  it('소수', () => {
    expect(decimal(12.5)).toBe('12.5');
    expect(decimal(1000.0)).toBe('1,000');
    expect(decimal(0.123456)).toBe('0.1235');
  });

  it('입력 읽기', () => {
    expect(parseDecimal('1,234.56')).toBeCloseTo(1234.56, 9);
    expect(parseDecimal('-3')).toBe(-3);
    expect(parseDecimal('abc')).toBeNull();
    expect(parseDecimal('.')).toBeNull();
    expect(parseDecimal('1.2.3')).toBeNull();
  });
});
