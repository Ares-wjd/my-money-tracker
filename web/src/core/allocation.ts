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
  /** 허용 범위의 절대 기준 A (%p). 0 이면 쓰지 않음. 예전 "차이 강조 기준" */
  threshold: number;
  /** 허용 범위의 상대 기준 R (%, 0~100). null·0 이면 쓰지 않음 (예전 설정은 null). 상단을 따로 쓰면 하단 기준 */
  relative: number | null;
  /** 상단(초과) 기준을 따로 쓸 때만. null 이면 상단·하단 같은 기준 */
  upper: ToleranceRule | null;
  createdAt: number;
  /** 비중 계산에서 뺄 종목 키 */
  excluded: string[];
  /** 계좌에 없는(아직 안 산) 종목을 직접 추가할 때의 이름: 종목 키 → 이름 */
  names: { [key: string]: string };
  /** 매수 단위: 종목 키 → 금액 단위(원). 없거나 0 이면 1주 단위 */
  units: { [key: string]: number };
  /** 직접 입력한 현재가 (계좌에 없는 종목): 종목 키 → 1주 가격 (국내 원, 해외 달러) */
  prices: { [key: string]: number };
  /** 지금 매수 안 함: 비중 표에는 보이지만 매수 계산기에서 빼는 종목 키 (예: 비상장) */
  noBuy: string[];
}

/** 허용 범위 기준: 허용 편차 = min(절대 threshold %p, 목표 × 상대 relative %). 0·null 인 기준은 쓰지 않는다. */
export interface ToleranceRule {
  threshold: number;
  relative: number | null;
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
  /** 해외 종목인지 (계산기에서 달러 금액도 보여줄지) */
  isForeign: boolean;
}

export interface AllocationView {
  target: AllocationTarget;
  /** 목표가 큰 순, 같으면 평가금이 큰 순 */
  rows: AllocationRow[];
  totalKrw: number;
  missingPrice: boolean;
  /** 대상 계좌들의 현재 예수금 (원화, 달러는 환율로). 비중에 넣지 않아도 계산기에서 쓸 수 있다 */
  cashKrw: number;
}

/**
 * 계산기 결과 한 줄: 살(+)/팔(−) 원화 금액과 주 수.
 * shares 는 1주 단위면 정확한 주 수, 금액 단위면 대략 주 수(소수), 가격을 모르면 null. fractional 은 금액 단위인지.
 */
export interface TradeSuggestion {
  row: AllocationRow;
  amountKrw: number;
  shares: number | null;
  fractional: boolean;
}

/** 매수 계산기 결과: 종목별 살 금액, 단위 때문에 남는 돈, 계산에 쓴 돈(넣을 돈 + 쓴 예수금)과 그중 예수금 */
export interface BuyPlan {
  trades: TradeSuggestion[];
  leftoverKrw: number;
  poolKrw: number;
  cashUsedKrw: number;
}

/** 목표 합계 (예수금 미포함이면 예수금 목표는 빼고). */
export function targetSum(t: AllocationTarget): number {
  return Object.entries(t.targets)
    .filter(([k]) => t.includeCash || k !== CASH_KEY)
    .reduce((s, [, v]) => s + v, 0);
}

export const KR_PREFIX = 'KR:';
export const US_PREFIX = 'US:';
/** 종목코드 없이 직접 추가한 종목의 키 앞부분 */
export const MANUAL_KR_PREFIX = 'manual-kr:';
export const MANUAL_US_PREFIX = 'manual-us:';

/** 종목코드 없이 직접 추가하는 종목의 키 (계좌 종목과 자동 연결되지 않는다). */
export function codelessKey(foreign: boolean, id: string): string {
  return (foreign ? MANUAL_US_PREFIX : MANUAL_KR_PREFIX) + id;
}

export function isCodeless(key: string): boolean {
  return key.startsWith(MANUAL_KR_PREFIX) || key.startsWith(MANUAL_US_PREFIX);
}

const isForeignKey = (key: string) => key.startsWith(US_PREFIX) || key.startsWith(MANUAL_US_PREFIX);

/** 직접 추가하는 종목의 키 (keyOf 와 같은 규칙). */
export function manualKey(foreign: boolean, code: string): string {
  return (foreign ? US_PREFIX : KR_PREFIX) + code.trim().toUpperCase();
}

