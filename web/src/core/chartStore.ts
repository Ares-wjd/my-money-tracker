// core 의 ChartStore 와 같은 규칙: 그래프 일별 값 미리 계산·저장. 요약값(해시)은 폰과 글자 하나까지 같아야 한다.
import type { Holding, InvestmentAccount, MarketSnapshot, Record } from '../model';
import { isBrokerLinked } from './cash';
import { pointDates, series, type ChartInterval, type ChartPoint } from './chart';
import { plusDays } from './dates';
import { keyOf } from './market';

export const CHART_STORE_VERSION = 1;

/** 저장된 하루 값 (그 계좌의 평가금·투자금·누적 배당, 그날 가격이 없는 종목이 있었는지). */
export interface DayValue {
  valueKrw: number;
  investedKrw: number;
  dividendsKrw: number;
  missingPrice: boolean;
}

/** 계좌 하나의 저장된 일별 값 (users/{uid}/chartDaily/{계좌id}). from 첫 기록일, to 계산한 마지막 날. */
export interface StoredChart {
  version: number;
  from: string;
  to: string;
  days: Map<string, DayValue>;
  recSig: Map<string, string>;
  accSig: string;
  priceSig: Map<string, string>;
  anchor: string | null;
}

/** 계산 계획: start ~ end 를 다시 계산하고, keep (start 이전 저장값) 은 그대로 둔다. */
export interface ChartPlan {
  accountId: string;
  from: string;
  start: string;
  end: string;
  keep: Map<string, DayValue>;
  recSig: Map<string, string>;
  accSig: string;
  priceSig: Map<string, string>;
  anchor: string | null;
}

export interface ChartView {
  points: ChartPoint[];
  /** 아직 계산하지 않은 날이 있어 점을 뺐는지 */
  pending: boolean;
}

/** FNV-1a 32비트 (UTF-16 글자 단위), 8자리 16진수. */
export function hash(text: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0).toString(16).padStart(8, '0');
}

const view = new DataView(new ArrayBuffer(8));

/** 숫자 → 64비트 실수 비트 16진수 (−0 은 0). */
export function num(x: number): string {
  view.setFloat64(0, x === 0 ? 0 : x);
  return view.getUint32(0).toString(16).padStart(8, '0') + view.getUint32(4).toString(16).padStart(8, '0');
}

export function recordLine(r: Record): string {
  return [
    r.id, r.accountId, r.type, r.date, r.currency, num(r.amount), num(r.krwAmount), r.toAccountId ?? '', r.holdingId ?? '',
    num(r.quantity), num(r.price), num(r.fee), num(r.tax), r.cost === null ? '' : num(r.cost), r.initial ? '1' : '0',
    r.externalId ?? '', String(r.createdAt),
  ].join('|');
}

export function holdingLine(h: Holding): string {
  return [h.id, h.accountId, h.code, h.market, h.assetType, h.manualPrice === null ? '' : num(h.manualPrice), h.manualPriceDate ?? ''].join('|');
}

/** 이 계좌와 관련된 기록 (보낸·받은 이체 포함). */
export const related = (accountId: string, records: readonly Record[]): Record[] =>
  records.filter((r) => r.accountId === accountId || r.toAccountId === accountId);

const sortStrings = (list: string[]) => list.sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));

/** 날짜별 기록 요약 (end 까지). */
export function recordSigs(rel: readonly Record[], end: string): Map<string, string> {
  const byDate = new Map<string, string[]>();
  for (const r of rel) {
    if (r.date > end) continue;
    const list = byDate.get(r.date) ?? [];
    list.push(recordLine(r));
    byDate.set(r.date, list);
  }
  return new Map([...byDate].map(([d, lines]) => [d, hash(sortStrings(lines).join('\n'))]));
}

/** 종목·연결 여부·직접 입력 환율 요약 (바뀌면 처음부터 다시 계산). */
export function accountSig(holdings: readonly Holding[], linked: boolean, manualUsdKrw: number | null): string {
  const lines = [`linked=${linked ? '1' : '0'}`, `fx=${manualUsdKrw === null ? '' : num(manualUsdKrw)}`, ...sortStrings(holdings.map(holdingLine))];
  return hash(lines.join('\n'));
}

