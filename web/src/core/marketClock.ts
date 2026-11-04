// 지금 시장 상태 (core MarketClock 과 같은 규칙, v2.10). 시각은 한국 시간으로 돌려주고, 미국은 뉴욕 시간대(Intl)로 서머타임을 맞춘다.
// - 국내: 개장일 08:00 프리마켓(NXT) → 09:00 정규장 → 15:30 애프터마켓(NXT·시간외 단일가) → 20:00 장 마감.
// - 미국: 현지 날짜 D 의 일봉 = 뉴욕 D−1 20:00 주간거래 → D 04:00 프리 → 09:30 정규장 → 16:00 애프터 → 20:00.
// - 환율(서울 외환시장): 개장일 09:00 ~ 다음 날 02:00.
import type { Holding, MarketCalendar } from '../model';
import { plusDays } from './dates';

export type MarketGroup = 'KR' | 'US' | 'FX';
export const MARKET_GROUPS: readonly MarketGroup[] = ['KR', 'US', 'FX'];
export const MARKET_GROUP_LABEL: { [K in MarketGroup]: string } = { KR: '국내', US: '미국', FX: '환율' };

/** 종목의 시장: 원화 종목(금현물 포함)은 국내, 달러 종목은 미국. */
export const marketGroupOf = (holding: Holding): MarketGroup => (holding.market === 'KR' ? 'KR' : 'US');

export type MarketPhase = 'PRE' | 'REGULAR' | 'AFTER' | 'DAY' | 'FX_OPEN' | 'BEFORE_OPEN' | 'CLOSED' | 'HOLIDAY';
export const PHASE_LABEL: { [K in MarketPhase]: string } = {
  PRE: '프리마켓', REGULAR: '정규장', AFTER: '애프터마켓', DAY: '주간거래', FX_OPEN: '외환시장 열림',
  BEFORE_OPEN: '개장 전', CLOSED: '장 마감', HOLIDAY: '휴장',
};
/** 가격이 움직이는 중인 상태. */
export const isActivePhase = (phase: MarketPhase): boolean => phase === 'PRE' || phase === 'REGULAR' || phase === 'AFTER' || phase === 'DAY' || phase === 'FX_OPEN';

/** 한국 시각 (날짜 + 그날 0시부터 분). */
export interface KstTime {
  date: string;
  minutes: number;
}

/** 한 시장의 지금 상태 (core MarketState). */
export interface MarketState {
  group: MarketGroup;
  phase: MarketPhase;
  /** 지금 진행 중이거나(진행 중) 마지막(쉬는 중) 일봉의 거래일 (미국은 현지 날짜). */
  candleDate: string;
  /** 휴장 사유 (모르면 null). */
  reason: string | null;
  nextAt: KstTime | null;
  nextPhase: MarketPhase | null;
}

/** 상태 표시 글: "정규장", "휴장 · 추수감사절", 사유를 모르면 "휴장". */
export const stateChip = (s: MarketState): string => (s.phase === 'HOLIDAY' && s.reason !== null ? `${PHASE_LABEL[s.phase]} · ${s.reason}` : PHASE_LABEL[s.phase]);

/** 시간표의 한 구간 (한국 시간 그날 0시부터 분, end 는 포함하지 않음). */
export interface SessionSegment {
  group: MarketGroup;
  phase: MarketPhase;
  start: number;
  end: number;
}

export const EMPTY_CALENDAR: MarketCalendar = { krClosed: new Set(), krFrom: null, krTo: null };

// ---------------------------------------------------------------- 휴일

const WEEKEND = '주말';
const dayOfWeek = (date: string): number => new Date(date + 'T00:00:00Z').getUTCDay(); // 0 = 일요일
export const isWeekend = (date: string): boolean => dayOfWeek(date) === 0 || dayOfWeek(date) === 6;

const KR_FIXED: { [mmdd: string]: string } = {
  '01-01': '신정', '03-01': '삼일절', '05-01': '근로자의 날', '05-05': '어린이날', '06-06': '현충일',
  '08-15': '광복절', '10-03': '개천절', '10-09': '한글날', '12-25': '성탄절', '12-31': '연말 휴장',
};

/** 국내 고정 휴일 이름 (주말이 아니어도 그날이면). */
export const krHoliday = (date: string): string | null => KR_FIXED[date.slice(5)] ?? null;

