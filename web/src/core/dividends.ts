// core Dividends 와 같은 규칙 (docs/FEATURES.md 1-10): 받은 배당 · 예정 배당(한투 확정 + 기록 추정) · 달·해·종목별. 금액은 세후·원화.
import type { Holding, MarketSnapshot, Record } from '../model';
import { plusDays, plusMonths } from './dates';
import { keyOf, tradeUsdKrw, usdKrwAt } from './market';
import { position, type PortfolioSummary } from './portfolio';

export type DividendKind = 'RECEIVED' | 'CONFIRMED' | 'ESTIMATED';

/** 배당 한 건 (세후). date: 받은 날·지급일 (확정인데 지급일을 모르면 null), month: 'YYYY-MM'. */
export interface DividendItem {
  kind: DividendKind;
  date: string | null;
  month: string;
  holding: Holding;
  accountId: string;
  amountLocal: number;
  amountKrw: number;
}

export interface DividendTotals {
  received: number;
  confirmed: number;
  estimated: number;
}

export interface DividendYield {
  key: string;
  holding: Holding;
  last12Krw: number;
  yieldOnPrice: number | null;
  yieldOnCost: number | null;
}

export interface DividendHome {
  thisYear: number;
  lastYearSamePeriod: number;
  nextMonth: string;
  nextMonthPlanned: number;
  lastYearNextMonth: number;
  months: DividendTotals[];
  lastYearMonths: number[];
}

export const KR_TAX = 0.154;
export const US_TAX = 0.15;
const UNKNOWN_PAY_DAYS = 30;

const monthOf = (date: string) => date.slice(0, 7);
const plusYears = (date: string, years: number) => plusMonths(date, 12 * years);
const monthPlus = (month: string, n: number) => monthOf(plusMonths(`${month}-01`, n));
const monthEnd = (month: string) => plusDays(monthPlus(month, 1) + '-01', -1);
const isUsd = (h: Holding) => h.market !== 'KR';
const inAccounts = (accountId: string, ids: ReadonlySet<string> | null) => ids === null || ids.has(accountId);

/** 받은 배당 (기록). 해외는 받은 날 환율로 원화 (매매일 환율 → 그날 이전 환율 → 지금 환율, 모르면 빠진다). */
export function receivedDividends(
  holdings: readonly Holding[],
  records: readonly Record[],
  snapshot: MarketSnapshot,
  today: string,
  fxNow: number | null,
  ids: ReadonlySet<string> | null = null,
): DividendItem[] {
  const byId = new Map(holdings.map((h) => [h.id, h]));
  const out: DividendItem[] = [];
  for (const r of records) {
    if (r.type !== 'DIVIDEND' || !inAccounts(r.accountId, ids)) continue;
    const h = r.holdingId === null ? undefined : byId.get(r.holdingId);
    if (!h) continue;
    const fx = isUsd(h) ? tradeUsdKrw(snapshot, r.date, today) ?? usdKrwAt(snapshot, r.date) ?? fxNow : 1;
    if (fx === null) continue;
    out.push({ kind: 'RECEIVED', date: r.date, month: monthOf(r.date), holding: h, accountId: r.accountId, amountLocal: r.amount, amountKrw: r.amount * fx });
  }
  return out.sort((a, b) => (a.date! < b.date! ? -1 : a.date! > b.date! ? 1 : a.holding.name < b.holding.name ? -1 : a.holding.name > b.holding.name ? 1 : 0));
}

function quantityOn(holding: Holding, records: readonly Record[], date: string): number {
  return position(holding, records.filter((r) => r.holdingId === holding.id && r.date <= date)).quantity;
}

/**
 * 반복 주기 (개월, v2.15): 날짜순 배당 기록 own 의 마지막 두 번 간격이 20~40일이면 1 (월배당), 80~100일이면 3 (분기배당).
 * 마지막 기록이 (주기 + 20일)보다 오래됐으면 null.
 */
export function cycleStep(own: readonly Record[], today: string): number | null {
  if (own.length < 2) return null;
  const prev = own[own.length - 2].date;
  const last = own[own.length - 1].date;
  const within = (lo: number, hi: number) => plusDays(prev, lo) <= last && last <= plusDays(prev, hi);
  const step = within(20, 40) ? 1 : within(80, 100) ? 3 : null;
  if (step === null) return null;
  return plusDays(plusMonths(last, step), 20) < today ? null : step;
}

