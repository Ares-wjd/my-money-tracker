// core 의 PortfolioCalculator 와 같은 규칙. 수수료·세금은 평균단가에 넣지 않고, 평균단가는 이동평균법.
import type { Currency, Holding, InvestmentAccount, PricePoint, Record } from '../model';
import { marketCurrency, totalCost } from '../model';
import { cashDetail, isBrokerLinked, type CashDetail } from './cash';

export interface CashBalance {
  krw: number;
  usd: number;
}

export const cashOf = (cash: CashBalance, currency: Currency): number => (currency === 'KRW' ? cash.krw : cash.usd);
export const cashToKrw = (cash: CashBalance, usdKrw: number | null): number => cash.krw + cash.usd * (usdKrw ?? 0);

/** 종목의 보유 현황 (종목 통화 기준). */
export interface Position {
  holding: Holding;
  quantity: number;
  averagePrice: number;
  realizedProfit: number;
  dividends: number;
  feesAndTaxes: number;
  costBasis: number;
}

export interface HoldingValuation {
  holding: Holding;
  position: Position;
  price: PricePoint | null;
  /** 평가금 (종목 통화). 가격이 없으면 null. */
  marketValue: number | null;
  /** 원화 평가금. 가격이나 (달러 종목의) 환율이 없으면 null. */
  marketValueKrw: number | null;
  unrealizedProfit: number | null;
  /** 평가손익률 (배당 미포함) */
  returnRate: number | null;
  /** 평가손익률 (배당 포함) */
  returnRateWithDividends: number | null;
}

export interface AccountSummary {
  account: InvestmentAccount;
  /** 투자금 = 입금 − 출금 ± 이체 (원화) */
  investedKrw: number;
  /** 예수금 (혼합 방식, cash.ts): 한투 연결 계좌는 불러온 값, 직접 입력 계좌는 입력값 + 기록으로 계산 */
  cash: CashBalance;
  /** 가장 최근 예수금 기준점(입력하거나 불러온 기록)의 날짜 */
  cashDate: string | null;
  /** 통화별 예수금 근거 */
  cashDetails: { KRW: CashDetail; USD: CashDetail };
  /** 증권사에서 불러오는 계좌인지 (예수금을 계산하지 않음) */
  brokerLinked: boolean;
  holdings: HoldingValuation[];
  dividendsKrw: number;
  missingPrice: boolean;
  missingFx: boolean;
  usdKrw: number | null;
  valueKrw: number;
  profitKrw: number;
  profitExDividendsKrw: number;
  returnRate: number | null;
  returnRateExDividends: number | null;
}

export interface PortfolioSummary {
  accounts: AccountSummary[];
  usdKrw: number | null;
  investedKrw: number;
  valueKrw: number;
  dividendsKrw: number;
  profitKrw: number;
  profitExDividendsKrw: number;
  returnRate: number | null;
  returnRateExDividends: number | null;
  missingPrice: boolean;
  missingFx: boolean;
}

const EPSILON = 1e-9;
const sum = (values: number[]) => values.reduce((a, b) => a + b, 0);
const rateOf = (profit: number, base: number): number | null => (base > 0 ? profit / base : null);

/** 기록을 날짜순(같은 날이면 입력순)으로 정렬. */
export function compareRecords(a: Record, b: Record): number {
  if (a.date !== b.date) return a.date < b.date ? -1 : 1;
  if (a.createdAt !== b.createdAt) return a.createdAt - b.createdAt;
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
}

/** 한 종목의 매수·매도·배당 기록으로 보유 현황을 계산한다. */
export function position(holding: Holding, records: readonly Record[]): Position {
  let quantity = 0;
  let average = 0;
  let realized = 0;
  let dividends = 0;
  let fees = 0;
  for (const r of [...records].sort(compareRecords)) {
    if (r.type === 'BUY') {
      const newQuantity = quantity + r.quantity;
      if (newQuantity > 0) average = (quantity * average + r.quantity * r.price) / newQuantity;
      quantity = newQuantity;
      fees += totalCost(r);
    } else if (r.type === 'SELL') {
      const sold = Math.min(r.quantity, quantity);
      realized += sold * (r.price - average) - totalCost(r);
      quantity -= sold;
      if (quantity <= EPSILON) {
        quantity = 0;
        average = 0;
      }
      fees += totalCost(r);
    } else if (r.type === 'DIVIDEND') {
      dividends += r.amount;
    }
  }
  return { holding, quantity, averagePrice: average, realizedProfit: realized, dividends, feesAndTaxes: fees, costBasis: quantity * average };
}

