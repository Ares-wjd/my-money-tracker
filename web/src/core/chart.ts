// core 의 ChartCalculator 와 같은 규칙.
import type { Holding, InvestmentAccount, MarketSnapshot, Record } from '../model';
import { plusDays, plusMonths } from './dates';
import { currentPrice, latestUsdKrw, priceAt, usdKrwAt } from './market';
import { summarize } from './portfolio';

export type ChartInterval = 'DAY_1' | 'DAY_5' | 'DAY_10' | 'MONTH' | 'YEAR';
export const CHART_INTERVALS: readonly ChartInterval[] = ['DAY_1', 'DAY_5', 'DAY_10', 'MONTH', 'YEAR'];
export const CHART_INTERVAL_LABEL: { [K in ChartInterval]: string } = { DAY_1: '1일', DAY_5: '5일', DAY_10: '10일', MONTH: '1달', YEAR: '1년' };
/** 일 단위 보기의 간격(일)과 기본 표시 기간(개월). */
const STEP_DAYS: { [K in ChartInterval]: number } = { DAY_1: 1, DAY_5: 5, DAY_10: 10, MONTH: 0, YEAR: 0 };
const DEFAULT_MONTHS: { [K in ChartInterval]: number | null } = { DAY_1: 3, DAY_5: 6, DAY_10: 12, MONTH: null, YEAR: null };

export interface ChartPoint {
  date: string;
  valueKrw: number;
  investedKrw: number;
  dividendsKrw: number;
}

function endOfMonth(year: number, month: number): string {
  const day = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

/**
 * 그래프의 점 날짜 (오래된 순). 마지막 점은 항상 [today].
 * @param firstDate 첫 기록 날짜. 이보다 앞선 점은 만들지 않는다.
 * @param rangeFactor "이전 기간 더 보기" 를 누를 때마다 1씩 늘어난다 (일 단위 보기에서만 사용).
 */
export function pointDates(interval: ChartInterval, today: string, firstDate: string, rangeFactor = 1): string[] {
  if (firstDate > today) return [today];
  const [fy, fm] = [Number(firstDate.slice(0, 4)), Number(firstDate.slice(5, 7))];
  const [ty, tm] = [Number(today.slice(0, 4)), Number(today.slice(5, 7))];
  if (interval === 'MONTH') {
    const dates: string[] = [];
    for (let y = fy, m = fm; y * 12 + m < ty * 12 + tm; m === 12 ? ((y += 1), (m = 1)) : (m += 1)) dates.push(endOfMonth(y, m));
    return [...dates, today];
  }
  if (interval === 'YEAR') {
    const dates: string[] = [];
    for (let y = fy; y < ty; y++) dates.push(`${String(y).padStart(4, '0')}-12-31`);
    return [...dates, today];
  }
  const months = (DEFAULT_MONTHS[interval] ?? 3) * Math.max(1, rangeFactor);
  const monthStart = plusMonths(today, -months);
  const start = firstDate > monthStart ? firstDate : monthStart;
  const dates: string[] = [];
  for (let d = today; d >= start; d = plusDays(d, -STEP_DAYS[interval])) dates.push(d);
  return dates.reverse();
}

/** 일 단위 보기에서 더 이전 기간이 남아 있는지. */
export function hasEarlier(interval: ChartInterval, today: string, firstDate: string, rangeFactor: number): boolean {
  const months = DEFAULT_MONTHS[interval];
  if (months === null) return false;
  return plusMonths(today, -months * Math.max(1, rangeFactor)) > firstDate;
}

/**
 * 날짜별 평가금·투자금·누적 배당. [accountId] 가 있으면 그 계좌만.
 * 과거 날짜는 그날(또는 직전 거래일) 종가와 환율, 오늘은 현재가와 최신 환율을 쓴다.
 */
export function series(
  dates: readonly string[],
  today: string,
  accounts: readonly InvestmentAccount[],
  holdings: readonly Holding[],
  records: readonly Record[],
  snapshot: MarketSnapshot,
  manualUsdKrw: number | null,
  accountId: string | null = null,
): ChartPoint[] {
  const targets = accountId === null ? accounts : accounts.filter((a) => a.id === accountId);
  return dates.map((date) => {
    const isToday = date >= today;
    const fx = (isToday ? latestUsdKrw(snapshot)?.[1] : usdKrwAt(snapshot, date)) ?? manualUsdKrw;
    const summary = summarize(
      targets,
      holdings,
      records,
      fx,
      (h) => (isToday ? currentPrice(h, snapshot) : priceAt(h, snapshot, date)),
      date,
    );
    return { date, valueKrw: summary.valueKrw, investedKrw: summary.investedKrw, dividendsKrw: summary.dividendsKrw };
  });
}