/** 예정 배당: 확정(한투 일정 × 지금 보유 수량 × (1 − 원천징수)) + 추정(반복 주기 → 최근 12개월 기록을 1년 뒤로, 같은 종목·같은 달은 앞의 것만). */
export function plannedDividends(
  summary: PortfolioSummary,
  records: readonly Record[],
  snapshot: MarketSnapshot,
  today: string,
  ids: ReadonlySet<string> | null = null,
): DividendItem[] {
  const fxNow = summary.usdKrw;
  const horizon = plusYears(today, 1);
  const start = plusDays(today, -365);
  const out: DividendItem[] = [];
  for (const account of summary.accounts) {
    if (!inAccounts(account.account.id, ids)) continue;
    for (const v of account.holdings) {
      const h = v.holding;
      const qty = v.position.quantity;
      if (qty <= 1e-9) continue;
      const fx = isUsd(h) ? fxNow : 1;
      if (fx === null) continue;
      const confirmedMonths = new Set<string>();
      const key = keyOf(h);
      for (const e of key === null ? [] : snapshot.dividends?.get(key) ?? []) {
        const keep = e.pay !== null ? e.pay >= today : e.record >= plusDays(today, -UNKNOWN_PAY_DAYS);
        const day = e.pay ?? e.record;
        if (!keep || day > horizon || e.perShare <= 0) continue;
        const local = e.perShare * qty * (1 - (e.currency === 'USD' ? US_TAX : KR_TAX));
        // 지급일을 모르면 기준일이 있는 달 (이미 지났으면 이번 달)
        const month = monthOf(e.pay ?? (e.record > today ? e.record : today));
        confirmedMonths.add(month);
        out.push({ kind: 'CONFIRMED', date: e.pay, month, holding: h, accountId: account.account.id, amountLocal: local, amountKrw: local * fx });
      }
      const own = records
        .filter((r) => r.type === 'DIVIDEND' && r.holdingId === h.id && r.accountId === account.account.id && r.date <= today)
        .sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0));
      // 반복 주기 (v2.15): 마지막 두 번의 간격으로 월·분기배당을 알아보고 마지막 주당 배당으로 이어 간다
      const cycleMonths = new Set<string>();
      const step = cycleStep(own, today);
      const last = own[own.length - 1];
      if (step !== null && last) {
        const then = quantityOn(h, records, last.date);
        if (then > 1e-9) {
          const local = (last.amount / then) * qty;
          for (let k = 1; ; k++) {
            const day = plusMonths(last.date, step * k);
            if (day > horizon) break;
            if (day <= today) continue;
            const month = monthOf(day);
            if (confirmedMonths.has(month)) continue;
            cycleMonths.add(month);
            out.push({ kind: 'ESTIMATED', date: day, month, holding: h, accountId: account.account.id, amountLocal: local, amountKrw: local * fx });
          }
        }
      }
      // 1년 전 기록: 확정·반복 주기가 없는 달만
      for (const r of own) {
        if (r.date <= start) continue;
        const day = plusYears(r.date, 1);
        if (day <= today || day > horizon) continue;
        const month = monthOf(day);
        if (confirmedMonths.has(month) || cycleMonths.has(month)) continue;
        const then = quantityOn(h, records, r.date);
        if (then <= 1e-9) continue;
        const local = (r.amount / then) * qty;
        out.push({ kind: 'ESTIMATED', date: day, month, holding: h, accountId: account.account.id, amountLocal: local, amountKrw: local * fx });
      }
    }
  }
  const sortDay = (d: DividendItem) => d.date ?? monthEnd(d.month);
  return out.sort((a, b) =>
    a.month !== b.month ? (a.month < b.month ? -1 : 1) : sortDay(a) !== sortDay(b) ? (sortDay(a) < sortDay(b) ? -1 : 1) : a.holding.name < b.holding.name ? -1 : a.holding.name > b.holding.name ? 1 : 0,
  );
}

export function dividendTotals(items: readonly DividendItem[]): DividendTotals {
  const sum = (k: DividendKind) => items.filter((d) => d.kind === k).reduce((s, d) => s + d.amountKrw, 0);
  return { received: sum('RECEIVED'), confirmed: sum('CONFIRMED'), estimated: sum('ESTIMATED') };
}

/** year 의 1~12월 합계. */
export function dividendMonths(items: readonly DividendItem[], year: number): DividendTotals[] {
  return Array.from({ length: 12 }, (_, i) => {
    const m = `${String(year).padStart(4, '0')}-${String(i + 1).padStart(2, '0')}`;
    return dividendTotals(items.filter((d) => d.month === m));
  });
}