/**
 * 종목 키: 같은 종목이 여러 계좌에 있어도 같은 키. 국내 "KR:코드", 해외 "US:티커" (거래소는 구분하지 않는다 —
 * 직접 추가한 종목과 나중에 계좌에 추가한 종목이 연결되도록). 코드가 없는 펀드·채권은 종목 하나로.
 */
export function keyOf(h: Holding): string {
  return h.code.trim() === '' ? `id:${h.id}` : manualKey(marketCurrency(h.market) === 'USD', h.code);
}

/** 5/25 룰: 절대 5%p, 상대 25%. */
export const DEFAULT_ABSOLUTE = 5;
export const DEFAULT_RELATIVE = 25;

/** 하단(부족) 기준 (상단·하단이 같으면 공통 기준). */
export const lowerRule = (t: Pick<AllocationTarget, 'threshold' | 'relative'>): ToleranceRule => ({ threshold: t.threshold, relative: t.relative });
/** 상단(초과) 기준. */
export const upperRule = (t: Pick<AllocationTarget, 'threshold' | 'relative' | 'upper'>): ToleranceRule => t.upper ?? lowerRule(t);
/** 기준을 하나라도 쓰는지. */
export const ruleIsSet = (r: ToleranceRule): boolean => r.threshold > 0 || (r.relative ?? 0) > 0;

/** 허용 편차 (%p) = min(A, W × R). weightPercent 는 목표 비중 W (%). 쓰는 기준이 없으면 null. */
export function toleranceBand(target: ToleranceRule, weightPercent: number): number | null {
  const bands: number[] = [];
  if (target.threshold > 0) bands.push(target.threshold);
  if (target.relative !== null && target.relative > 0) bands.push((weightPercent * target.relative) / 100);
  return bands.length === 0 ? null : Math.min(...bands);
}

/** 허용 범위 [하한, 상한] %. */
export function toleranceRange(view: AllocationView, row: AllocationRow): [number, number] | null {
  const w = row.targetWeight * 100;
  const low = toleranceBand(lowerRule(view.target), w);
  const high = toleranceBand(upperRule(view.target), w);
  return low === null || high === null ? null : [Math.max(0, w - low), w + high];
}

/** 강조할 줄: 허용 범위를 벗어남 (초과는 상단, 부족은 하단 편차 이상). 목표 0% 는 강조하지 않는다. */
export function isHighlighted(view: AllocationView, row: AllocationRow): boolean {
  if (row.targetWeight <= 0) return false;
  const rule = row.diffPoints > 0 ? upperRule(view.target) : lowerRule(view.target);
  const b = toleranceBand(rule, row.targetWeight * 100);
  return b !== null && Math.abs(row.diffPoints) >= b - 1e-9;
}

/** 기준 하나의 짧은 이름: 5/25, ±3%p, 목표의 ±25%. */
function ruleText(rule: ToleranceRule, short: boolean): string {
  const a = rule.threshold > 0 ? rule.threshold : null;
  const r = rule.relative !== null && rule.relative > 0 ? rule.relative : null;
  if (a !== null && r !== null) return short ? `${trim(a)}/${trim(r)}` : `절대 ${trim(a)}%p · 상대 ${trim(r)}%`;
  if (a !== null) return `±${trim(a)}%p`;
  if (r !== null) return `목표의 ±${trim(r)}%`;
  return '안 씀';
}

/** 사용 중인 허용 기준 이름 (예: "5/25 룰", "±3%p", "상단 10/50 · 하단 5/25"). */
export function toleranceLabel(target: Pick<AllocationTarget, 'threshold' | 'relative' | 'upper'>): string {
  const low = lowerRule(target);
  if (target.upper) return `상단 ${ruleText(target.upper, true)} · 하단 ${ruleText(low, true)}`;
  if (low.threshold === DEFAULT_ABSOLUTE && low.relative === DEFAULT_RELATIVE) return '5/25 룰';
  return ruleIsSet(low) ? ruleText(low, false) : '강조 안 함';
}