/** 종가·환율 요약: "키|연-월" 마다 그달 from ~ end 값, "키|base" 는 from 에 쓰는 직전 값. 값이 없는 달은 넣지 않는다. */
export function priceSigs(keys: Iterable<string>, snapshot: MarketSnapshot, from: string, end: string): Map<string, string> {
  const out = new Map<string, string>();
  const add = (name: string, values: Map<string, number> | undefined) => {
    if (!values || from > end) return;
    const dates = sortStrings([...values.keys()]);
    const before = plusDays(from, -1);
    let base: string | null = null;
    const months = new Map<string, string[]>();
    for (const d of dates) {
      if (d <= before) base = d;
      else if (d <= end) {
        const m = d.slice(0, 7);
        const list = months.get(m) ?? [];
        list.push(`${d}=${num(values.get(d)!)}`);
        months.set(m, list);
      }
    }
    if (base !== null) out.set(`${name}|base`, hash(`${base}=${num(values.get(base)!)}`));
    for (const [m, list] of months) out.set(`${name}|${m}`, hash(list.join(';')));
  };
  for (const key of sortStrings([...keys])) add(key, snapshot.prices.get(key)?.closes);
  add('fx', snapshot.usdKrw);
  return out;
}

/** 직접 입력 계좌의 거꾸로 추정 경계: 통화별 첫 예수금 입력일 중 가장 늦은 날 (없거나 연결 계좌면 null). */
export function anchorLimit(accountId: string, rel: readonly Record[], linked: boolean): string | null {
  if (linked) return null;
  let result: string | null = null;
  for (const currency of ['KRW', 'USD'] as const) {
    let first: string | null = null;
    for (const r of rel) {
      if (r.type === 'CASH_BALANCE' && r.accountId === accountId && r.currency === currency && (first === null || r.date < first)) first = r.date;
    }
    if (first !== null && (result === null || first > result)) result = first;
  }
  return result;
}

/** 요약 두 개를 비교해 다른 키 중 가장 이른 날. 같으면 null. */
function earliestDiff(a: Map<string, string>, b: Map<string, string>, dateOf: (key: string) => string): string | null {
  let result: string | null = null;
  for (const key of new Set([...a.keys(), ...b.keys()])) {
    if (a.get(key) === b.get(key)) continue;
    const d = dateOf(key);
    if (result === null || d < result) result = d;
  }
  return result;
}

/** 계좌 하나의 계산 계획. 기록이 없거나 다시 계산할 것이 없으면 null. end 는 저장할 마지막 날(어제). */
export function planChart(
  account: InvestmentAccount,
  holdings: readonly Holding[],
  records: readonly Record[],
  snapshot: MarketSnapshot,
  manualUsdKrw: number | null,
  stored: StoredChart | null,
  end: string,
  force = false,
): ChartPlan | null {
  const rel = related(account.id, records);
  if (rel.length === 0) return null;
  const from = rel.reduce((m, r) => (r.date < m ? r.date : m), rel[0].date);
  const own = holdings.filter((h) => h.accountId === account.id);
  const linked = isBrokerLinked(account.id, rel);
  const keys = new Set(own.map(keyOf).filter((k): k is string => k !== null));
  const accSig = accountSig(own, linked, manualUsdKrw);
  const anchor = anchorLimit(account.id, rel, linked);
  const recSig = recordSigs(rel, end);
  const priceSig = priceSigs(keys, snapshot, from, end);
  const plan = (start: string, keep: Map<string, DayValue>): ChartPlan => ({ accountId: account.id, from, start, end, keep, recSig, accSig, priceSig, anchor });

  const full = force || stored === null || stored.version !== CHART_STORE_VERSION || stored.accSig !== accSig || stored.from !== from ||
    (!linked && stored.anchor !== anchor);
  if (full) return plan(from, new Map());
  // 저장할 때 계산한 날(to)까지만 비교하고, 그 뒤 날짜는 새로 계산할 범위
  const recDiff = earliestDiff(recordSigs(rel, stored.to), stored.recSig, (k) => k);
  const priceDiff = earliestDiff(priceSigs(keys, snapshot, from, stored.to), stored.priceSig, (k) => {
    const tail = k.slice(k.lastIndexOf('|') + 1);
    return tail === 'base' ? from : `${tail}-01`;
  });
  let start = [recDiff, priceDiff, plusDays(stored.to, 1)].filter((d): d is string => d !== null).sort()[0];
  if (start < from) start = from;
  if (start > end) return null;
  // 첫 예수금 입력일 이전이 바뀌면 거꾸로 추정한 앞 날짜들도 바뀐다
  if (anchor !== null && start <= anchor) start = from;
  return plan(start, new Map([...stored.days].filter(([d]) => d >= from && d < start)));
}

