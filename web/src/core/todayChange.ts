// core 의 TodayChangeCalculator 와 같은 규칙 (v2.10): 시장별 변동 — 국내 · 미국 · 환율마다 지금(또는 마지막) 일봉 하나.
import { marketCurrency, type Holding, type MarketSnapshot } from '../model';
import { candleRate, keyOf, latestUsdKrw, usdKrwAt, usesLatest } from './market';
import { EMPTY_CALENDAR, isActivePhase, marketGroupOf, marketStates, prevKrOpen, type MarketGroup, type MarketState } from './marketClock';
import type { PortfolioSummary } from './portfolio';

/**
 * 시장별 변동의 종목 하나 (같은 시세 키는 계좌가 달라도 합침). changeKrw·rate 가 null 이면 일봉 없음 (직접 입력 가격·아직 못 받음).
 * changeKrw 는 가격 변동만 (해외는 지금 환율로 원화, 환율 효과는 따로). live 면 그 시장이 진행 중이고 받은 일봉이 지금 일봉.
 */
export interface TodayItem {
  key: string;
  holding: Holding;
  valueKrw: number;
  changeKrw: number | null;
  rate: number | null;
  group: MarketGroup;
  live: boolean;
}

/** 필터 칩의 종류. 종목은 티커 색 구분과 같고, 달러 예수금은 환율 효과에만 들어간다. */
export type TodayKind = 'KR_STOCK' | 'US_STOCK' | 'ETF' | 'GOLD' | 'OTHER' | 'USD_CASH';
export const TODAY_KINDS: readonly TodayKind[] = ['KR_STOCK', 'US_STOCK', 'ETF', 'GOLD', 'OTHER', 'USD_CASH'];
export const TODAY_KIND_LABEL: { [K in TodayKind]: string } = {
  KR_STOCK: '국내 주식', US_STOCK: '해외 주식', ETF: 'ETF', GOLD: '금현물', OTHER: '펀드·채권', USD_CASH: '달러 예수금',
};

export function todayKindOf(h: Holding): TodayKind {
  if (h.assetType === 'ETF') return 'ETF';
  if (h.assetType === 'STOCK') return h.market === 'KR' ? 'KR_STOCK' : 'US_STOCK';
  if (h.assetType === 'GOLD') return 'GOLD';
  return 'OTHER';
}

/** 필터: 뺀 계좌·종류를 기억하므로 기본은 전부 (= 전체), 새로 생긴 계좌·종류도 보인다. */
export interface TodayFilter {
  hiddenAccounts: ReadonlySet<string>;
  hiddenKinds: ReadonlySet<TodayKind>;
}
export const NO_FILTER: TodayFilter = { hiddenAccounts: new Set(), hiddenKinds: new Set() };
export const isNoFilter = (f: TodayFilter) => f.hiddenAccounts.size === 0 && f.hiddenKinds.size === 0;

/** 칩에 있는 것이 모두 보이는지 ([전체] 칩이 켜진 상태). */
export const allChipsShown = <T,>(present: readonly T[], hidden: ReadonlySet<T>): boolean => present.every((x) => !hidden.has(x));
/** [전체] 칩: 모두 보이면 전체 해제, 아니면 전체 선택. */
export function toggleAllChips<T>(present: readonly T[], hidden: ReadonlySet<T>): Set<T> {
  const next = new Set(hidden);
  const showAll = !allChipsShown(present, hidden);
  for (const x of present) {
    if (showAll) next.delete(x);
    else next.add(x);
  }
  return next;
}
/** 칩 하나 켜기·끄기. */
export function toggleChip<T>(item: T, hidden: ReadonlySet<T>): Set<T> {
  const next = new Set(hidden);
  if (next.has(item)) next.delete(item);
  else next.add(item);
  return next;
}

/** 칩으로 보여줄 종류: 보유 종목에 있는 종류 + 달러 예수금이 있으면 달러 예수금, 정해진 순서로. */
export function presentKinds(summary: PortfolioSummary): TodayKind[] {
  const kinds = new Set<TodayKind>();
  for (const a of summary.accounts) {
    for (const v of a.holdings) if (v.position.quantity > 1e-9) kinds.add(todayKindOf(v.holding));
    if (Math.abs(a.cash.usd) > 1e-9) kinds.add('USD_CASH');
  }
  return TODAY_KINDS.filter((k) => kinds.has(k));
}


