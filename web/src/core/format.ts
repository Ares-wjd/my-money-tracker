// core 의 MoneyFormat 과 같은 표시 형식.
import type { Currency } from '../model';

function group(integer: string): string {
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** Kotlin roundToLong 과 같음 (0.5 는 올림). -0 은 0 으로. */
const roundToLong = (value: number): number => Math.round(value) || 0;

/**
 * 양수를 소수 [digits] 자리로 반올림한 문자열. Java DecimalFormat 기본값과 같게, 2진수 실제 값을 기준으로 반올림하고
 * 정확히 가운데일 때만 짝수 쪽으로 보낸다 (HALF_EVEN). 예: 12.345(실제 12.34500000000000063…) → 12.35, 0.125 → 0.12
 */
function fixedHalfEven(value: number, digits: number): string {
  if (value >= 1e21) return value.toFixed(digits);
  // toFixed(100) 은 보통 크기의 double 을 정확한 십진수로 펼친다.
  const [intPart, fracPart] = value.toFixed(100).split('.');
  const kept = intPart + fracPart.slice(0, digits);
  const rest = fracPart.slice(digits);
  const lastDigit = Number(kept[kept.length - 1]);
  const roundUp = rest[0] > '5' || (rest[0] === '5' && (/[1-9]/.test(rest.slice(1)) || lastDigit % 2 === 1));
  let n = BigInt(kept);
  if (roundUp) n += 1n;
  const s = n.toString().padStart(digits + 1, '0');
  return `${s.slice(0, s.length - digits)}.${s.slice(s.length - digits)}`;
}

function grouped2(value: number): string {
  const [i, f] = fixedHalfEven(Math.abs(value), 2).split('.');
  return `${group(i)}.${f}`;
}

/** 1234567 -> "1,234,567원" (소수는 반올림) */
export function won(amount: number): string {
  const n = roundToLong(amount);
  return (n < 0 ? '-' : '') + group(String(Math.abs(n))) + '원';
}

/** +1,000원 / -1,000원 */
export function signedWon(amount: number): string {
  const n = roundToLong(amount);
  if (n > 0) return '+' + won(n);
  if (n < 0) return '-' + won(-n);
  return won(0);
}

/** 1234.5 -> "$1,234.50" */
export function usd(amount: number): string {
  return (amount < 0 ? '-$' : '$') + grouped2(amount);
}

export function signedUsd(amount: number): string {
  return amount > 0 ? '+' + usd(amount) : usd(amount);
}

export function amount(currency: Currency, value: number): string {
  return currency === 'KRW' ? won(value) : usd(value);
}

export function signedAmount(currency: Currency, value: number): string {
  return currency === 'KRW' ? signedWon(value) : signedUsd(value);
}

/** 0.1234 -> "+12.34%" (null 이면 "-") */
export function percent(rate: number | null | undefined): string {
  if (rate === null || rate === undefined || !Number.isFinite(rate)) return '-';
  const text = grouped2(rate * 100);
  if (rate > 0) return `+${text}%`;
  if (rate < 0) return `-${text}%`;
  return `${text}%`;
}

/** 수량·단가처럼 소수가 있을 수 있는 숫자. 불필요한 0 은 지운다. 12.5000 -> "12.5" */
export function decimal(value: number, maxFractionDigits = 4): string {
  // toFixed 는 실제 2진수 값을 기준으로 가운데면 큰 쪽으로 반올림한다 (BigDecimal HALF_UP 과 같은 결과, 양수 기준).
  const fixed = Math.abs(value).toFixed(maxFractionDigits);
  const [i, f = ''] = fixed.split('.');
  const frac = f.replace(/0+$/, '');
  const text = group(i) + (frac ? '.' + frac : '');
  return value < 0 && /[1-9]/.test(fixed) ? '-' + text : text;
}

/** "1,234.56" -> 1234.56. 숫자가 아니면 null. 쉼표는 무시한다. */
export function parseDecimal(input: string): number | null {
  const cleaned = input.replace(/,/g, '').trim();
  if (cleaned === '' || cleaned.length > 20) return null;
  if (!/^-?\d*\.?\d*$/.test(cleaned) || cleaned === '.' || cleaned === '-' || cleaned === '-.') return null;
  const n = Number(cleaned);
  return Number.isFinite(n) ? n : null;
}
