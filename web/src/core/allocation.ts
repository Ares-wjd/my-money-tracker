// core 의 AllocationCalculator 와 같은 규칙: 비중 설정의 실제·목표 비중, 매수 계산기.
import type { Holding } from '../model';
import { marketCurrency } from '../model';
import { cashToKrw, type AccountSummary } from './portfolio';

export const CASH_KEY = 'CASH';

/** 비중 설정: 대상 계좌들의 종목별 목표 비중(%). 키는 keyOf (시장:코드), 예수금은 CASH_KEY. */
export interface AllocationTarget {
  id: string;
  name: string;
  accountIds: string[];
  /** 종목 키 → 목표 비중 (%, 0~100) */
  targets: { [key: string]: number };
  /** 예수금을 비중에 넣을지 */
  includeCash: boolean;
  /** 차이 강조 기준 (%p) */
  threshold: number;
  createdAt: number;
  /** 비중 계산에서 뺄 종목 키 */
  excluded: string[];
}

/** 비중 표의 한 줄 (종목 하나 또는 예수금). 비중은 0~1. */
export interface AllocationRow {
  key: string;
  name: string;
  /** 대표 종목 (티커 배지용). 예수금·지운 종목이면 null */
  holding: Holding | null;
  quantity: number;
  valueKrw: number;
  /** 1주 원화 가격 (계산기 주 수용) */
  priceKrw: number | null;
  /** 1주 현지 통화 가격 */
  priceLocal: number | null;
  currentWeight: number;
  targetWeight: number;
  /** 실제 − 목표 (%p) */
  diffPoints: number;
  isCash: boolean;
}

export interface AllocationView {
  target: AllocationTarget;
  /** 목표가 큰 순, 같으면 평가금이 큰 순 */
  rows: AllocationRow[];
  totalKrw: number;
  missingPrice: boolean;
}

export interface TradeSuggestion {
  row: AllocationRow;
  /** 살(+)/팔(−) 원화 금액 */
  amountKrw: number;
  /** 대략 주 수 (내림). 가격이 없거나 예수금이면 null */
  shares: number | null;
}

/** 목표 합계 (예수금 미포함이면 예수금 목표는 빼고). */
export function targetSum(t: AllocationTarget): number {
  return Object.entries(t.targets)
    .filter(([k]) => t.includeCash || k !== CASH_KEY)
    .reduce((s, [, v]) => s + v, 0);
}

/** 종목 키: 같은 종목이 여러 계좌에 있어도 같은 키. 코드가 없는 펀드·채권은 종목 하나로. */
export function keyOf(h: Holding): string {
  return h.code.trim() === '' ? `id:${h.id}` : `${h.market}:${h.code.trim().toUpperCase()}`;
}

/** 강조할 줄: |차이| 가 기준 이상. */
export function isHighlighted(view: AllocationView, row: AllocationRow): boolean {
  return view.target.threshold > 0 && Math.abs(row.diffPoints) >= view.target.threshold - 1e-9;
}

