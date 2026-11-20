// core 의 ChartCalculator 와 같은 규칙.
import type { Holding, InvestmentAccount, MarketSnapshot, Record } from '../model';
import { plusDays, plusMonths } from './dates';
import { currentPrice, latestUsdKrw, PriceEstimator, usdKrwAt } from './market';
import { summarize } from './portfolio';

export type ChartInterval = 'DAY_1' | 'DAY_5' | 'DAY_10' | 'MONTH' | 'YEAR';
export const CHART_INTERVALS: readonly ChartInterval[] = ['DAY_1', 'DAY_5', 'DAY_10', 'MONTH', 'YEAR'];
export const CHART_INTERVAL_LABEL: { [K in ChartInterval]: string } = { DAY_1: '1일', DAY_5: '5일', DAY_10: '10일', MONTH: '1달', YEAR: '1년' };
/** 일 단위 보기의 간격(일). */
const STEP_DAYS: { [K in ChartInterval]: number } = { DAY_1: 1, DAY_5: 5, DAY_10: 10, MONTH: 0, YEAR: 0 };

/** 자산 추이 기간 (v2.14, core ChartRange 와 같은 규칙). */
export type ChartRange = 'ALL' | 'YEARS_5' | 'YEAR_1' | 'THIS_YEAR' | 'THIS_MONTH';
export const CHART_RANGES: readonly ChartRange[] = ['ALL', 'YEARS_5', 'YEAR_1', 'THIS_YEAR', 'THIS_MONTH'];
export const CHART_RANGE_LABEL: { [K in ChartRange]: string } = { ALL: '전체', YEARS_5: '5년', YEAR_1: '1년', THIS_YEAR: '올해', THIS_MONTH: '이번 달' };
const RANGE_INTERVALS: { [K in ChartRange]: readonly ChartInterval[] } = {
  ALL: CHART_INTERVALS,
  YEARS_5: ['DAY_5', 'DAY_10', 'MONTH', 'YEAR'],
  YEAR_1: ['DAY_1', 'DAY_5', 'DAY_10', 'MONTH'],
  THIS_YEAR: ['DAY_1', 'DAY_5', 'DAY_10', 'MONTH'],
  THIS_MONTH: ['DAY_1', 'DAY_5'],
};

/** 기간에 고를 수 있는 보기 단위. */
export const rangeIntervals = (range: ChartRange): readonly ChartInterval[] => RANGE_INTERVALS[range];

/** 기간 시작일 (전체는 null = 첫 기록일부터). */
export function rangeStart(range: ChartRange, today: string): string | null {
  switch (range) {
    case 'ALL':
      return null;
    case 'YEARS_5':
      return plusMonths(today, -60);
    case 'YEAR_1':
      return plusMonths(today, -12);
    case 'THIS_YEAR':
      return `${today.slice(0, 4)}-01-01`;
    case 'THIS_MONTH':
      return `${today.slice(0, 7)}-01`;
  }
}

/** interval 이 기간에 맞지 않으면 가장 가까운 단위 (같으면 짧은 쪽). */
export function fitInterval(range: ChartRange, interval: ChartInterval): ChartInterval {
  const allowed = RANGE_INTERVALS[range];
  if (allowed.includes(interval)) return interval;
  const at = CHART_INTERVALS.indexOf(interval);
  return [...allowed].sort((a, b) => Math.abs(CHART_INTERVALS.indexOf(a) - at) - Math.abs(CHART_INTERVALS.indexOf(b) - at) || CHART_INTERVALS.indexOf(a) - CHART_INTERVALS.indexOf(b))[0];
}

export interface ChartPoint {
  date: string;
  valueKrw: number;
  investedKrw: number;
  dividendsKrw: number;
  /** 그날 가격이 없는 보유 종목이 있는지 (폰이 아직 과거 종가를 받지 않음 → 평가금이 낮게 나옴) */
  missingPrice: boolean;
}

function endOfMonth(year: number, month: number): string {
  const day = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

/**
 * 그래프의 점 날짜 (오래된 순, 첫 기록일부터 전체 기간). 마지막 점은 항상 [today].
 * @param firstDate 첫 기록 날짜. 이보다 앞선 점은 만들지 않는다.
 */
export function pointDates(interval: ChartInterval, today: string, firstDate: string): string[] {
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
  const dates: string[] = [];
  for (let d = today; d >= firstDate; d = plusDays(d, -STEP_DAYS[interval])) dates.push(d);
  return dates.reverse();
}

/**
 * 날짜별 평가금·투자금·누적 배당. [accountId] 가 있으면 그 계좌만.
 * 과거 날짜는 그날 가격(PriceEstimator: 종가, 휴일은 직전 거래일, 시세를 모르는 기간은 앞뒤 가격으로 추정)과 환율,
 * 오늘은 현재가와 최신 환율을 쓴다. estimator 는 같은 종목의 다른 계좌 매매 단가도 쓰도록 전체 종목·기록으로 만든 것을 넘길 수 있다.
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
  estimator: PriceEstimator = new PriceEstimator(holdings, records, snapshot),
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
      (h) => (isToday ? currentPrice(h, snapshot) : estimator.at(h, date)),
      date,
    );
    return { date, valueKrw: summary.valueKrw, investedKrw: summary.investedKrw, dividendsKrw: summary.dividendsKrw, missingPrice: summary.missingPrice };
  });
}