/**
 * 한 시장의 일봉 변동 (core MarketMove). changeKrw 가 null 이면 종목은 있지만 받은 일봉이 하나도 없음.
 * live 면 시장이 진행 중이고 받은 일봉이 지금 일봉 (아니면 화면에서 회색). candleDate 는 받은 일봉 날짜 (종목 중 가장 최근).
 * 국내는 regularKrw (정규장) + extendedKrw (장외) = changeKrw. 환율 줄은 fxFrom → fxTo.
 */
export interface MarketMove {
  state: MarketState;
  changeKrw: number | null;
  rate: number | null;
  live: boolean;
  candleDate: string | null;
  items: TodayItem[];
  regularKrw: number | null;
  extendedKrw: number | null;
  fxFrom: number | null;
  fxTo: number | null;
}

/** 시장은 진행 중인데 받은 일봉이 아직 예전 것 (폰이 아직 못 받음). */
export const isStale = (m: MarketMove): boolean => isActivePhase(m.state.phase) && !m.live;

/** 시장별 변동: 합계 = 각 시장 최근 일봉 변동의 합. */
export interface TodayChange {
  totalKrw: number;
  /** 합계 ÷ 기준 평가금 (대상 종목 기준가 + 달러 예수금). 기준이 없으면 null. */
  rate: number | null;
  /** 국내 · 미국 · 환율 (있는 것만, 이 순서). */
  markets: MarketMove[];
  /** 종목별 (평가금 큰 순). */
  items: TodayItem[];
}

/** 지금 움직이는 시장이 하나라도 있는지 (없으면 합계도 회색). */
export const isLiveChange = (c: TodayChange): boolean => c.markets.some((m) => m.live);
export const marketOf = (c: TodayChange, group: MarketGroup): MarketMove | undefined => c.markets.find((m) => m.state.group === group);

/** 목록에 쓰는 이름: 금현물, 종목명, 이름이 없으면 종목코드. */
export const todayLabel = (item: TodayItem): string =>
  item.holding.assetType === 'GOLD' ? '금현물' : item.holding.name.trim() !== '' ? item.holding.name.trim() : item.holding.code.toUpperCase();

interface Acc {
  change: number;
  regular: number;
  /** 시장 등락률의 분모 (해외는 지금 환율로 원화). */
  base: number;
  /** 합계 비율의 분모 (해외는 직전 환율로 원화 = 기준 평가금). */
  prev: number;
  any: boolean;
  candleDate: string | null;
  items: Map<string, TodayItem>;
}
const newAcc = (): Acc => ({ change: 0, regular: 0, base: 0, prev: 0, any: false, candleDate: null, items: new Map() });

/**
 * 시장별 변동. nowMs 로 시장 상태를 정하고, filter 로 뺀 계좌·종류는 계산하지 않는다.
 * 고른 범위에 종목도 달러 예수금도 없으면 null.
 */
