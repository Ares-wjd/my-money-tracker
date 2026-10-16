// core 의 PeriodReturns 와 같은 규칙: 올해 수익률·연평균 수익률·월별·연도별 수익 (v2.8). 배당은 평가금(예수금)에 들어 있어 수익에 포함.
// 계좌 여러 개를 합치면 계좌 사이 이체는 투자금 증감이 서로 상쇄된다.
import { plusDays } from './dates';
import { rate as formatRate, shortWon } from './format';
import { firstDates, type StoredChart } from './chartStore';
import type { PortfolioSummary } from './portfolio';
import type { Record } from '../model';

/** 하루의 평가금·투자금 합. 투자금이 늘거나 준 만큼이 그날 넣고 뺀 돈. */
export interface FlowPoint {
  date: string;
  valueKrw: number;
  investedKrw: number;
}

/** 한 기간의 수익: 수익금 = 끝 평가금 − 시작 평가금 − 넣은 돈, 수익률은 수정 디츠 방식 (분모가 0 이하면 null). */
export interface PeriodResult {
  from: string;
  to: string;
  profitKrw: number;
  rate: number | null;
  /** 기간 동안 넣은 돈 − 뺀 돈 */
  netFlowKrw: number;
}

/** 장기 수익률: 1년 이상이면 연평균(XIRR), 1년 미만이면 기간 수익률 (days 일). */
export interface LongTermReturn {
  annual: number | null;
  period: number | null;
  days: number;
}

const DAY_MS = 86_400_000;
const daysBetween = (a: string, b: string): number => Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / DAY_MS);
const maxDate = (a: string, b: string) => (a > b ? a : b);
const minDate = (a: string, b: string) => (a < b ? a : b);