/** [date] 시점의 보유 수량. [excludeRecordId] 는 수정 중인 기록을 빼고 계산할 때 사용. */
export function quantityAt(holding: Holding, records: readonly Record[], date: string, excludeRecordId?: string): number {
  return position(
    holding,
    records.filter((r) => r.holdingId === holding.id && r.date <= date && r.id !== excludeRecordId),
  ).quantity;
}

const defaultPrice = (h: Holding): PricePoint | null =>
  h.manualPrice === null ? null : { price: h.manualPrice, date: h.manualPriceDate };

function summarizeAccount(
  account: InvestmentAccount,
  holdings: Holding[],
  records: Record[],
  allRecords: Record[],
  asOf: string | null,
  usdKrw: number | null,
  priceOf: (h: Holding) => PricePoint | null,
): AccountSummary {
  let invested = 0;
  // 예수금 (혼합 방식): 첫 기준점 이전 추정에 그 뒤 기록도 필요해 날짜 제한 없는 기록으로 계산한다.
  const linked = isBrokerLinked(account.id, allRecords);
  const cashDetails = {
    KRW: cashDetail(account.id, 'KRW', allRecords, holdings, asOf, linked),
    USD: cashDetail(account.id, 'USD', allRecords, holdings, asOf, linked),
  };
  const cash: CashBalance = { krw: cashDetails.KRW.amount, usd: cashDetails.USD.amount };
  const anchorDates = [cashDetails.KRW.anchorDate, cashDetails.USD.anchorDate].filter((d): d is string => d !== null).sort();
  const cashDate = anchorDates.length > 0 ? anchorDates[anchorDates.length - 1] : null;

  for (const r of records) {
    const incoming = r.type === 'TRANSFER' && r.toAccountId === account.id && r.accountId !== account.id;
    if (r.type === 'DEPOSIT') invested += r.krwAmount;
    else if (r.type === 'WITHDRAW') invested -= r.krwAmount;
    else if (r.type === 'TRANSFER') invested += (incoming ? 1 : -1) * r.krwAmount;
  }

  const valuations: HoldingValuation[] = holdings.map((holding) => {
    const pos = position(holding, records.filter((r) => r.holdingId === holding.id && r.accountId === account.id));
    const price = priceOf(holding);
    const marketValue = price === null ? null : pos.quantity * price.price;
    const currency = marketCurrency(holding.market);
    const marketValueKrw =
      marketValue === null ? null : currency === 'KRW' ? marketValue : usdKrw !== null ? marketValue * usdKrw : null;
    const unrealizedProfit = marketValue === null ? null : marketValue - pos.costBasis;
    return {
      holding,
      position: pos,
      price,
      marketValue,
      marketValueKrw,
      unrealizedProfit,
      returnRate: unrealizedProfit === null ? null : rateOf(unrealizedProfit, pos.costBasis),
      returnRateWithDividends: unrealizedProfit === null ? null : rateOf(unrealizedProfit + pos.dividends, pos.costBasis),
    };
  });

  const dividendsKrw = sum(
    valuations.map((v) => (marketCurrency(v.holding.market) === 'KRW' ? v.position.dividends : v.position.dividends * (usdKrw ?? 0))),
  );
  const hasUsd = cash.usd !== 0 || valuations.some((v) => marketCurrency(v.holding.market) === 'USD' && v.position.quantity > 0);
  const valueKrw = sum(valuations.map((v) => v.marketValueKrw ?? 0)) + cashToKrw(cash, usdKrw);
  const profitKrw = valueKrw - invested;
  const profitExDividendsKrw = profitKrw - dividendsKrw;

  return {
    account,
    investedKrw: invested,
    cash,
    cashDate,
    cashDetails,
    brokerLinked: linked,
    holdings: valuations,
    dividendsKrw,
    missingPrice: valuations.some((v) => v.position.quantity > 0 && v.price === null),
    missingFx: hasUsd && usdKrw === null,
    usdKrw,
    valueKrw,
    profitKrw,
    profitExDividendsKrw,
    returnRate: rateOf(profitKrw, invested),
    returnRateExDividends: rateOf(profitExDividendsKrw, invested),
  };
}