const trim = (n: number): string => String(Number(n.toFixed(2)));

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
      // 현재가를 직접 넣었으면 주 수 계산에 쓴다 (해외는 달러 → 원)
      const local = (target.prices[k] ?? 0) > 0 ? target.prices[k] : null;
      const krw = local === null ? null : isForeignKey(k) ? (usdKrw === null ? null : local * usdKrw) : local;
      const fallback = isCodeless(k) ? '직접 추가 종목' : k.includes(':') ? k.slice(k.indexOf(':') + 1) : k;
      raws.push({ key: k, name: target.names[k]?.trim() || fallback, holding: null, qty: 0, value: 0, priceKrw: krw, priceLocal: local });
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
        isForeign: (r.holding !== null && marketCurrency(r.holding.market) === 'USD') || isForeignKey(r.key),
      };
    })
    .sort((a, b) => b.targetWeight - a.targetWeight || b.valueKrw - a.valueKrw);
  return { target, rows, totalKrw: total, missingPrice, cashKrw: included.reduce((s, a) => s + cashToKrw(a.cash, usdKrw), 0) };
}

/** 종목의 매수 단위(원): 금액 단위가 있으면 그 금액, 없으면 1주 가격, 가격을 모르면 null (내림하지 않음). */
function unitOf(target: AllocationTarget, row: AllocationRow): number | null {
  if (row.isCash) return null;
  const money = target.units[row.key] ?? 0;
  if (money > 0) return money;
  return row.priceKrw !== null && row.priceKrw > 0 ? row.priceKrw : null;
}

function suggestion(target: AllocationTarget, row: AllocationRow, amountKrw: number): TradeSuggestion {
  const fractional = !row.isCash && (target.units[row.key] ?? 0) > 0;
  const price = row.priceKrw !== null && row.priceKrw > 0 && !row.isCash ? row.priceKrw : null;
  const exact = price === null ? null : Math.abs(amountKrw) / price;
  return { row, amountKrw, shares: exact === null ? null : fractional ? exact : Math.round(exact), fractional };
}

/**
 * 팔지 않고 amountKrw 만 더 사서 목표에 가장 가깝게: 넣은 뒤 합계 × 목표 − 지금 평가금 = 부족분.
 * 부족분 합이 넣을 돈보다 크면 부족분 비율로 나누고, 남으면 목표 비율로 더 나눈다. 목표가 없는 종목은 사지 않는다.
 * 그다음 종목별 매수 단위로 내림하고, 남은 돈으로 목표보다 가장 많이 부족한 종목부터 한 단위씩 더 산다
 * (목표와의 차이 합이 줄어들 때만 — 예수금 목표가 있으면 예수금 차이도 함께).
 */
