// core Benchmark 와 같은 규칙 (docs/FEATURES.md 1-8): "같은 날 같은 돈을 지수에 넣었다면".
// 일별 평가금·투자금에서 날마다 투자금이 늘거나 준 만큼을 그날 지수(원화)로 사고판 가상 평가금을 만든다.
import type { MarketSnapshot } from '../model';
import { latestUsdKrw } from './market';
import { plusDays } from './dates';
import { periodReturn, type FlowPoint, type PeriodResult } from './periodReturns';

export type BenchmarkIndex = 'SPX' | 'COMP' | 'KOSPI';
export const BENCHMARK_INDICES: readonly BenchmarkIndex[] = ['SPX', 'COMP', 'KOSPI'];
export const BENCHMARK_KEY: { [K in BenchmarkIndex]: string } = { SPX: 'IDX_SPX', COMP: 'IDX_COMP', KOSPI: 'IDX_KOSPI' };
export const BENCHMARK_LABEL: { [K in BenchmarkIndex]: string } = { SPX: 'S&P500', COMP: '나스닥', KOSPI: 'KOSPI' };
const USD: { [K in BenchmarkIndex]: boolean } = { SPX: true, COMP: true, KOSPI: false };

/** 정렬된 날짜·값에서 date 이하 가장 늦은 값 (값 조건 ok 를 만족하는 것), 없으면 undefined. */
function floorAt(dates: readonly string[], values: readonly number[], date: string, ok: (v: number) => boolean = () => true): number | undefined {
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
  for (let i = found; i >= 0; i--) if (ok(values[i])) return values[i];
  return undefined;
}

/** 지수 하나의 원화 값 계산기 (날짜 목록을 한 번만 정렬해 둔다). */
function levelFn(index: BenchmarkIndex, snapshot: MarketSnapshot, today: string): ((date: string) => number | null) | null {
  const history = snapshot.prices.get(BENCHMARK_KEY[index]);
  if (!history) return null;
  const cDates = [...history.closes.keys()].sort();
  const cValues = cDates.map((d) => history.closes.get(d)!);
  const fDates = [...snapshot.usdKrw.keys()].sort();
  const fValues = fDates.map((d) => snapshot.usdKrw.get(d)!);
  const firstFx = fValues.find((v) => v > 0);
  const latestFx = latestUsdKrw(snapshot)?.[1] ?? null;
  return (date) => {
    const raw =
      date >= today
        ? (history.latest && history.latest.price > 0 ? history.latest.price : undefined) ?? cValues[cValues.length - 1]
        : floorAt(cDates, cValues, date) ?? cValues[0];
    if (raw === undefined || !(raw > 0)) return null;
    if (!USD[index]) return raw;
    const fx = date >= today ? latestFx : floorAt(fDates, fValues, date, (v) => v > 0) ?? firstFx ?? null;
    return fx !== null && fx > 0 ? raw * fx : null;
  };
}

/** date 의 지수 원화 값 (core Benchmark.levelKrw). */
export function levelKrw(index: BenchmarkIndex, snapshot: MarketSnapshot, date: string, today: string): number | null {
  return levelFn(index, snapshot, today)?.(date) ?? null;
}

/** 가상 평가금 일별 값 (series 와 같은 날짜, 투자금은 그대로). 지수 값을 모르는 날이 있으면 null. */
export function simulate(series: readonly FlowPoint[], index: BenchmarkIndex, snapshot: MarketSnapshot, today: string): FlowPoint[] | null {
  if (series.length === 0) return null;
  const level = levelFn(index, snapshot, today);
  if (!level) return null;
  let units = 0;
  let invested = 0;
  const out: FlowPoint[] = [];
  for (const p of series) {
    const l = level(p.date);
    if (l === null) return null;
    units += (p.investedKrw - invested) / l;
    invested = p.investedKrw;
    out.push({ date: p.date, valueKrw: units * l, investedKrw: p.investedKrw });
  }
  return out;
}

/** 지수 종가가 from 무렵(7일 안)부터 있는지: 아니면 그 기간 수익률은 "-" (아직 받는 중). */
export function covers(index: BenchmarkIndex, snapshot: MarketSnapshot, from: string): boolean {
  const history = snapshot.prices.get(BENCHMARK_KEY[index]);
  if (!history || history.closes.size === 0) return false;
  const first = [...history.closes.keys()].sort()[0];
  return first <= plusDays(from, 7);
}

/** 지수 가상 평가금의 기간 수익 (periodReturn 과 같은 방식). 지수 종가가 그 기간을 다 덮지 못하면 null ("-"). */
export function benchmarkPeriod(
  simulated: readonly FlowPoint[] | null,
  index: BenchmarkIndex,
  snapshot: MarketSnapshot,
  from: string,
  to: string,
): PeriodResult | null {
  if (!simulated) return null;
  const result = periodReturn(simulated, from, to);
  return result && covers(index, snapshot, result.from) ? result : null;
}

/** 그래프 점 날짜마다 가상 평가금 (그날 또는 직전 값, 앞에 없으면 null). */
export function valuesAt(simulated: readonly FlowPoint[], dates: readonly string[]): (number | null)[] {
  const ds = simulated.map((p) => p.date);
  const vs = simulated.map((p) => p.valueKrw);
  return dates.map((d) => floorAt(ds, vs, d) ?? null);
}

/** 오늘 기준: 가상 평가금과 내 평가금 − 가상 평가금. */
export function todayDiff(series: readonly FlowPoint[], simulated: readonly FlowPoint[]): { valueKrw: number; diffKrw: number } | null {
  const mine = series[series.length - 1];
  const sim = simulated[simulated.length - 1];
  if (!mine || !sim) return null;
  return { valueKrw: sim.valueKrw, diffKrw: mine.valueKrw - sim.valueKrw };
}

/** 수익률 비교 한 줄: "S&P500보다 3.90%p, 나스닥보다 6.80%p 낮고, KOSPI보다 2.90%p 높아요". */
export function compareText(mine: number | null, others: readonly [BenchmarkIndex, number | null][]): string | null {
  if (mine === null) return null;
  const known = others.filter((o): o is [BenchmarkIndex, number] => o[1] !== null).map(([i, r]) => [i, (mine - r) * 100] as const);
  if (known.length === 0) return null;
  const list = (items: readonly (readonly [BenchmarkIndex, number])[]) =>
    items.map(([i, d]) => `${BENCHMARK_LABEL[i]}보다 ${Math.abs(d).toFixed(2)}%p`).join(', ');
  const lower = known.filter((k) => k[1] <= -0.005);
  const higher = known.filter((k) => k[1] >= 0.005);
  const same = known.filter((k) => Math.abs(k[1]) < 0.005);
  const parts: string[] = [];
  if (lower.length) parts.push(list(lower) + ' 낮');
  if (higher.length) parts.push(list(higher) + ' 높');
  if (same.length) parts.push(same.map((k) => BENCHMARK_LABEL[k[0]]).join(', ') + ' 수익률과 같');
  return parts.join('고, ') + '아요';
}
