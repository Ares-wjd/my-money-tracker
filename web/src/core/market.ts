// core 의 MarketKeys · PriceLookup · MarketSnapshot 과 같은 규칙.
import type { Holding, Market, MarketSnapshot, PriceHistory, PricePoint } from '../model';

const MIN_DATE = '0000-00-00';

/** 자동 시세를 받을 수 있는 종목인지 (펀드·채권은 직접 입력). */
export function quotable(holding: Holding): boolean {
  return holding.code.trim() !== '' && (holding.assetType === 'STOCK' || holding.assetType === 'ETF');
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

/** 가장 최근 환율 [날짜, 환율] (0 이하는 "그날 데이터 없음" 표시라 건너뛴다). */
export function latestUsdKrw(snapshot: MarketSnapshot): [string, number] | null {
  const dates = sortedDates(snapshot.usdKrw);
  for (let i = dates.length - 1; i >= 0; i--) {
    const rate = snapshot.usdKrw.get(dates[i])!;
    if (rate > 0) return [dates[i], rate];
  }
  return null;
}

/** [date] 당일 또는 그 이전의 가장 가까운 환율. */
export function usdKrwAt(snapshot: MarketSnapshot, date: string): number | null {
  const dates = sortedDates(snapshot.usdKrw).filter((d) => d <= date);
  for (let i = dates.length - 1; i >= 0; i--) {
    const rate = snapshot.usdKrw.get(dates[i])!;
    if (rate > 0) return rate;
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

/** 과거 [date] 기준 가격: 그날 이전 종가, 없으면 그날 이전에 직접 입력한 가격, 그것도 없으면 직접 입력 가격. */
export function priceAt(holding: Holding, snapshot: MarketSnapshot, date: string): PricePoint | null {
  const close = closeOnOrBefore(historyOf(snapshot, holding), date);
  const manual = manualPoint(holding);
  const manualBefore = manual !== null && (manual.date ?? MIN_DATE) <= date ? manual : null;
  return newer(manualBefore, close) ?? manual;
}
