// core 의 PortfolioCalculator 와 같은 규칙. 수수료·세금은 평균단가에 넣지 않고, 평균단가는 이동평균법.
import type { Currency, Holding, InvestmentAccount, PricePoint, Record } from '../model';
import { marketCurrency } from '../model';

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
  /** 예수금: 통화별로 가장 최근의 예수금 기록. 기록이 없으면 0. */
  cash: CashBalance;
  cashDate: string | null;
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
      fees += r.fee + r.tax;
    } else if (r.type === 'SELL') {
      const sold = Math.min(r.quantity, quantity);
      realized += sold * (r.price - average) - r.fee - r.tax;
      quantity -= sold;
      if (quantity <= EPSILON) {
        quantity = 0;
        average = 0;
      }
      fees += r.fee + r.tax;
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
  usdKrw: number | null,
  priceOf: (h: Holding) => PricePoint | null,
): AccountSummary {
  let invested = 0;
  const cash: CashBalance = { krw: 0, usd: 0 };
  let cashDate: string | null = null;

  // 예수금은 계산하지 않는다. 통화별로 가장 최근의 예수금 기록(records 는 날짜순)을 쓴다.
  for (const r of records) {
    const incoming = r.type === 'TRANSFER' && r.toAccountId === account.id && r.accountId !== account.id;
    if (r.type === 'DEPOSIT') invested += r.krwAmount;
    else if (r.type === 'WITHDRAW') invested -= r.krwAmount;
    else if (r.type === 'TRANSFER') invested += (incoming ? 1 : -1) * r.krwAmount;
    else if (r.type === 'CASH_BALANCE' && r.accountId === account.id) {
      if (r.currency === 'KRW') cash.krw = r.amount;
      else cash.usd = r.amount;
      cashDate = r.date;
    }
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
  const effective = records.filter((r) => asOf === null || r.date <= asOf).sort(compareRecords);
  const summaries = accounts.map((account) =>
    summarizeAccount(
      account,
      holdings.filter((h) => h.accountId === account.id),
      effective.filter((r) => r.accountId === account.id || r.toAccountId === account.id),
      usdKrw,
      priceOf,
    ),
  );
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