/** 대상 계좌들의 종목을 합쳐 실제·목표 비중을 계산한다. */
export function allocationView(target: AllocationTarget, accounts: readonly AccountSummary[], usdKrw: number | null): AllocationView {
  const included = accounts.filter((a) => target.accountIds.includes(a.account.id));
  const byKey = new Map<string, AccountSummary['holdings']>();
  const excluded = new Set(target.excluded);
  for (const v of included.flatMap((a) => a.holdings)) {
    const key = keyOf(v.holding);
    // 제외한 종목은 합계·비중·계산기 모두에서 뺀다
    if (excluded.has(key)) continue;
    byKey.set(key, [...(byKey.get(key) ?? []), v]);
  }
  let missingPrice = false;
  type Raw = { key: string; name: string; holding: Holding | null; qty: number; value: number; priceKrw: number | null; priceLocal: number | null };
  const raws: Raw[] = [];
  for (const [key, valuations] of byKey) {
    const qty = valuations.reduce((s, v) => s + v.position.quantity, 0);
    const held = qty > 0;
    if (!held && (target.targets[key] ?? 0) <= 0) continue;
    if (held && valuations.some((v) => v.position.quantity > 0 && v.marketValueKrw === null)) missingPrice = true;
    const value = valuations.reduce((s, v) => s + (v.marketValueKrw ?? 0), 0);
    const sample = valuations.reduce((a, b) => (b.position.quantity > a.position.quantity ? b : a));
    const local = sample.price?.price ?? null;
    const fx = marketCurrency(sample.holding.market) === 'USD' ? usdKrw : 1;
    raws.push({ key, name: sample.holding.name, holding: sample.holding, qty, value, priceKrw: local !== null && fx !== null ? local * fx : null, priceLocal: local });
  }
  if (target.includeCash) {
    const cash = included.reduce((s, a) => s + cashToKrw(a.cash, usdKrw), 0);
    raws.push({ key: CASH_KEY, name: '예수금', holding: null, qty: 0, value: cash, priceKrw: null, priceLocal: null });
  }
  // 목표에는 있는데 대상 계좌에 종목이 없는 키 (종목을 지웠거나 계좌를 뺐을 때)
  for (const [k, v] of Object.entries(target.targets)) {
    if (v > 0 && k !== CASH_KEY && !excluded.has(k) && !raws.some((r) => r.key === k)) {
      raws.push({ key: k, name: k.includes(':') ? k.slice(k.indexOf(':') + 1) : k, holding: null, qty: 0, value: 0, priceKrw: null, priceLocal: null });
    }
  }
  const total = raws.reduce((s, r) => s + r.value, 0);
  const rows = raws
    .map((r): AllocationRow => {
      const currentWeight = total > 0 ? r.value / total : 0;
      const targetWeight = (target.targets[r.key] ?? 0) / 100;
      return {
        key: r.key, name: r.name, holding: r.holding, quantity: r.qty, valueKrw: r.value, priceKrw: r.priceKrw, priceLocal: r.priceLocal,
        currentWeight, targetWeight, diffPoints: (currentWeight - targetWeight) * 100, isCash: r.key === CASH_KEY,
      };
    })
    .sort((a, b) => b.targetWeight - a.targetWeight || b.valueKrw - a.valueKrw);
  return { target, rows, totalKrw: total, missingPrice };
}

function suggestion(row: AllocationRow, amountKrw: number): TradeSuggestion {
  const shares = row.priceKrw !== null && row.priceKrw > 0 && !row.isCash ? Math.floor(Math.abs(amountKrw) / row.priceKrw) : null;
  return { row, amountKrw, shares };
}

/**
 * 팔지 않고 amountKrw 만 더 사서 목표에 가장 가깝게: 넣은 뒤 합계 × 목표 − 지금 평가금 = 부족분.
 * 부족분 합이 넣을 돈보다 크면 부족분 비율로 나누고, 남으면 목표 비율로 더 나눈다. 목표가 없는 종목은 사지 않는다.
 */
export function buyOnly(view: AllocationView, amountKrw: number): TradeSuggestion[] {
  if (amountKrw <= 0) return [];
  const rows = view.rows.filter((r) => r.targetWeight > 0);
  const sum = rows.reduce((s, r) => s + r.targetWeight, 0);
  if (rows.length === 0 || sum <= 0) return [];
  const newTotal = view.totalKrw + amountKrw;
  const deficits = rows.map((r) => Math.max(0, (r.targetWeight / sum) * newTotal - r.valueKrw));
  const deficitSum = deficits.reduce((s, d) => s + d, 0);
  const buys =
    deficitSum >= amountKrw && deficitSum > 0
      ? deficits.map((d) => (d / deficitSum) * amountKrw)
      : rows.map((r, i) => deficits[i] + ((amountKrw - deficitSum) * r.targetWeight) / sum);
  return rows.map((r, i) => suggestion(r, buys[i])).filter((s) => s.amountKrw >= 0.5);
}

/** 사고팔아 정확히 맞추기: 종목별 목표 × 합계 − 평가금 (+ 사기, − 팔기). */
export function rebalance(view: AllocationView): TradeSuggestion[] {
  const sum = view.rows.reduce((s, r) => s + r.targetWeight, 0);
  if (sum <= 0) return [];
  return view.rows
    .map((r) => suggestion(r, (r.targetWeight / sum) * view.totalKrw - r.valueKrw))
    .filter((s) => Math.abs(s.amountKrw) >= 0.5);
}
