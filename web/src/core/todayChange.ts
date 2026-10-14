// core 의 TodayChangeCalculator 와 같은 규칙: 오늘 변동 (국내 · 해외 · 환율), 종목별 타일.
import { marketCurrency, type Holding, type InvestmentAccount, type MarketSnapshot } from '../model';
import { dailyChangeRate, keyOf, latestUsdKrw, usdKrwAt } from './market';
import { plusDays } from './dates';
import type { PortfolioSummary } from './portfolio';

/** 오늘 변동의 종목 하나 (같은 시세 키는 계좌가 달라도 합침). rate·changeKrw 가 null 이면 시세 없음. changeKrw 는 가격 변동만. */
export interface TodayItem {
  key: string;
  holding: Holding;
  valueKrw: number;
  changeKrw: number | null;
  rate: number | null;
}

/** 오늘 변동을 거르는 종류 (칩). 종목은 티커 색 구분과 같고, 달러 예수금은 환율 효과에만 들어간다. */
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

/** 오늘 변동 필터: 뺀 계좌·종류를 기억하므로 기본은 전부 (= 전체), 새로 생긴 계좌·종류도 보인다. */
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

/** 계좌 한 줄: 그 계좌 종목의 가격 변동 (환율 효과는 따로). changeKrw 가 null 이면 종목은 있지만 시세가 하나도 없음. */
export interface TodayAccount {
  account: InvestmentAccount;
  changeKrw: number | null;
}

export interface TodayChange {
  totalKrw: number;
  /** 오늘 변동 ÷ 전일 기준 평가금 (대상 종목 + 달러 예수금). 기준이 없으면 null. */
  rate: number | null;
  domesticKrw: number;
  foreignKrw: number;
  fxKrw: number;
  /** 환율 효과에 쓴 전 영업일·오늘 환율 (오늘 환율 발표 전이면 null). */
  fxFrom: number | null;
  fxTo: number | null;
  /** 종목별 (평가금 큰 순). */
  items: TodayItem[];
  /** 계좌별 (계좌 순서). 종목이 있는 계좌만 (예수금만 있는 계좌는 없음). 계좌 합 + fxKrw = totalKrw. */
  accounts: TodayAccount[];
}