export function todayChange(summary: PortfolioSummary, snapshot: MarketSnapshot, nowMs: number, filter: TodayFilter = NO_FILTER): TodayChange | null {
  const calendar = snapshot.calendar ?? EMPTY_CALENDAR;
  const states = marketStates(nowMs, calendar);
  const fxState = states.FX;
  const fxNow = summary.usdKrw;

  // 환율 일봉: 그날 환율 (오늘 받은 값이 그날이면 그것) ← 직전 개장일 환율
  const latestFx = latestUsdKrw(snapshot);
  const stored = snapshot.usdKrw.get(fxState.candleDate);
  const fxTo = latestFx !== null && latestFx[0] === fxState.candleDate ? latestFx[1] : stored !== undefined && stored > 0 ? stored : null;
  const fxFrom = fxTo !== null ? usdKrwAt(snapshot, prevKrOpen(fxState.candleDate, calendar)) : null;
  const fxMove = fxFrom !== null && fxTo !== null ? fxTo - fxFrom : 0;
  const fxBase = fxFrom ?? fxNow ?? 0;

  const kr = newAcc();
  const us = newAcc();
  let usdCash = 0;
  let usdHoldings = 0;
  let anyUsdCash = false;
  for (const account of summary.accounts) {
    if (filter.hiddenAccounts.has(account.account.id)) continue;
    if (!filter.hiddenKinds.has('USD_CASH') && Math.abs(account.cash.usd) > 1e-9) {
      usdCash += account.cash.usd;
      anyUsdCash = true;
    }
    for (const v of account.holdings) {
      const quantity = v.position.quantity;
      if (quantity <= 1e-9) continue;
      const h = v.holding;
      if (filter.hiddenKinds.has(todayKindOf(h))) continue;
      const group = marketGroupOf(h);
      const acc = group === 'KR' ? kr : us;
      acc.any = true;
      const key = keyOf(h) ?? `id:${h.id}`;
      const usd = marketCurrency(h.market) === 'USD';
      // 직접 입력 가격을 쓰는 종목(펀드 등)은 빼고, 일봉이 있는 자동 시세 종목만
      const raw = snapshot.prices.get(key)?.candle ?? null;
      const candle = raw !== null && raw.base > 0 && usesLatest(h, snapshot) ? raw : null;
      let change: number | null = null;
      if (candle !== null) {
        const fx = usd ? fxNow ?? 0 : 1;
        change = quantity * (candle.last - candle.base) * fx;
        acc.change += change;
        acc.regular += quantity * ((candle.regular ?? candle.last) - candle.base) * fx;
        acc.base += quantity * candle.base * fx;
        acc.prev += quantity * candle.base * (usd ? fxBase : 1);
        if (usd) usdHoldings += quantity * candle.base;
        acc.candleDate = acc.candleDate === null || candle.date > acc.candleDate ? candle.date : acc.candleDate;
      }
      const state = states[group];
      const live = candle !== null && isActivePhase(state.phase) && candle.date === state.candleDate;
      const old = acc.items.get(key);
      acc.items.set(key, old === undefined
        ? { key, holding: h, valueKrw: v.marketValueKrw ?? 0, changeKrw: change, rate: candle !== null ? candleRate(candle) : null, group, live }
        : { ...old, valueKrw: old.valueKrw + (v.marketValueKrw ?? 0), changeKrw: old.changeKrw === null || change === null ? old.changeKrw ?? change : old.changeKrw + change });
    }
  }
  if (!kr.any && !us.any && !anyUsdCash) return null;

  const byValue = (a: TodayItem, b: TodayItem) => b.valueKrw - a.valueKrw;
  const move = (group: MarketGroup, acc: Acc, split: boolean): MarketMove => {
    const state = states[group];
    const has = acc.candleDate !== null;
    return {
      state,
      changeKrw: has ? acc.change : null,
      rate: has && acc.base > 0 ? acc.change / acc.base : null,
      live: has && isActivePhase(state.phase) && acc.candleDate === state.candleDate,
      candleDate: acc.candleDate,
      items: [...acc.items.values()].sort(byValue),
      regularKrw: split && has ? acc.regular : null,
      extendedKrw: split && has ? acc.change - acc.regular : null,
      fxFrom: null,
      fxTo: null,
    };
  };
  const markets: MarketMove[] = [];
  if (kr.any) markets.push(move('KR', kr, true));
  if (us.any) markets.push(move('US', us, false));
  if (us.any || anyUsdCash) {
    // 환율 효과 = (해외 종목 수량 × 기준가 + 달러 예수금) × 환율 변동
    markets.push({
      state: fxState,
      changeKrw: fxTo !== null ? (usdHoldings + usdCash) * fxMove : null,
      rate: fxFrom !== null && fxTo !== null && fxFrom > 0 ? fxTo / fxFrom - 1 : null,
      live: fxTo !== null && isActivePhase(fxState.phase),
      candleDate: fxTo !== null ? fxState.candleDate : null,
      items: [],
      regularKrw: null,
      extendedKrw: null,
      fxFrom,
      fxTo,
    });
  }
  const total = markets.reduce((sum, m) => sum + (m.changeKrw ?? 0), 0);
  const baseValue = kr.prev + us.prev + usdCash * fxBase;
  return {
    totalKrw: total,
    rate: baseValue > 0 ? total / baseValue : null,
    markets,
    items: [...kr.items.values(), ...us.items.values()].sort(byValue),
  };
}