/** 연도별 (첫 배당 해 ~ 올해). */
export function dividendYears(items: readonly DividendItem[], today: string): [number, DividendTotals][] {
  if (items.length === 0) return [];
  const thisYear = Number(today.slice(0, 4));
  const first = Math.min(thisYear, ...items.map((d) => Number(d.month.slice(0, 4))));
  const out: [number, DividendTotals][] = [];
  for (let y = first; y <= thisYear; y++) out.push([y, dividendTotals(items.filter((d) => Number(d.month.slice(0, 4)) === y))]);
  return out;
}

/** 홈 카드 (전체 계좌). */
export function dividendHome(received: readonly DividendItem[], planned: readonly DividendItem[], today: string): DividendHome {
  const year = Number(today.slice(0, 4));
  const lastYearToday = plusYears(today, -1);
  const next = monthPlus(monthOf(today), 1);
  const sum = (xs: readonly DividendItem[]) => xs.reduce((s, d) => s + d.amountKrw, 0);
  return {
    thisYear: sum(received.filter((d) => d.date!.slice(0, 4) === today.slice(0, 4) && d.date! <= today)),
    lastYearSamePeriod: sum(received.filter((d) => d.date!.slice(0, 4) === lastYearToday.slice(0, 4) && d.date! <= lastYearToday)),
    nextMonth: next,
    nextMonthPlanned: sum(planned.filter((d) => d.month === next)),
    lastYearNextMonth: sum(received.filter((d) => d.month === monthPlus(next, -12))),
    months: dividendMonths([...received, ...planned], year),
    lastYearMonths: dividendMonths(received, year - 1).map((t) => t.received),
  };
}

/** 종목별 최근 12개월 배당과 배당수익률 (현재가 기준 · 내 평단 기준), 전체. 같은 종목(시세 키)은 계좌가 달라도 합친다. */
export function dividendYields(
  summary: PortfolioSummary,
  received: readonly DividendItem[],
  today: string,
  ids: ReadonlySet<string> | null = null,
): { items: DividendYield[]; totalOnPrice: number | null; totalOnCost: number | null } {
  const fxNow = summary.usdKrw;
  const start = plusDays(today, -365);
  const keyFor = (h: Holding) => keyOf(h) ?? `id:${h.id}`;
  const acc = new Map<string, { holding: Holding; divLocal: number; divKrw: number; value: number; cost: number; qty: number }>();
  for (const d of received) {
    if (d.date! <= start || d.date! > today) continue;
    const k = keyFor(d.holding);
    const a = acc.get(k) ?? { holding: d.holding, divLocal: 0, divKrw: 0, value: 0, cost: 0, qty: 0 };
    a.divLocal += d.amountLocal;
    a.divKrw += d.amountKrw;
    acc.set(k, a);
  }
  for (const account of summary.accounts) {
    if (!inAccounts(account.account.id, ids)) continue;
    for (const v of account.holdings) {
      const a = acc.get(keyFor(v.holding));
      if (!a) continue;
      a.qty += v.position.quantity;
      a.value += v.marketValue ?? 0;
      a.cost += v.position.costBasis;
    }
  }
  let divAll = 0;
  let valueAll = 0;
  let costAll = 0;
  const items: DividendYield[] = [];
  for (const [key, a] of acc) {
    const held = a.qty > 1e-9;
    const fx = isUsd(a.holding) ? fxNow : 1;
    if (held && fx !== null && a.value > 0 && a.cost > 0) {
      divAll += a.divLocal * fx;
      valueAll += a.value * fx;
      costAll += a.cost * fx;
    }
    items.push({
      key,
      holding: a.holding,
      last12Krw: a.divKrw,
      yieldOnPrice: held && a.value > 0 ? a.divLocal / a.value : null,
      yieldOnCost: held && a.cost > 0 ? a.divLocal / a.cost : null,
    });
  }
  items.sort((a, b) => b.last12Krw - a.last12Krw);
  return { items, totalOnPrice: valueAll > 0 ? divAll / valueAll : null, totalOnCost: costAll > 0 ? divAll / costAll : null };
}

/** 비교 비율 (before 가 0 이면 null). */
export const dividendChange = (now: number, before: number): number | null => (before > 0.5 ? now / before - 1 : null);