export interface TileRect {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** 타일이 이보다 많으면 작은 것부터 "기타"로 묶는다. */
export const MAX_TILES = 16;

/** 타일·목록에 쓰는 이름: 금현물, 종목명, 이름이 없으면 종목코드. */
export const todayLabel = (item: TodayItem): string =>
  item.holding.assetType === 'GOLD' ? '금현물' : item.holding.name.trim() !== '' ? item.holding.name.trim() : item.holding.code.toUpperCase();

export const includedItems = (c: TodayChange) => c.items.filter((i) => i.rate !== null);
export const excludedItems = (c: TodayChange) => c.items.filter((i) => i.rate === null);
/** 많이 오른 종목 (등락률 큰 순, 상승만). */
export const topGainers = (c: TodayChange, count = 3) => includedItems(c).filter((i) => i.rate! > 0).sort((a, b) => b.rate! - a.rate!).slice(0, count);
/** 많이 내린 종목 (등락률 작은 순, 하락만). */
export const topLosers = (c: TodayChange, count = 3) => includedItems(c).filter((i) => i.rate! < 0).sort((a, b) => a.rate! - b.rate!).slice(0, count);

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
 * 오늘 변동. today 는 오늘 날짜 (오늘 환율이 오늘 것인지 판단). filter 로 뺀 계좌·종류는 계산하지 않는다.
 * 고른 범위에 종목도 달러 예수금도 없으면 null.
 */
export function todayChange(summary: PortfolioSummary, snapshot: MarketSnapshot, today: string, filter: TodayFilter = NO_FILTER): TodayChange | null {
  const fxTo = summary.usdKrw;
  // 오늘 발표된 환율일 때만 환율 효과 (전 영업일 환율과 비교)
  const latest = latestUsdKrw(snapshot);
  const fxFrom = latest !== null && latest[0] === today && fxTo !== null ? usdKrwAt(snapshot, plusDays(today, -1)) : null;
  const fxMove = fxFrom !== null && fxTo !== null ? fxTo - fxFrom : 0;
  const fxBase = fxFrom ?? fxTo ?? 0;

  let domestic = 0;
  let foreign = 0;
  let fx = 0;
  let previous = 0;
  const merged = new Map<string, TodayItem>();
  const accounts: TodayAccount[] = [];
  for (const account of summary.accounts) {
    if (filter.hiddenAccounts.has(account.account.id)) continue;
    if (!filter.hiddenKinds.has('USD_CASH')) {
      fx += account.cash.usd * fxMove;
      previous += account.cash.usd * fxBase;
    }
    let accountChange: number | null = null;
    let held = false;
    for (const v of account.holdings) {
      const quantity = v.position.quantity;
      if (quantity <= 1e-9) continue;
      const h = v.holding;
      if (filter.hiddenKinds.has(todayKindOf(h))) continue;
      held = true;
      const key = keyOf(h) ?? `id:${h.id}`;
      const value = v.marketValueKrw ?? 0;
      const rate = dailyChangeRate(h, snapshot);
      const price = v.price?.price ?? null;
      let change: number | null = null;
      if (rate !== null && price !== null) {
        const prev = price / (1 + rate);
        if (marketCurrency(h.market) === 'USD') {
          change = quantity * (price - prev) * (fxTo ?? 0);
          foreign += change;
          fx += quantity * prev * fxMove;
          previous += quantity * prev * fxBase;
        } else {
          change = quantity * (price - prev);
          domestic += change;
          previous += quantity * prev;
        }
        accountChange = (accountChange ?? 0) + change;
      }
      const old = merged.get(key);
      merged.set(key, old === undefined
        ? { key, holding: h, valueKrw: value, changeKrw: change, rate: change !== null ? rate : null }
        : { ...old, valueKrw: old.valueKrw + value, changeKrw: old.changeKrw === null || change === null ? old.changeKrw ?? change : old.changeKrw + change });
    }
    if (held) accounts.push({ account: account.account, changeKrw: accountChange });
  }
  if (merged.size === 0 && previous <= 0) return null;
  const total = domestic + foreign + fx;
  return {
    totalKrw: total,
    rate: previous > 0 ? total / previous : null,
    domesticKrw: domestic,
    foreignKrw: foreign,
    fxKrw: fx,
    fxFrom,
    fxTo: fxFrom !== null ? fxTo : null,
    items: [...merged.values()].sort((a, b) => b.valueKrw - a.valueKrw),
    accounts,
  };
}

/** 등락 색 단계: 0 = 보합(0.1% 미만), ±1 = 1% 미만, ±2 = 2% 미만, ±3 = 2% 이상. 시세 없음은 null. */
export function changeLevel(rate: number | null): number | null {
  if (rate === null) return null;
  const a = Math.abs(rate);
  const step = a < 0.001 ? 0 : a < 0.01 ? 1 : a < 0.02 ? 2 : 3;
  return rate < 0 ? -step : step;
}

/** 타일로 그릴 항목: 평가금이 있는 것만, MAX_TILES 를 넘으면 작은 것들을 하나로 (item null = 기타). */
export function todayTiles(items: readonly TodayItem[]): { item: TodayItem | null; weight: number }[] {
  const sized = items.filter((i) => i.valueKrw > 0).sort((a, b) => b.valueKrw - a.valueKrw);
  if (sized.length <= MAX_TILES) return sized.map((item) => ({ item, weight: item.valueKrw }));
  const kept = sized.slice(0, MAX_TILES - 1);
  const rest = sized.slice(MAX_TILES - 1).reduce((s, i) => s + i.valueKrw, 0);
  return [...kept.map((item) => ({ item, weight: item.valueKrw })), { item: null, weight: rest }];
}

/** Squarified treemap: weights 를 width × height 안에 넓이 비례로 나눈다. 같은 순서로 돌려준다. */
export function treemap(weights: readonly number[], width: number, height: number): TileRect[] {
  const total = weights.reduce((s, w) => s + w, 0);
  if (weights.length === 0 || total <= 0 || width <= 0 || height <= 0) return weights.map(() => ({ x: 0, y: 0, w: 0, h: 0 }));
  const areas = weights.map((w) => (w / total) * width * height);
  const out: TileRect[] = weights.map(() => ({ x: 0, y: 0, w: 0, h: 0 }));
  let x = 0;
  let y = 0;
  let w = width;
  let h = height;
  let i = 0;
  const worst = (row: number[], side: number) => {
    const s = row.reduce((a, b) => a + b, 0);
    return Math.max((side * side * Math.max(...row)) / (s * s), (s * s) / (side * side * Math.min(...row)));
  };
  while (i < areas.length) {
    const side = Math.min(w, h);
    const row = [areas[i]];
    let j = i + 1;
    while (j < areas.length && worst([...row, areas[j]], side) <= worst(row, side)) {
      row.push(areas[j]);
      j++;
    }
    const s = row.reduce((a, b) => a + b, 0);
    if (w >= h) {
      // 왼쪽에 세로 줄
      const stripW = h > 0 ? s / h : 0;
      let yy = y;
      row.forEach((a, k) => {
        const hh = stripW > 0 ? a / stripW : 0;
        out[i + k] = { x, y: yy, w: stripW, h: hh };
        yy += hh;
      });
      x += stripW;
      w -= stripW;
    } else {
      // 위쪽에 가로 줄
      const stripH = w > 0 ? s / w : 0;
      let xx = x;
      row.forEach((a, k) => {
        const ww = stripH > 0 ? a / stripH : 0;
        out[i + k] = { x: xx, y, w: ww, h: stripH };
        xx += ww;
      });
      y += stripH;
      h -= stripH;
    }
    i = j;
  }
  return out;
}
