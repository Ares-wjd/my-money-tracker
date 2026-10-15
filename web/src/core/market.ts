// core 의 MarketKeys · PriceLookup · MarketSnapshot 과 같은 규칙.
import type { Holding, Market, MarketSnapshot, PriceHistory, PricePoint } from '../model';
import { plusDays } from './dates';

const MIN_DATE = '0000-00-00';

/** 자동 시세를 받을 수 있는 종목인지 (펀드·채권은 직접 입력, 금현물은 국내 시세 조회). */
export function quotable(holding: Holding): boolean {
  return holding.code.trim() !== '' && (holding.assetType === 'STOCK' || holding.assetType === 'ETF' || holding.assetType === 'GOLD');
}

export function marketKey(market: Market, code: string): string {
  return (market === 'KR' ? 'KR' : 'US') + '_' + code.toUpperCase().replace(/\//g, '_');
}

/** 시세 저장 키. 같은 종목이면 계좌가 달라도 같은 시세를 쓴다. */
export function keyOf(holding: Holding): string | null {
  return quotable(holding) ? marketKey(holding.market, holding.code) : null;
}

export function historyOf(snapshot: MarketSnapshot, holding: Holding): PriceHistory | undefined {
  const key = keyOf(holding);
  return key === null ? undefined : snapshot.prices.get(key);
}

function sortedDates(map: Map<string, number>): string[] {
  return [...map.keys()].sort();
}

/** 가장 최근 환율 [날짜, 환율] (0 이하는 "그날 데이터 없음" 표시라 건너뛴다). 오늘 받은 환율이 더 최근이면 그것. */
/** 환율 출처 표시 글 (core FxRules.sourceLabel 과 같음). */
export const fxSourceLabel = (source: string | null | undefined): string | null =>
  source === 'KIS' ? '한투' : source === 'EXIM' ? '수출입은행' : null;

/** 가장 최근 환율의 출처 표시 글 (오늘 받은 환율일 때만, 과거 목록 값이면 null). */
export function latestUsdKrwSourceLabel(snapshot: MarketSnapshot): string | null {
  const latest = latestUsdKrw(snapshot);
  const today = snapshot.usdKrwLatest;
  return latest !== null && today && latest[0] === today[0] && latest[1] === today[1] ? fxSourceLabel(snapshot.usdKrwLatestSource) : null;
}

export function latestUsdKrw(snapshot: MarketSnapshot): [string, number] | null {
  let past: [string, number] | null = null;
  const dates = sortedDates(snapshot.usdKrw);
  for (let i = dates.length - 1; i >= 0; i--) {
    const rate = snapshot.usdKrw.get(dates[i])!;
    if (rate > 0) {
      past = [dates[i], rate];
      break;
    }
  }
  const latest = snapshot.usdKrwLatest && snapshot.usdKrwLatest[1] > 0 ? snapshot.usdKrwLatest : null;
  return latest && (past === null || latest[0] >= past[0]) ? latest : past;
}

/** 환율 받는 규칙 (core FxRules 와 같음): 최근 1주일(어제 ~ 7일 전)은 계속 다시 받고, 그보다 오래된 과거는 두 번 받아 같은 값만 고정. */
export const FX_RECENT_DAYS = 7;
export const isRecentFxDate = (date: string, today: string): boolean => date < today && date >= plusDays(today, -FX_RECENT_DAYS);
/** 받은 값들 중 두 번 이상 같은 값 (없으면 null). "없음"은 -1. */
export const agreedFx = (values: readonly number[]): number | null => values.find((v) => values.filter((x) => x === v).length >= 2) ?? null;

/** [date] 당일 또는 그 이전의 가장 가까운 환율. */
export function usdKrwAt(snapshot: MarketSnapshot, date: string): number | null {
  const dates = sortedDates(snapshot.usdKrw).filter((d) => d <= date);
  for (let i = dates.length - 1; i >= 0; i--) {
    const rate = snapshot.usdKrw.get(dates[i])!;
    if (rate > 0) return rate;
  }
  return null;
}

/**
 * 매매일 환율 (원화 보기의 환차익 계산용): 그날 또는 직전 영업일(최대 7일 전)까지 받아 둔 환율.
 * 그 사이에 아직 받지 않은 날이 있으면 null (멀리 떨어진 옛 환율을 쓰지 않도록). 오늘 이후는 최신 환율.
 */
export function tradeUsdKrw(snapshot: MarketSnapshot, date: string, today: string): number | null {
  if (date >= today) return latestUsdKrw(snapshot)?.[1] ?? null;
  let day = date;
  for (let step = 0; step <= 7; step++) {
    const rate = snapshot.usdKrw.get(day);
    if (rate === undefined) return null;
    if (rate > 0) return rate;
    day = plusDays(day, -1);
  }
  return null;
}

/** [date] 당일 또는 그 이전의 가장 가까운 종가. */
export function closeOnOrBefore(history: PriceHistory | undefined, date: string): PricePoint | null {
  if (!history) return null;
  const dates = sortedDates(history.closes).filter((d) => d <= date);
  if (dates.length === 0) return null;
  const last = dates[dates.length - 1];
  return { price: history.closes.get(last)!, date: last };
}

/** 직접 입력한 가격과 자동 시세 중 날짜가 더 최근인 것 (같은 날이면 직접 입력 우선). */
export function newer(manual: PricePoint | null, auto: PricePoint | null): PricePoint | null {
  if (manual === null) return auto;
  if (auto === null) return manual;
  return (manual.date ?? MIN_DATE) >= (auto.date ?? MIN_DATE) ? manual : auto;
}

/** 자동 시세끼리는 같은 날이면 현재가(실시간에 가까움)를 우선한다. 규칙은 newer 와 같다. */
const newerAuto = newer;

const manualPoint = (holding: Holding): PricePoint | null =>
  holding.manualPrice === null ? null : { price: holding.manualPrice, date: holding.manualPriceDate };

/** 오늘 기준 현재가: 최신 현재가 또는 마지막 종가 중 최근 것과, 직접 입력 가격 중 최근 것. */
export function currentPrice(holding: Holding, snapshot: MarketSnapshot): PricePoint | null {
  const history = historyOf(snapshot, holding);
  let lastClose: PricePoint | null = null;
  if (history && history.closes.size > 0) {
    const last = sortedDates(history.closes).at(-1)!;
    lastClose = { price: history.closes.get(last)!, date: last };
  }
  const auto = newerAuto(history?.latest ?? null, lastClose);
  return newer(manualPoint(holding), auto);
}

/** 오늘 수익률 = 현재가 ÷ 전일 종가 − 1. 자동 시세(최신 현재가)를 쓰는 종목만 (직접 입력 가격이거나 전일 종가가 없으면 null). */
export function dailyChangeRate(holding: Holding, snapshot: MarketSnapshot): number | null {
  const history = historyOf(snapshot, holding);
  if (!history || history.latest === null || history.prevClose === null || history.prevClose <= 0) return null;
  const current = currentPrice(holding, snapshot);
  if (current === null || current.price !== history.latest.price || current.date !== history.latest.date) return null;
  return history.latest.price / history.prevClose - 1;
}

/** 과거 [date] 기준 가격: 그날 이전 종가, 없으면 그날 이전에 직접 입력한 가격, 그것도 없으면 직접 입력 가격. */
export function priceAt(holding: Holding, snapshot: MarketSnapshot, date: string): PricePoint | null {
  const close = closeOnOrBefore(historyOf(snapshot, holding), date);
  const manual = manualPoint(holding);
  const manualBefore = manual !== null && (manual.date ?? MIN_DATE) <= date ? manual : null;
  return newer(manualBefore, close) ?? manual;
}

/** 알고 있는 가격 하나. close 는 실제 종가인지 (매매 단가·직접 입력 가격이면 false). */
export interface KnownPrice {
  date: string;
  price: number;
  close: boolean;
}

/** 앞뒤 종가 간격이 이 날 수 이하면 휴일로 보고 전 거래일 종가를 쓴다. */
export const HOLIDAY_GAP_DAYS = 14;

const dayNumber = (d: string) => Date.UTC(Number(d.slice(0, 4)), Number(d.slice(5, 7)) - 1, Number(d.slice(8, 10))) / 86_400_000;
export const daysBetween = (a: string, b: string) => dayNumber(b) - dayNumber(a);

/**
 * 과거 날짜의 가격 추정 (core 의 PriceEstimator 와 같은 규칙).
 * 알고 있는 가격 = 저장된 종가 + 매수·매도 단가(같은 날이면 종가, 같은 날 여러 번이면 수량 가중평균, 같은 시세 키면 다른 계좌 기록도)
 * + 날짜가 있는 직접 입력 가격. ① 그날 값 ② 앞뒤가 모두 종가이고 14일 이하면(휴일) 전 거래일 종가
 * ③ 그 밖은 앞뒤 가격을 날짜 비율로 잇는다 ④ 첫 가격 앞은 첫 가격, 마지막 가격 뒤는 마지막 가격.
 */
export class PriceEstimator {
  private readonly series = new Map<string, KnownPrice[]>();

  constructor(holdings: readonly Holding[], records: readonly { type: string; holdingId: string | null; date: string; price: number; quantity: number }[], snapshot: MarketSnapshot) {
    const maps = new Map<string, Map<string, KnownPrice>>();
    const mapOf = (key: string) => {
      let m = maps.get(key);
      if (!m) maps.set(key, (m = new Map()));
      return m;
    };
    for (const h of holdings) {
      const key = keyOf(h);
      if (key === null || maps.has(key)) continue;
      const m = mapOf(key);
      snapshot.prices.get(key)?.closes.forEach((v, d) => {
        if (v > 0) m.set(d, { date: d, price: v, close: true });
      });
    }
    const byId = new Map(holdings.map((h) => [h.id, h]));
    const trades = new Map<string, { key: string; date: string; amount: number; quantity: number }>();
    for (const r of records) {
      if ((r.type !== 'BUY' && r.type !== 'SELL') || !(r.price > 0) || !(r.quantity > 0) || r.holdingId === null) continue;
      const h = byId.get(r.holdingId);
      if (!h) continue;
      const key = this.keyOf(h);
      const id = `${key}\u0000${r.date}`;
      const t = trades.get(id) ?? { key, date: r.date, amount: 0, quantity: 0 };
      t.amount += r.price * r.quantity;
      t.quantity += r.quantity;
      trades.set(id, t);
    }
    for (const t of trades.values()) {
      const m = mapOf(t.key);
      if (m.get(t.date)?.close) continue;
      m.set(t.date, { date: t.date, price: t.amount / t.quantity, close: false });
    }
    for (const h of holdings) {
      if (h.manualPrice === null || !(h.manualPrice > 0) || h.manualPriceDate === null) continue;
      const m = mapOf(this.keyOf(h));
      if (m.get(h.manualPriceDate)?.close) continue;
      m.set(h.manualPriceDate, { date: h.manualPriceDate, price: h.manualPrice, close: false });
    }
    for (const [key, m] of maps) this.series.set(key, [...m.values()].sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0)));
  }

  /** 가격 계열 키: 자동 시세 종목은 시세 키(계좌가 달라도 같음), 아니면 종목마다. */
  keyOf(holding: Holding): string {
    return keyOf(holding) ?? `id:${holding.id}`;
  }

  /** date 이하 마지막 알고 있는 가격의 위치 (없으면 -1). */
  private floorIndex(list: KnownPrice[], date: string): number {
    let lo = 0;
    let hi = list.length - 1;
    let found = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (list[mid].date <= date) {
        found = mid;
        lo = mid + 1;
      } else hi = mid - 1;
    }
    return found;
  }

  /** 추정 가격. 날짜는 쓴 앞쪽 알고 있는 가격의 날짜 (첫 가격 앞이면 첫 가격 날짜). 알고 있는 가격이 없으면 null. */
  estimate(holding: Holding, date: string): PricePoint | null {
    const list = this.series.get(this.keyOf(holding));
    if (!list || list.length === 0) return null;
    const i = this.floorIndex(list, date);
    const prev = i >= 0 ? list[i] : null;
    if (prev && prev.date === date) return { price: prev.price, date };
    const next = i + 1 < list.length ? list[i + 1] : null;
    if (prev === null) return next === null ? null : { price: next.price, date: next.date };
    if (next === null) return { price: prev.price, date: prev.date };
    if (prev.close && next.close && daysBetween(prev.date, next.date) <= HOLIDAY_GAP_DAYS) return { price: prev.price, date: prev.date };
    const t = daysBetween(prev.date, date) / daysBetween(prev.date, next.date);
    return { price: prev.price + (next.price - prev.price) * t, date: prev.date };
  }

  /** date 보다 앞의 마지막 알고 있는 가격의 날짜. */
  knownBefore(key: string, date: string): string | null {
    const list = this.series.get(key) ?? [];
    const i = this.floorIndex(list, date);
    const j = i >= 0 && list[i].date === date ? i - 1 : i;
    return j >= 0 ? list[j].date : null;
  }

  /** date 이후(같은 날 포함) 첫 알고 있는 가격. */
  knownFrom(key: string, date: string): KnownPrice | null {
    const list = this.series.get(key) ?? [];
    const i = this.floorIndex(list, date);
    if (i >= 0 && list[i].date === date) return list[i];
    return i + 1 < list.length ? list[i + 1] : null;
  }

  /** date 이전(같은 날 포함) 마지막 알고 있는 가격. */
  knownUntil(key: string, date: string): KnownPrice | null {
    const list = this.series.get(key) ?? [];
    const i = this.floorIndex(list, date);
    return i >= 0 ? list[i] : null;
  }

  /** 과거 date 기준 가격: 그날 이전의 직접 입력 가격이 더 최근이면 그것, 아니면 추정 가격, 둘 다 없으면 직접 입력 가격. */
  at(holding: Holding, date: string): PricePoint | null {
    const manual = manualPoint(holding);
    const manualBefore = manual !== null && (manual.date ?? MIN_DATE) <= date ? manual : null;
    return newer(manualBefore, this.estimate(holding, date)) ?? manual;
  }
}