/** a 이하인 가장 늦은 날짜의 값 (정렬된 날짜 목록에서). */
function floorValue<T>(dates: readonly string[], map: ReadonlyMap<string, T>, date: string): T | undefined {
  let lo = 0;
  let hi = dates.length - 1;
  let found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (dates[mid] <= date) {
      found = mid;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  return found < 0 ? undefined : map.get(dates[found]);
}

/**
 * 고른 계좌들의 일별 평가금·투자금 합: 첫 기록일부터 어제까지는 저장된 일별 값, 오늘은 todayValues (계좌 id → [평가금, 투자금]).
 * 저장된 값이 없는 계좌가 있으면(아직 계산 중) null. 저장한 마지막 날 뒤 며칠은 마지막 값을 이어 쓴다.
 */
export function flowSeries(
  firsts: ReadonlyMap<string, string>,
  stored: ReadonlyMap<string, StoredChart>,
  today: string,
  todayValues: ReadonlyMap<string, [number, number]>,
): FlowPoint[] | null {
  if (firsts.size === 0) return [];
  const first = [...firsts.values()].sort()[0];
  const charts = new Map<string, { dates: string[]; days: StoredChart['days'] }>();
  for (const [id, accountFirst] of firsts) {
    const chart = stored.get(id);
    if (!chart || chart.from !== accountFirst) {
      if (accountFirst < today) return null;
      continue;
    }
    charts.set(id, { dates: [...chart.days.keys()].sort(), days: chart.days });
  }
  const out: FlowPoint[] = [];
  for (let date = first; date < today; date = plusDays(date, 1)) {
    let value = 0;
    let invested = 0;
    for (const [id, accountFirst] of firsts) {
      if (date < accountFirst) continue;
      const c = charts.get(id);
      const day = c ? floorValue(c.dates, c.days, date) : undefined;
      if (!day) continue;
      value += day.valueKrw;
      invested += day.investedKrw;
    }
    out.push({ date, valueKrw: value, investedKrw: invested });
  }
  let value = 0;
  let invested = 0;
  for (const id of firsts.keys()) {
    const v = todayValues.get(id);
    if (v) {
      value += v[0];
      invested += v[1];
    }
  }
  out.push({ date: today, valueKrw: value, investedKrw: invested });
  return out;
}

/** ids 계좌(null 이면 전체)의 일별 평가금·투자금 합. 오늘은 summary 의 지금 값. 계산 중이면 null. */
export function flowSeriesFor(
  summary: PortfolioSummary,
  records: readonly Record[],
  stored: ReadonlyMap<string, StoredChart>,
  today: string,
  ids: ReadonlySet<string> | null = null,
): FlowPoint[] | null {
  const accounts = summary.accounts.filter((a) => ids === null || ids.has(a.account.id));
  const firsts = firstDates(accounts.map((a) => a.account), records);
  return flowSeries(firsts, stored, today, new Map(accounts.map((a) => [a.account.id, [a.valueKrw, a.investedKrw] as [number, number]])));
}

/**
 * fromDate ~ to (둘 다 포함) 의 수익. 시작 평가금은 시작 전날 값 (없으면 0). 기간에 점이 없으면 null.
 * 기록이 fromDate 보다 늦게 시작하면 첫 기록일부터.
 */
export function periodReturn(series: readonly FlowPoint[], fromDate: string, to: string): PeriodResult | null {
  if (series.length === 0) return null;
  const from = maxDate(series[0].date, fromDate);
  const points = series.filter((p) => p.date >= from && p.date <= to);
  if (points.length === 0) return null;
  const before = [...series].reverse().find((p) => p.date < from);
  const v0 = before?.valueKrw ?? 0;
  let invested = before?.investedKrw ?? 0;
  const end = points[points.length - 1].date;
  const total = daysBetween(plusDays(from, -1), end);
  let flows = 0;
  let weighted = 0;
  for (const p of points) {
    const flow = p.investedKrw - invested;
    invested = p.investedKrw;
    if (Math.abs(flow) < 1e-9) continue;
    flows += flow;
    // 넣은 돈은 그날 아침에 들어온 것으로 본다 (첫날 넣은 돈은 기간 전체만큼)
    weighted += (flow * (daysBetween(p.date, end) + 1)) / total;
  }
  const profit = points[points.length - 1].valueKrw - v0 - flows;
  const base = v0 + weighted;
  return { from, to: end, profitKrw: profit, rate: base > 1e-9 ? profit / base : null, netFlowKrw: flows };
}

/** 올해 (1월 1일 ~ 오늘). */
export const yearToDate = (series: readonly FlowPoint[], today: string): PeriodResult | null => periodReturn(series, `${today.slice(0, 4)}-01-01`, today);

/** 넣고 뺀 돈(투자금 증감)과 오늘 평가금으로 계산한 연환산 수익률. 풀 수 없으면 null. */
export function xirr(series: readonly FlowPoint[], today: string): number | null {
  if (series.length === 0) return null;
  const first = series[0].date;
  const flows: [number, number][] = [];
  let invested = 0;
  for (const p of series) {
    const flow = p.investedKrw - invested;
    invested = p.investedKrw;
    if (Math.abs(flow) > 1e-9) flows.push([daysBetween(first, p.date) / 365, -flow]);
  }
  flows.push([daysBetween(first, today) / 365, series[series.length - 1].valueKrw]);
  if (!flows.some(([, cf]) => cf < 0) || !flows.some(([, cf]) => cf > 0)) return null;
  const npv = (r: number) => flows.reduce((s, [t, cf]) => s + cf / Math.pow(1 + r, t), 0);
  let lo = -0.9999;
  let hi = 10;
  let fLo = npv(lo);
  if (fLo * npv(hi) > 0) return null;
  for (let i = 0; i < 200; i++) {
    const mid = (lo + hi) / 2;
    const fMid = npv(mid);
    if (fLo * fMid <= 0) hi = mid;
    else {
      lo = mid;
      fLo = fMid;
    }
  }
  return (lo + hi) / 2;
}

/** 처음부터 오늘까지: 1년(365일) 이상이면 연평균 수익률(XIRR), 미만이면 기간 수익률. */
export function longTermReturn(series: readonly FlowPoint[], today: string): LongTermReturn | null {
  if (series.length === 0) return null;
  const days = daysBetween(series[0].date, today);
  if (days < 365) return { annual: null, period: periodReturn(series, series[0].date, today)?.rate ?? null, days };
  return { annual: xirr(series, today), period: null, days };
}

const monthStart = (date: string) => `${date.slice(0, 7)}-01`;
function addMonths(ym: string, n: number): string {
  const y = Number(ym.slice(0, 4));
  const m = Number(ym.slice(5, 7)) - 1 + n;
  const yy = y + Math.floor(m / 12);
  const mm = ((m % 12) + 12) % 12;
  return `${String(yy).padStart(4, '0')}-${String(mm + 1).padStart(2, '0')}-01`;
}

/** 최근 count 달의 월별 수익 (오래된 달부터, 기록이 시작되기 전 달은 뺀다). 이번 달은 오늘까지. */
export function monthlyReturns(series: readonly FlowPoint[], today: string, count = 12): PeriodResult[] {
  if (series.length === 0) return [];
  const first = series[0].date;
  const out: PeriodResult[] = [];
  for (let back = count - 1; back >= 0; back--) {
    const start = addMonths(monthStart(today), -back);
    const end = minDate(plusDays(addMonths(start, 1), -1), today);
    if (end < first) continue;
    const r = periodReturn(series, maxDate(start, first), end);
    if (r) out.push(r);
  }
  return out;
}

/** 연도별 수익 (첫해부터 올해까지, 올해는 오늘까지). */
export function yearlyReturns(series: readonly FlowPoint[], today: string): PeriodResult[] {
  if (series.length === 0) return [];
  const first = series[0].date;
  const out: PeriodResult[] = [];
  for (let y = Number(first.slice(0, 4)); y <= Number(today.slice(0, 4)); y++) {
    const r = periodReturn(series, maxDate(`${y}-01-01`, first), minDate(`${y}-12-31`, today));
    if (r) out.push(r);
  }
  return out;
}

/** 기간 길이 글: 31일 미만 "n일", 그 이상 "n개월". */
export const lengthText = (days: number): string => (days < 31 ? `${days}일` : `${Math.floor(days / 30)}개월`);

const signedShort = (v: number) => (v > 0.5 ? '+' : '') + shortWon(v);

/** 홈·계좌 상세 한 줄: "올해 ▲8.20% (+234.0만원) · 연평균 ▲11.40%" 또는 "… · 기간 ▲5.00% (2개월)". */
export function periodSummaryText(ytd: PeriodResult | null, long: LongTermReturn | null): string {
  const parts: string[] = [];
  if (ytd) parts.push(`올해 ${formatRate(ytd.rate)} (${signedShort(ytd.profitKrw)})`);
  if (long) {
    if (long.annual !== null) parts.push(`연평균 ${formatRate(long.annual)}`);
    else if (long.period !== null) parts.push(`기간 ${formatRate(long.period)} (${lengthText(long.days)})`);
  }
  return parts.join(' · ');
}
