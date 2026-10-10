// 날짜는 "YYYY-MM-DD" 문자열로 다룬다. java.time.LocalDate 와 같은 규칙으로 계산한다.

const pad = (n: number, width = 2) => String(n).padStart(width, '0');

function parts(date: string): [number, number, number] {
  return [Number(date.slice(0, 4)), Number(date.slice(5, 7)), Number(date.slice(8, 10))];
}

const format = (y: number, m: number, d: number) => `${pad(y, 4)}-${pad(m)}-${pad(d)}`;

function lengthOfMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

/** 이 PC(브라우저)의 오늘 날짜 (폰처럼 현지 시간 기준). */
export function today(now: Date = new Date()): string {
  return format(now.getFullYear(), now.getMonth() + 1, now.getDate());
}

export function plusDays(date: string, days: number): string {
  const [y, m, d] = parts(date);
  const t = new Date(Date.UTC(y, m - 1, d + days));
  return format(t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate());
}

/** LocalDate.plusMonths: 그 달에 없는 날이면 말일로 맞춘다 (1/31 + 1달 = 2/28). */
export function plusMonths(date: string, months: number): string {
  const [y, m, d] = parts(date);
  const total = y * 12 + (m - 1) + months;
  const year = Math.floor(total / 12);
  const month = total - year * 12 + 1;
  return format(year, month, Math.min(d, lengthOfMonth(year, month)));
}

/** ChronoUnit.MONTHS.between: 꽉 찬 개월 수. */
export function monthsBetween(start: string, end: string): number {
  const [y1, m1, d1] = parts(start);
  const [y2, m2, d2] = parts(end);
  const packed1 = (y1 * 12 + m1 - 1) * 32 + d1;
  const packed2 = (y2 * 12 + m2 - 1) * 32 + d2;
  return Math.trunc((packed2 - packed1) / 32);
}

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

/** 폰과 같은 표시: "2026.10.9 (금)" */
export function formatDate(date: string): string {
  const [y, m, d] = parts(date);
  return `${y}.${m}.${d} (${WEEKDAYS[new Date(Date.UTC(y, m - 1, d)).getUTCDay()]})`;
}

/** 짧은 표시: "10.9" */
export function formatShortDate(date: string): string {
  const [, m, d] = parts(date);
  return `${m}.${d}`;
}