/**
 * @param priceOf 종목의 현재가 (없으면 null)
 * @param usdKrw 원/달러 환율 (없으면 달러 금액은 원화 합계에서 빠진다)
 * @param asOf 이 날짜까지의 기록만 반영 (null 이면 전부)
 */
export function summarize(
  accounts: readonly InvestmentAccount[],
  holdings: readonly Holding[],
  records: readonly Record[],
  usdKrw: number | null,
  priceOf: (h: Holding) => PricePoint | null = defaultPrice,
  asOf: string | null = null,
): PortfolioSummary {
  const sorted = [...records].sort(compareRecords);
  const effective = sorted.filter((r) => asOf === null || r.date <= asOf);
  const summaries = accounts.map((account) => {
    const related = (r: Record) => r.accountId === account.id || r.toAccountId === account.id;
    return summarizeAccount(
      account,
      holdings.filter((h) => h.accountId === account.id),
      effective.filter(related),
      sorted.filter(related),
      asOf,
      usdKrw,
      priceOf,
    );
  });
  const investedKrw = sum(summaries.map((a) => a.investedKrw));
  const valueKrw = sum(summaries.map((a) => a.valueKrw));
  const dividendsKrw = sum(summaries.map((a) => a.dividendsKrw));
  const profitKrw = valueKrw - investedKrw;
  const profitExDividendsKrw = profitKrw - dividendsKrw;
  return {
    accounts: summaries,
    usdKrw,
    investedKrw,
    valueKrw,
    dividendsKrw,
    profitKrw,
    profitExDividendsKrw,
    returnRate: rateOf(profitKrw, investedKrw),
    returnRateExDividends: rateOf(profitExDividendsKrw, investedKrw),
    missingPrice: summaries.some((a) => a.missingPrice),
    missingFx: summaries.some((a) => a.missingFx),
  };
}

/** 해외 종목 금액을 어떤 통화로 보여줄지 (core 의 DisplayCurrency). */
export type DisplayCurrency = 'KRW' | 'FOREIGN';

/** 종목 금액의 표시 통화와 곱할 비율. 원화 보기의 해외 종목은 현재 환율로 단순 환산한다 (환율 없으면 rate null). */
export interface DisplayConversion {
  currency: Currency;
  rate: number | null;
}

export function displayConversion(holdingCurrency: Currency, mode: DisplayCurrency, usdKrw: number | null): DisplayConversion {
  return mode === 'KRW' && holdingCurrency === 'USD' ? { currency: 'KRW', rate: usdKrw } : { currency: holdingCurrency, rate: 1 };
}

export const convert = (c: DisplayConversion, value: number): number | null => (c.rate === null ? null : value * c.rate);

/**
 * 원화 보기의 해외 종목 숫자 (환차익 반영, core 의 KrwHoldingView 와 같음). 매입금액·평균단가는 매수한 날의 환율로 쌓고(이동평균),
 * 평가금은 오늘 환율로 환산한다. 실현손익은 매도한 날, 배당은 받은 날의 환율로 환산한다.
 */
export interface KrwHoldingView {
  averagePriceKrw: number;
  costBasisKrw: number;
  marketValueKrw: number | null;
  /** 원화 평가손익 = 주가 손익 + 환차익 */
  unrealizedProfitKrw: number | null;
  /** 주가 손익 = 달러 평가손익 × 오늘 환율 */
  stockProfitKrw: number | null;
  /** 환차익 = 달러 매입금액 × 오늘 환율 − 원화 매입금액 */
  fxProfitKrw: number;
  realizedProfitKrw: number;
  dividendsKrw: number;
  feesAndTaxesKrw: number;
  returnRate: number | null;
  returnRateWithDividends: number | null;
}