/** 계획의 날짜들 (start ~ end, 하루씩). */
export function planDates(plan: ChartPlan): string[] {
  const dates: string[] = [];
  for (let d = plan.start; d <= plan.end; d = plusDays(d, 1)) dates.push(d);
  return dates;
}

/** 계획의 날짜들 중 dates 를 계산한다 (화면이 멈추지 않게 나눠서 부른다). */
export function computeChart(
  plan: ChartPlan,
  account: InvestmentAccount,
  holdings: readonly Holding[],
  records: readonly Record[],
  snapshot: MarketSnapshot,
  manualUsdKrw: number | null,
  dates: readonly string[],
): Map<string, DayValue> {
  const own = holdings.filter((h) => h.accountId === account.id);
  const points = series(dates, plusDays(plan.end, 1), [account], own, related(account.id, records), snapshot, manualUsdKrw);
  return new Map(points.map((p) => [p.date, { valueKrw: p.valueKrw, investedKrw: p.investedKrw, dividendsKrw: p.dividendsKrw, missingPrice: p.missingPrice }]));
}

/** 계산 결과로 저장할 값. */
export function buildChart(plan: ChartPlan, computed: Map<string, DayValue>): StoredChart {
  return {
    version: CHART_STORE_VERSION, from: plan.from, to: plan.end, days: new Map([...plan.keep, ...computed]),
    recSig: plan.recSig, accSig: plan.accSig, priceSig: plan.priceSig, anchor: plan.anchor,
  };
}

/** 계좌별 첫 기록일 (기록이 있는 계좌만). */
export function firstDates(accounts: readonly InvestmentAccount[], records: readonly Record[]): Map<string, string> {
  const out = new Map<string, string>();
  for (const a of accounts) {
    for (const r of related(a.id, records)) {
      const cur = out.get(a.id);
      if (cur === undefined || r.date < cur) out.set(a.id, r.date);
    }
  }
  return out;
}

/** 저장된 값으로 그래프 점을 만든다. 계좌가 아직 계산하지 않은 날은 점을 뺀다. todayPoint 는 현재가로 바로 계산한 오늘 값. */
export function assembleChart(
  interval: ChartInterval,
  today: string,
  firsts: Map<string, string>,
  stored: Map<string, StoredChart>,
  todayPoint: ChartPoint,
): ChartView {
  if (firsts.size === 0) return { points: [todayPoint], pending: false };
  const first = [...firsts.values()].sort()[0];
  let pending = false;
  const points: ChartPoint[] = [];
  for (const date of pointDates(interval, today, first)) {
    if (date >= today) {
      points.push(todayPoint);
      continue;
    }
    let valueKrw = 0;
    let investedKrw = 0;
    let dividendsKrw = 0;
    let missingPrice = false;
    let ok = true;
    for (const [id, accountFirst] of firsts) {
      if (date < accountFirst) continue;
      const s = stored.get(id);
      const day = s && s.from === accountFirst && date <= s.to ? s.days.get(date) : undefined;
      if (!day) {
        ok = false;
        break;
      }
      valueKrw += day.valueKrw;
      investedKrw += day.investedKrw;
      dividendsKrw += day.dividendsKrw;
      missingPrice = missingPrice || day.missingPrice;
    }
    if (ok) points.push({ date, valueKrw, investedKrw, dividendsKrw, missingPrice });
    else pending = true;
  }
  return { points, pending };
}