export function buyOnly(view: AllocationView, inputKrw: number, useCash = false): BuyPlan {
  // 현재 예수금도 쓰면: 계산에 쓰는 돈 = 넣을 돈 + 예수금, 예수금 줄의 지금 값은 0 으로 (모두 매수에 쓸 돈으로 옮김)
  const cashUsed = useCash ? Math.max(0, view.cashKrw) : 0;
  const amountKrw = Math.max(0, inputKrw) + cashUsed;
  const cashInTotal = view.rows.some((r) => r.isCash);
  const valueOf = (r: AllocationRow) => (r.isCash && useCash ? 0 : r.valueKrw);
  if (amountKrw <= 0) return { trades: [], leftoverKrw: 0, poolKrw: 0, cashUsedKrw: 0 };
  // 목표 합계는 "지금 매수 안 함" 종목까지 포함한 원래 목표 기준 (나머지 종목의 목표를 부풀리지 않는다)
  const sum = view.rows.reduce((s, r) => s + r.targetWeight, 0);
  const noBuy = new Set(view.target.noBuy);
  const rows = view.rows.filter((r) => r.targetWeight > 0 && !noBuy.has(r.key));
  const buySum = rows.reduce((s, r) => s + r.targetWeight, 0);
  if (rows.length === 0 || sum <= 0 || buySum <= 0) return { trades: [], leftoverKrw: amountKrw, poolKrw: amountKrw, cashUsedKrw: cashUsed };
  // 예수금이 이미 합계에 들어 있으면(비중에 포함) 옮겨도 합계는 그대로, 아니면 쓰는 예수금만큼 늘어난다
  const newTotal = view.totalKrw + Math.max(0, inputKrw) + (cashInTotal ? 0 : cashUsed);
  const deficits = rows.map((r) => Math.max(0, (r.targetWeight / sum) * newTotal - valueOf(r)));
  const deficitSum = deficits.reduce((s, d) => s + d, 0);
  const ideal = new Map<AllocationRow, number>(
    rows.map((r, i) => [
      r,
      // 부족분을 다 채우고 남는 돈은 살 수 있는 종목의 목표 비율로 (빠진 종목 몫을 현금으로 남기지 않는다)
      deficitSum >= amountKrw && deficitSum > 0 ? (deficits[i] / deficitSum) * amountKrw : deficits[i] + ((amountKrw - deficitSum) * r.targetWeight) / buySum,
    ]),
  );
  // 매수 단위로 내림 (단위를 모르면 그대로). 예수금은 "사고 남은 돈"이라 마지막에 남은 돈으로 정한다.
  const stocks = rows.filter((r) => !r.isCash);
  const units = new Map(stocks.map((r) => [r, unitOf(view.target, r)]));
  const buys = new Map(
    stocks.map((r) => {
      const u = units.get(r) ?? null;
      const raw = ideal.get(r)!;
      return [r, u === null ? raw : Math.floor(raw / u + 1e-9) * u];
    }),
  );
  let leftover = amountKrw - [...buys.values()].reduce((s, b) => s + b, 0);
  const cashRow = rows.find((r) => r.isCash) ?? null;
  const goal = (r: AllocationRow) => Math.max(1e-9, (r.targetWeight / sum) * newTotal);
  const need = (r: AllocationRow, bought: number) => goal(r) - (valueOf(r) + bought);
  for (let guard = 0; guard < 100_000; guard++) {
    const cashNeed = cashRow ? need(cashRow, leftover) : 0;
    let pick: AllocationRow | null = null;
    let best = -Infinity;
    for (const r of stocks) {
      const u = units.get(r) ?? null;
      if (u === null || u > leftover + 1e-9) continue;
      const d = need(r, buys.get(r)!);
      let ok: boolean;
      if (cashRow === null) {
        // 예수금 목표가 없으면(0%) 남기는 돈도 목표에서 벗어나므로 살 수 있으면 산다
        ok = true;
      } else {
        const change = Math.abs(d - u) - Math.abs(d) + Math.abs(cashNeed + u) - Math.abs(cashNeed);
        // 차이 합이 줄면 산다. 같으면 단위 때문에 원래 몫보다 반 단위 이상 덜 산 종목이고 목표 대비 예수금보다 더 부족할 때만
        const tieBuy = Math.abs(change) <= 1e-9 && ideal.get(r)! - buys.get(r)! >= u / 2 - 1e-9 && d / goal(r) > cashNeed / goal(cashRow);
        ok = change < -1e-9 || tieBuy;
      }
      if (ok && d > best) {
        best = d;
        pick = r;
      }
    }
    if (pick === null) break;
    const u = units.get(pick)!;
    buys.set(pick, buys.get(pick)! + u);
    leftover -= u;
  }
  leftover = Math.max(0, leftover);
  const trades = stocks.map((r) => suggestion(view.target, r, buys.get(r)!));
  // 예수금 목표가 있으면 남은 돈은 예수금으로 (그 경우 "남는 돈"은 0)
  if (cashRow) {
    trades.push(suggestion(view.target, cashRow, leftover));
    leftover = 0;
  }
  return { trades: trades.filter((t) => t.amountKrw >= 0.5), leftoverKrw: leftover, poolKrw: amountKrw, cashUsedKrw: cashUsed };
}

/** 사고팔아 정확히 맞추기: 종목별 목표 × 합계 − 평가금 (+ 사기, − 팔기), 매수 단위로 0 쪽 내림. */
export function rebalance(view: AllocationView, tax: TaxLimit | null = null): RebalancePlan {
  const sum = view.rows.reduce((s, r) => s + r.targetWeight, 0);
  if (sum <= 0) return { trades: [], tax: null };
  const noBuy = new Set(view.target.noBuy);
  const amounts = new Map<AllocationRow, number>();
  for (const r of view.rows) {
    if (!noBuy.has(r.key)) amounts.set(r, floorUnit(view.target, r, (r.targetWeight / sum) * view.totalKrw - r.valueKrw));
  }
  const result = tax ? limitOverseasGain(view, amounts, tax) : null;
  const trades = [...amounts].map(([r, a]) => suggestion(view.target, r, a)).filter((s) => Math.abs(s.amountKrw) >= 0.5);
  return { trades, tax: result };
}