/** 달러 종목이 아니거나, 오늘 환율 또는 기록 중 하루라도 환율이 없으면 null (화면은 오늘 환율로 단순 환산한다). */
export function krwHoldingView(
  valuation: HoldingValuation,
  records: readonly Record[],
  usdKrw: number | null,
  fxAt: (date: string) => number | null,
): KrwHoldingView | null {
  if (marketCurrency(valuation.holding.market) !== 'USD' || usdKrw === null) return null;
  let quantity = 0;
  let averageKrw = 0;
  let realizedKrw = 0;
  let dividendsKrw = 0;
  let feesKrw = 0;
  const own = records.filter((r) => r.holdingId === valuation.holding.id).sort(compareRecords);
  for (const r of own) {
    if (r.type !== 'BUY' && r.type !== 'SELL' && r.type !== 'DIVIDEND') continue;
    const fx = fxAt(r.date);
    if (fx === null) return null;
    if (r.type === 'BUY') {
      const newQuantity = quantity + r.quantity;
      if (newQuantity > 0) averageKrw = (quantity * averageKrw + r.quantity * r.price * fx) / newQuantity;
      quantity = newQuantity;
      feesKrw += totalCost(r) * fx;
    } else if (r.type === 'SELL') {
      const sold = Math.min(r.quantity, quantity);
      realizedKrw += sold * (r.price * fx - averageKrw) - totalCost(r) * fx;
      quantity -= sold;
      if (quantity <= EPSILON) {
        quantity = 0;
        averageKrw = 0;
      }
      feesKrw += totalCost(r) * fx;
    } else {
      dividendsKrw += r.amount * fx;
    }
  }
  const costKrw = quantity * averageKrw;
  const marketValueKrw = valuation.marketValue === null ? null : valuation.marketValue * usdKrw;
  const unrealized = marketValueKrw === null ? null : marketValueKrw - costKrw;
  return {
    averagePriceKrw: averageKrw,
    costBasisKrw: costKrw,
    marketValueKrw,
    unrealizedProfitKrw: unrealized,
    stockProfitKrw: valuation.unrealizedProfit === null ? null : valuation.unrealizedProfit * usdKrw,
    fxProfitKrw: valuation.position.costBasis * usdKrw - costKrw,
    realizedProfitKrw: realizedKrw,
    dividendsKrw,
    feesAndTaxesKrw: feesKrw,
    returnRate: unrealized === null ? null : rateOf(unrealized, costKrw),
    returnRateWithDividends: unrealized === null ? null : rateOf(unrealized + dividendsKrw, costKrw),
  };
}

/** 보유 종목 정렬 기준 (모두 높은 순). core 의 HoldingSort 와 같음. */
export type HoldingSort = 'VALUE' | 'TOTAL_RETURN' | 'TODAY_RETURN';
export const HOLDING_SORTS: readonly HoldingSort[] = ['VALUE', 'TOTAL_RETURN', 'TODAY_RETURN'];
export const HOLDING_SORT_LABEL: { [K in HoldingSort]: string } = { VALUE: '평가금', TOTAL_RETURN: '총 수익률', TODAY_RETURN: '오늘 수익률' };

/** [sort] 기준 높은 순. 값이 없는 종목은 맨 뒤, 같으면 평가금이 큰 순. */
export function sortHoldings(
  holdings: readonly HoldingValuation[],
  sort: HoldingSort,
  totalRate: (v: HoldingValuation) => number | null,
  todayRate: (v: HoldingValuation) => number | null,
): HoldingValuation[] {
  const key = sort === 'VALUE' ? (v: HoldingValuation) => v.marketValueKrw : sort === 'TOTAL_RETURN' ? totalRate : todayRate;
  return [...holdings].sort((a, b) => {
    const ka = key(a);
    const kb = key(b);
    if ((ka === null) !== (kb === null)) return ka === null ? 1 : -1;
    if (ka !== null && kb !== null && ka !== kb) return kb - ka;
    return (b.marketValueKrw ?? 0) - (a.marketValueKrw ?? 0);
  });
}
