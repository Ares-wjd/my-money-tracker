// core 의 TodayChangeCalculator 와 같은 규칙: 오늘 변동 (국내 · 해외 · 환율), 종목별 타일.
import { marketCurrency, type Holding, type MarketSnapshot } from '../model';
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
}

export interface TileRect {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** 타일이 이보다 많으면 작은 것부터 "기타"로 묶는다. */
export const MAX_TILES = 16;

/** 타일·배지에 쓰는 짧은 이름: 금현물, 종목코드, 코드가 없으면 종목명. */
export const todayLabel = (item: TodayItem): string =>
  item.holding.assetType === 'GOLD' ? '금현물' : item.holding.code.trim() !== '' ? item.holding.code.toUpperCase() : item.holding.name;

export const includedItems = (c: TodayChange) => c.items.filter((i) => i.rate !== null);
export const excludedItems = (c: TodayChange) => c.items.filter((i) => i.rate === null);
/** 많이 오른 종목 (등락률 큰 순, 상승만). */
export const topGainers = (c: TodayChange, count = 3) => includedItems(c).filter((i) => i.rate! > 0).sort((a, b) => b.rate! - a.rate!).slice(0, count);
/** 많이 내린 종목 (등락률 작은 순, 하락만). */
export const topLosers = (c: TodayChange, count = 3) => includedItems(c).filter((i) => i.rate! < 0).sort((a, b) => a.rate! - b.rate!).slice(0, count);

/** 오늘 변동. today 는 오늘 날짜 (오늘 환율이 오늘 것인지 판단). 보유 종목이 없으면 null. */
export function todayChange(summary: PortfolioSummary, snapshot: MarketSnapshot, today: string): TodayChange | null {
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
  for (const account of summary.accounts) {
    fx += account.cash.usd * fxMove;
    previous += account.cash.usd * fxBase;
    for (const v of account.holdings) {
      const quantity = v.position.quantity;
      if (quantity <= 1e-9) continue;
      const h = v.holding;
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
      }
      const old = merged.get(key);
      merged.set(key, old === undefined
        ? { key, holding: h, valueKrw: value, changeKrw: change, rate: change !== null ? rate : null }
        : { ...old, valueKrw: old.valueKrw + value, changeKrw: old.changeKrw === null || change === null ? old.changeKrw ?? change : old.changeKrw + change });
    }
  }
  if (merged.size === 0) return null;
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
