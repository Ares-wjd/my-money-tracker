// core 의 CashCalculator 와 같은 규칙: 예수금 (혼합 방식).
// - 증권사에서 불러오는 계좌 (한투에서 불러온 기록이 있는 계좌): 불러온 예수금 기록만. 그날 또는 이전의 마지막 값, 없으면 0.
// - 직접 입력 계좌: 입력한 예수금(그날 기록이 모두 반영된 잔액)을 기준점으로, 그 사이는 기록으로 계산.
//   기준점 이후 = 기준점 + 다음 날부터의 기록, 첫 기준점 이전 = 첫 기준점 − 그 사이 기록, 기준점이 없으면 기록만으로.
//   초기 보유분 매수는 예수금에서 나간 돈이 아니므로 빼지 않는다.
import { marketCurrency, totalCost, type Currency, type Holding, type Record } from '../model';

/** 통화 하나의 예수금과 근거 (기준점, 기준점 이후(이전) 기록으로 더한 금액, 계산값인지). */
export interface CashDetail {
  amount: number;
  anchor: number | null;
  anchorDate: string | null;
  change: number;
  estimated: boolean;
}

const compare = (a: Record, b: Record) => (a.date < b.date ? -1 : a.date > b.date ? 1 : a.createdAt - b.createdAt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));

/** 증권사에서 불러오는 계좌인지 (한투에서 불러온 기록이 있으면). */
export const isBrokerLinked = (accountId: string, records: readonly Record[]): boolean =>
  records.some((r) => r.accountId === accountId && r.externalId !== null);

/** 기록 하나가 이 계좌의 currency 예수금을 바꾸는 금액. */
export function cashDelta(r: Record, accountId: string, currency: Currency, holdingCurrency: (id: string) => Currency | undefined): number {
  const own = r.accountId === accountId;
  switch (r.type) {
    case 'DEPOSIT':
      return own && r.currency === currency ? r.amount : 0;
    case 'WITHDRAW':
      return own && r.currency === currency ? -r.amount : 0;
    case 'TRANSFER':
      if (r.currency !== currency) return 0;
      if (own && r.toAccountId !== accountId) return -r.amount;
      if (!own && r.toAccountId === accountId) return r.amount;
      return 0;
    case 'BUY':
    case 'SELL':
    case 'DIVIDEND': {
      if (!own || r.initial || r.holdingId === null || holdingCurrency(r.holdingId) !== currency) return 0;
      if (r.type === 'BUY') return -(r.quantity * r.price + totalCost(r));
      if (r.type === 'SELL') return r.quantity * r.price - totalCost(r);
      return r.amount;
    }
    // 환전: currency 는 바꾸기 전 통화, krwAmount 원화 금액, amount 달러 금액
    case 'EXCHANGE':
      if (!own) return 0;
      if (r.currency === 'KRW') return currency === 'KRW' ? -r.krwAmount : r.amount;
      return currency === 'USD' ? -r.amount : r.krwAmount;
    default:
      return 0;
  }
}

/**
 * date 의 currency 예수금. records 는 이 계좌와 관련된 모든 기록 (날짜 제한 없이), date 가 null 이면 모든 기록 기준.
 * excludeAnchorOn 날의 기준점은 빼고 계산한다 (입력 창의 "계산값" 용).
 */
export function cashDetail(
  accountId: string,
  currency: Currency,
  records: readonly Record[],
  holdings: readonly Holding[],
  date: string | null,
  linked: boolean,
  excludeAnchorOn: string | null = null,
): CashDetail {
  const sorted = [...records].sort(compare);
  const anchors = sorted.filter((r) => r.type === 'CASH_BALANCE' && r.accountId === accountId && r.currency === currency && r.date !== excludeAnchorOn);
  const last = [...anchors].reverse().find((r) => date === null || r.date <= date) ?? null;
  if (linked) {
    return last ? { amount: last.amount, anchor: last.amount, anchorDate: last.date, change: 0, estimated: false } : { amount: 0, anchor: null, anchorDate: null, change: 0, estimated: false };
  }
  const currencyOf = new Map(holdings.map((h) => [h.id, marketCurrency(h.market)]));
  // (from, to] 사이 기록의 합 (from 이 null 이면 처음부터, to 가 null 이면 끝까지)
  const change = (from: string | null, to: string | null) =>
    sorted
      .filter((r) => (from === null || r.date > from) && (to === null || r.date <= to))
      .reduce((s, r) => s + cashDelta(r, accountId, currency, (id) => currencyOf.get(id)), 0);
  if (last) {
    const c = change(last.date, date);
    return { amount: last.amount + c, anchor: last.amount, anchorDate: last.date, change: c, estimated: Math.abs(c) > 1e-9 };
  }
  const first = anchors[0];
  if (first) {
    const c = -change(date, first.date);
    return { amount: first.amount + c, anchor: first.amount, anchorDate: first.date, change: c, estimated: true };
  }
  const c = change(null, date);
  return { amount: c, anchor: null, anchorDate: null, change: c, estimated: Math.abs(c) > 1e-9 };
}