const ymd = (y: number, m: number, d: number) => `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;

/** month(1~12) 의 n 번째 weekday(0=일). */
function nth(year: number, month: number, weekday: number, n: number): string {
  const first = new Date(Date.UTC(year, month - 1, 1)).getUTCDay();
  return ymd(year, month, 1 + ((weekday - first + 7) % 7) + 7 * (n - 1));
}

function lastWeekday(year: number, month: number, weekday: number): string {
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const last = new Date(Date.UTC(year, month - 1, lastDay)).getUTCDay();
  return ymd(year, month, lastDay - ((last - weekday + 7) % 7));
}

/** 토요일이면 금요일, 일요일이면 월요일에 쉰다. */
function observed(date: string): string {
  const w = dayOfWeek(date);
  return w === 6 ? plusDays(date, -1) : w === 0 ? plusDays(date, 1) : date;
}

/** 부활절 (그레고리력). */
export function easter(year: number): string {
  const a = year % 19;
  const b = Math.floor(year / 100);
  const c = year % 100;
  const d = Math.floor(b / 4);
  const e = b % 4;
  const f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3);
  const h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4);
  const k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7;
  const m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31);
  const day = ((h + l - 7 * m + 114) % 31) + 1;
  return ymd(year, month, day);
}

function usHolidays(year: number): Map<string, string> {
  const out = new Map<string, string>();
  // 새해 첫날: 일요일이면 월요일, 토요일이면 옮기지 않는다 (전년 12/31 은 연다)
  const newYear = ymd(year, 1, 1);
  if (dayOfWeek(newYear) === 0) out.set(plusDays(newYear, 1), '새해 첫날');
  else if (dayOfWeek(newYear) !== 6) out.set(newYear, '새해 첫날');
  out.set(nth(year, 1, 1, 3), '마틴 루서 킹 데이');
  out.set(nth(year, 2, 1, 3), '대통령의 날');
  out.set(plusDays(easter(year), -2), '성금요일');
  out.set(lastWeekday(year, 5, 1), '메모리얼 데이');
  if (year >= 2022) out.set(observed(ymd(year, 6, 19)), '준틴스');
  out.set(observed(ymd(year, 7, 4)), '독립기념일');
  out.set(nth(year, 9, 1, 1), '노동절');
  out.set(nth(year, 11, 4, 4), '추수감사절');
  out.set(observed(ymd(year, 12, 25)), '크리스마스');
  return out;
}

/** 뉴욕증권거래소 휴장일 이름 (평일만, 아니면 null). */
export function usHoliday(date: string): string | null {
  if (isWeekend(date)) return null;
  const year = Number(date.slice(0, 4));
  return usHolidays(year).get(date) ?? usHolidays(year + 1).get(date) ?? null;
}

export function krOpen(cal: MarketCalendar, date: string): boolean {
  if (isWeekend(date) || cal.krClosed.has(date)) return false;
  const checked = cal.krFrom !== null && cal.krTo !== null && date >= cal.krFrom && date <= cal.krTo;
  return checked || krHoliday(date) === null;
}

export const krReason = (date: string): string | null => (isWeekend(date) ? WEEKEND : krHoliday(date));
export const usOpen = (date: string): boolean => !isWeekend(date) && usHoliday(date) === null;
export const usReason = (date: string): string | null => (isWeekend(date) ? WEEKEND : usHoliday(date));

function step(from: string, by: number, ok: (d: string) => boolean): string {
  let d = from;
  for (let i = 0; i < 40; i++) {
    if (ok(d)) return d;
    d = plusDays(d, by);
  }
  return d;
}

/** date 바로 전 국내 개장일. */
export const prevKrOpen = (date: string, cal: MarketCalendar): string => step(plusDays(date, -1), -1, (d) => krOpen(cal, d));
export const nextKrOpen = (date: string, cal: MarketCalendar): string => step(date, 1, (d) => krOpen(cal, d));
const prevUsOpen = (date: string): string => step(plusDays(date, -1), -1, usOpen);
const nextUsOpen = (date: string): string => step(date, 1, usOpen);

// ---------------------------------------------------------------- 시각 변환

const KST_OFFSET = 9 * 60 * 60 * 1000;
const NEW_YORK = 'America/New_York';

/** ms → 한국 시각 (한국은 서머타임이 없어 +9시간). */
export function kstOf(ms: number): KstTime {
  const t = new Date(ms + KST_OFFSET);
  return { date: ymd(t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate()), minutes: t.getUTCHours() * 60 + t.getUTCMinutes() };
}

const nyFormat = new Intl.DateTimeFormat('en-US', {
  timeZone: NEW_YORK, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
});

/** ms → 뉴욕 시각. */
function nyOf(ms: number): KstTime {
  const p: { [k: string]: string } = {};
  for (const part of nyFormat.formatToParts(new Date(ms))) p[part.type] = part.value;
  return { date: `${p.year}-${p.month}-${p.day}`, minutes: Number(p.hour) * 60 + Number(p.minute) };
}

const utcOf = (t: KstTime): number => Date.parse(t.date + 'T00:00:00Z') + t.minutes * 60000;

/** 뉴욕 시각 → ms (서머타임 경계는 두 번 맞춘다). */
function nyToMs(t: KstTime): number {
  const guess = utcOf(t);
  const offset = (ms: number) => utcOf(nyOf(ms)) - Math.floor(ms / 60000) * 60000;
  const first = guess - offset(guess);
  return guess - offset(first);
}

const nyToKst = (t: KstTime): KstTime => kstOf(nyToMs(t));
const at = (date: string, h: number, m = 0): KstTime => ({ date, minutes: h * 60 + m });
const before = (t: number, h: number, m = 0) => t < h * 60 + m;

// ---------------------------------------------------------------- 상태

export function krState(nowMs: number, cal: MarketCalendar): MarketState {
  const { date: d, minutes: t } = kstOf(nowMs);
  const s = (phase: MarketPhase, candleDate: string, nextAt: KstTime, nextPhase: MarketPhase, reason: string | null = null): MarketState =>
    ({ group: 'KR', phase, candleDate, reason, nextAt, nextPhase });
  if (!krOpen(cal, d)) return s('HOLIDAY', prevKrOpen(d, cal), at(nextKrOpen(plusDays(d, 1), cal), 8), 'PRE', krReason(d));
  if (before(t, 8)) return s('BEFORE_OPEN', prevKrOpen(d, cal), at(d, 8), 'PRE');
  if (before(t, 9)) return s('PRE', d, at(d, 9), 'REGULAR');
  if (before(t, 15, 30)) return s('REGULAR', d, at(d, 15, 30), 'AFTER');
  if (before(t, 20)) return s('AFTER', d, at(d, 20), 'CLOSED');
  return s('CLOSED', d, at(nextKrOpen(plusDays(d, 1), cal), 8), 'PRE');
}

export function usState(nowMs: number): MarketState {
  const et = nyOf(nowMs);
  const t = et.minutes;
  const d = t >= 20 * 60 ? plusDays(et.date, 1) : et.date;
  const s = (phase: MarketPhase, candleDate: string, nextAt: KstTime, nextPhase: MarketPhase, reason: string | null = null): MarketState =>
    ({ group: 'US', phase, candleDate, reason, nextAt, nextPhase });
  if (!usOpen(d)) {
    // 다음 일봉은 다음 거래일 전날 20:00 (뉴욕) 주간거래로 시작한다
    const start = nyToKst(at(plusDays(nextUsOpen(plusDays(d, 1)), -1), 20));
    const phase: MarketPhase = start.date === kstOf(nowMs).date ? 'BEFORE_OPEN' : 'HOLIDAY';
    return s(phase, prevUsOpen(d), start, 'DAY', phase === 'HOLIDAY' ? usReason(d) : null);
  }
  if (t >= 20 * 60 || before(t, 4)) return s('DAY', d, nyToKst(at(d, 4)), 'PRE');
  if (before(t, 9, 30)) return s('PRE', d, nyToKst(at(d, 9, 30)), 'REGULAR');
  if (before(t, 16)) return s('REGULAR', d, nyToKst(at(d, 16)), 'AFTER');
  return s('AFTER', d, nyToKst(at(d, 20)), usOpen(plusDays(d, 1)) ? 'DAY' : 'CLOSED');
}

export function fxState(nowMs: number, cal: MarketCalendar): MarketState {
  const { date: d, minutes: t } = kstOf(nowMs);
  const s = (phase: MarketPhase, candleDate: string, nextAt: KstTime, nextPhase: MarketPhase, reason: string | null = null): MarketState =>
    ({ group: 'FX', phase, candleDate, reason, nextAt, nextPhase });
  if (before(t, 2) && krOpen(cal, plusDays(d, -1))) return s('FX_OPEN', plusDays(d, -1), at(d, 2), 'CLOSED');
  if (!krOpen(cal, d)) return s('HOLIDAY', prevKrOpen(d, cal), at(nextKrOpen(plusDays(d, 1), cal), 9), 'FX_OPEN', krReason(d));
  if (before(t, 9)) return s('BEFORE_OPEN', prevKrOpen(d, cal), at(d, 9), 'FX_OPEN');
  return s('FX_OPEN', d, at(plusDays(d, 1), 2), 'CLOSED');
}

export function marketStates(nowMs: number, cal: MarketCalendar): { [K in MarketGroup]: MarketState } {
  return { KR: krState(nowMs, cal), US: usState(nowMs), FX: fxState(nowMs, cal) };
}

/**
 * 한국 시간 date 하루(0~24시)의 시장별 구간: 국내 프리·정규·애프터, 미국 주간·프리·정규·애프터 (서머타임 반영),
 * 환율 열린 구간. 상세 화면의 "지금 열린 장" 시간표.
 */
export function timeline(date: string, cal: MarketCalendar): SessionSegment[] {
  const out: SessionSegment[] = [];
  if (krOpen(cal, date)) {
    out.push({ group: 'KR', phase: 'PRE', start: 480, end: 540 }, { group: 'KR', phase: 'REGULAR', start: 540, end: 930 }, { group: 'KR', phase: 'AFTER', start: 930, end: 1200 });
  }
  const dayStart = utcOf(at(date, 0));
  const dayEnd = utcOf(at(plusDays(date, 1), 0));
  for (const offset of [-1, 0, 1]) {
    const d = plusDays(date, offset);
    if (!usOpen(d)) continue;
    const parts: [MarketPhase, KstTime, KstTime][] = [
      ['DAY', at(plusDays(d, -1), 20), at(d, 4)],
      ['PRE', at(d, 4), at(d, 9, 30)],
      ['REGULAR', at(d, 9, 30), at(d, 16)],
      ['AFTER', at(d, 16), at(d, 20)],
    ];
    for (const [phase, from, to] of parts) {
      const s = Math.max(utcOf(nyToKst(from)), dayStart);
      const e = Math.min(utcOf(nyToKst(to)), dayEnd);
      if (s < e) out.push({ group: 'US', phase, start: (s - dayStart) / 60000, end: (e - dayStart) / 60000 });
    }
  }
  if (krOpen(cal, plusDays(date, -1))) out.push({ group: 'FX', phase: 'FX_OPEN', start: 0, end: 120 });
  if (krOpen(cal, date)) out.push({ group: 'FX', phase: 'FX_OPEN', start: 540, end: 1440 });
  const order = (g: MarketGroup) => MARKET_GROUPS.indexOf(g);
  return out.sort((a, b) => order(a.group) - order(b.group) || a.start - b.start);
}

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

/** "11/3(월)". */
export const dayText = (date: string): string => `${Number(date.slice(5, 7))}/${Number(date.slice(8, 10))}(${WEEKDAYS[dayOfWeek(date)]})`;

/** 다음 단계 안내: "08:00 프리마켓", "내일 08:00 프리마켓", "11/10(월) 10:00 주간거래". */
export function nextText(state: MarketState, nowMs: number): string | null {
  if (state.nextAt === null || state.nextPhase === null) return null;
  const today = kstOf(nowMs).date;
  const time = `${String(Math.floor(state.nextAt.minutes / 60)).padStart(2, '0')}:${String(state.nextAt.minutes % 60).padStart(2, '0')}`;
  const days = Math.round((Date.parse(state.nextAt.date) - Date.parse(today)) / 86400000);
  const day = days === 0 ? '' : days === 1 ? '내일 ' : `${dayText(state.nextAt.date)} `;
  return `${day}${time} ${PHASE_LABEL[state.nextPhase]}`;
}