/** 매수 단위로 0 쪽 내림 (단위를 모르면 그대로). */
function floorUnit(target: AllocationTarget, row: AllocationRow, raw: number): number {
  const u = unitOf(target, row);
  return u === null ? raw : Math.sign(raw) * Math.floor(Math.abs(raw) / u + 1e-9) * u;
}

/** 해외 매도 한 건의 실현이익 비율 (매도 금액 1원당). 원화 평균단가를 모르면 매도 금액 전체를 이익으로 본다 (보수적으로). */
function gainRatio(row: AllocationRow, tax: TaxLimit): number {
  const price = row.priceKrw !== null && row.priceKrw > 0 ? row.priceKrw : null;
  const average = tax.averageCostKrw.get(row.key);
  return price === null || average === undefined ? 1 : 1 - average / price;
}

const isForeignSell = (r: AllocationRow, a: number) => a < 0 && r.isForeign && !r.isCash;

/**
 * 해외 매도의 실현이익 합이 남은 한도(한도 − 올해 실현손익, 손실 매도만큼 늘어남)를 넘으면,
 * 이익 나는 해외 매도를 모두 같은 비율로 줄이고(매수 단위로 내림), 덜 판 만큼 매수(+ 금액)도 같은 비율로 줄인다.
 */
function limitOverseasGain(view: AllocationView, amounts: Map<AllocationRow, number>, tax: TaxLimit): TaxResult {
  const gains = [...amounts].filter(([r, a]) => isForeignSell(r, a)).map(([r, a]) => [r, -a * gainRatio(r, tax)] as const);
  const losses = gains.filter(([, g]) => g < 0).reduce((s, [, g]) => s + g, 0);
  const profit = gains.filter(([, g]) => g > 0).reduce((s, [, g]) => s + g, 0);
  const available = tax.limitKrw - tax.realizedKrw - losses;
  const reduced: [AllocationRow, number][] = [];
  if (profit > available + 1e-6) {
    const ratio = Math.min(1, Math.max(0, available / profit));
    for (const [r, g] of gains) {
      if (g <= 0) continue;
      const old = amounts.get(r)!;
      const next = floorUnit(view.target, r, old * ratio);
      amounts.set(r, next);
      if (next - old > 0.5) reduced.push([r, next - old]);
    }
    const cut = reduced.reduce((s, [, c]) => s + c, 0);
    const buys = [...amounts].filter(([, a]) => a > 0);
    const buyTotal = buys.reduce((s, [, a]) => s + a, 0);
    if (buyTotal > 0 && cut > 0) {
      const scale = Math.min(1, Math.max(0, (buyTotal - cut) / buyTotal));
      for (const [r, a] of buys) amounts.set(r, floorUnit(view.target, r, a * scale));
    }
  }
  const planned = [...amounts].filter(([r, a]) => isForeignSell(r, a)).reduce((s, [r, a]) => s + -a * gainRatio(r, tax), 0);
  return { realizedKrw: tax.realizedKrw, plannedGainKrw: planned, limitKrw: tax.limitKrw, reduced, estimated: tax.estimated };
}

/** 사고팔기 계산의 해외 양도세 한도 (core TaxLimit). averageCostKrw: 종목 키 → 원화 평균단가(1주). */
export interface TaxLimit {
  realizedKrw: number;
  averageCostKrw: ReadonlyMap<string, number>;
  limitKrw: number;
  estimated: boolean;
}

/** 한도 적용 결과: 올해 실현손익, 이번 매도로 생기는 이익(손실은 −), 한도, 줄인 매도 [종목, 줄인 금액]. */
export interface TaxResult {
  realizedKrw: number;
  plannedGainKrw: number;
  limitKrw: number;
  reduced: [AllocationRow, number][];
  estimated: boolean;
}

/** 사고팔기 결과. tax 는 양도세 한도를 적용했을 때만. */
export interface RebalancePlan {
  trades: TradeSuggestion[];
  tax: TaxResult | null;
}
