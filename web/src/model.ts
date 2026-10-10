// core 모듈(Kotlin)의 모델과 같은 형태. 날짜는 "YYYY-MM-DD" 문자열로 다룬다 (문자열 비교 = 날짜 비교).

export type Currency = 'KRW' | 'USD';
export type Market = 'KR' | 'NASDAQ' | 'NYSE' | 'AMEX' | 'US_OTHER';
export type AssetType = 'STOCK' | 'ETF' | 'FUND' | 'BOND';
export type AccountKind = 'GENERAL' | 'ISA' | 'PENSION' | 'IRP' | 'DC' | 'OTHER';
export type RecordType =
  | 'DEPOSIT'
  | 'WITHDRAW'
  | 'TRANSFER'
  | 'EXCHANGE'
  | 'BUY'
  | 'SELL'
  | 'DIVIDEND'
  | 'CASH_ADJUST'
  | 'CASH_BALANCE';
export type GoalType = 'ONE_TIME' | 'RECURRING';

export const CURRENCIES: readonly Currency[] = ['KRW', 'USD'];
export const MARKETS: readonly Market[] = ['KR', 'NASDAQ', 'NYSE', 'AMEX', 'US_OTHER'];
export const ASSET_TYPES: readonly AssetType[] = ['STOCK', 'ETF', 'FUND', 'BOND'];
export const ACCOUNT_KINDS: readonly AccountKind[] = ['GENERAL', 'ISA', 'PENSION', 'IRP', 'DC', 'OTHER'];
export const RECORD_TYPES: readonly RecordType[] = [
  'DEPOSIT',
  'WITHDRAW',
  'TRANSFER',
  'EXCHANGE',
  'BUY',
  'SELL',
  'DIVIDEND',
  'CASH_ADJUST',
  'CASH_BALANCE',
];
export const GOAL_TYPES: readonly GoalType[] = ['ONE_TIME', 'RECURRING'];

export const marketCurrency = (market: Market): Currency => (market === 'KR' ? 'KRW' : 'USD');

export interface InvestmentAccount {
  id: string;
  name: string;
  kind: AccountKind;
  number: string;
  memo: string;
  createdAt: number;
}

export interface Holding {
  id: string;
  accountId: string;
  name: string;
  code: string;
  market: Market;
  assetType: AssetType;
  manualPrice: number | null;
  manualPriceDate: string | null;
  createdAt: number;
}

/** 계좌의 모든 기록. 유형별로 쓰는 필드는 core 의 Record 설명과 같다. */
export interface Record {
  id: string;
  accountId: string;
  type: RecordType;
  date: string;
  currency: Currency;
  amount: number;
  krwAmount: number;
  toAccountId: string | null;
  holdingId: string | null;
  quantity: number;
  price: number;
  fee: number;
  tax: number;
  initial: boolean;
  externalId: string | null;
  memo: string;
  createdAt: number;
}

export interface SavingsSubAccount {
  name: string;
  number: string;
  balance: number;
}

export interface SavingsAccount {
  id: string;
  name: string;
  subAccounts: SavingsSubAccount[];
  balanceDate: string | null;
  annualRate: number;
  memo: string;
  createdAt: number;
}

export interface SavingsGoal {
  id: string;
  accountId: string;
  name: string;
  type: GoalType;
  amount: number;
  dueDate: string;
  intervalMonths: number;
  createdAt: number;
}

export interface AppSettings {
  manualUsdKrw: number | null;
}

export interface PricePoint {
  price: number;
  date: string | null;
}

/** 시세 키(KR_005930, US_AAPL) 하나의 종가 기록과 최신가. */
export interface PriceHistory {
  /** 날짜 → 종가 */
  closes: Map<string, number>;
  latest: PricePoint | null;
}

/** 폰이 Firestore(marketData)에 저장해 둔 시세·환율. */
export interface MarketSnapshot {
  prices: Map<string, PriceHistory>;
  /** 날짜 → 원/달러 환율 */
  usdKrw: Map<string, number>;
}

export const savingsBalance = (account: SavingsAccount): number =>
  account.subAccounts.reduce((sum, sub) => sum + sub.balance, 0);

/** 한투에서 불러온 기록인지 (폰에서 externalId 를 "KIS:..." 로 저장한다). */
export const isImported = (record: Record): boolean => record.externalId?.startsWith('KIS:') ?? false;

/** 한투 연결 계좌인지: 연결 정보는 폰에만 있으므로, 한투에서 불러온 기록이 있는 계좌로 판단한다. */
export const linkedAccountIds = (records: readonly Record[]): Set<string> =>
  new Set(records.filter(isImported).map((r) => r.accountId));
