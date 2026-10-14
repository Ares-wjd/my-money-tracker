// core 모듈(Kotlin)의 모델과 같은 형태. 날짜는 "YYYY-MM-DD" 문자열로 다룬다 (문자열 비교 = 날짜 비교).

export type Currency = 'KRW' | 'USD';
export type Market = 'KR' | 'NASDAQ' | 'NYSE' | 'AMEX' | 'US_OTHER';
export type AssetType = 'STOCK' | 'ETF' | 'FUND' | 'BOND' | 'GOLD';
export type AccountKind = 'GENERAL' | 'ISA' | 'PENSION' | 'IRP' | 'DC' | 'GOLD' | 'OTHER';
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
export const ASSET_TYPES: readonly AssetType[] = ['STOCK', 'ETF', 'FUND', 'BOND', 'GOLD'];
export const ACCOUNT_KINDS: readonly AccountKind[] = ['GENERAL', 'ISA', 'PENSION', 'IRP', 'DC', 'GOLD', 'OTHER'];
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
  /** 제비용합 (종목 통화). 수수료·세금을 쓰면 그 합, 제비용합만 쓰면 수수료·세금은 0. 예전 기록·한투 기록은 null (수수료 + 세금). */
  cost: number | null;
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
  /** 속한 그룹 (1회성만) */
  groupId: string | null;
  /** 반복 목표: 마지막으로 실제 소비한 날 */
  lastSpentDate: string | null;
  /** 반복 목표: 지난 소비 날짜들 (오래된 순, 최근 50개) */
  spentDates: string[];
  /** 1회성 목표: 구매 완료한 날 (있으면 배분·납입 계산에서 뺀다) */
  completedDate: string | null;
}

/** 함께 살 1회성 목표들의 묶음. 목표는 groupId 로 연결한다. */
export interface SavingsGoalGroup {
  id: string;
  accountId: string;
  name: string;
  /** 그룹 구매 날짜 (하나로 계산할 때 쓰는 날짜) */
  dueDate: string;
  /** true: 하나로 계산 (기본), false: 각자 날짜로 따로 계산 */
  combined: boolean;
  createdAt: number;
}

export const isCompleted = (g: SavingsGoal): boolean => g.type === 'ONE_TIME' && g.completedDate !== null;

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
  /** latest 의 전일 종가 (오늘 수익률 계산용) */
  prevClose: number | null;
}

/** 폰이 Firestore(marketData)에 저장해 둔 시세·환율. */
export interface MarketSnapshot {
  prices: Map<string, PriceHistory>;
  /** 날짜 → 원/달러 환율 (지난 날짜만, 최근 1주일·확인 전 값 위에 고정된 값을 덮은 것) */
  usdKrw: Map<string, number>;
  /** 오늘 받은 환율 [날짜, 환율] (오늘 평가금용, 과거 환율 목록에는 넣지 않는다) */
  usdKrwLatest?: [string, number] | null;
}

export const savingsBalance = (account: SavingsAccount): number =>
  account.subAccounts.reduce((sum, sub) => sum + sub.balance, 0);

/** 한투에서 불러온 기록인지 (폰에서 externalId 를 "KIS:..." 로 저장한다). */
export const isImported = (record: Record): boolean => record.externalId?.startsWith('KIS:') ?? false;

/** 한투 연결 계좌인지: 연결 정보는 폰에만 있으므로, 한투에서 불러온 기록이 있는 계좌로 판단한다. */
export const linkedAccountIds = (records: readonly Record[]): Set<string> =>
  new Set(records.filter(isImported).map((r) => r.accountId));

// 화면 표시용 이름 (폰 앱과 같음)
export const ACCOUNT_KIND_LABEL: { [K in AccountKind]: string } = {
  GENERAL: '일반(위탁)', ISA: 'ISA', PENSION: '연금저축', IRP: 'IRP', DC: '퇴직연금(DC)', GOLD: '금현물', OTHER: '기타',
};

/** 배지에 쓰는 짧은 계좌 종류 (폰과 같음). */
export const ACCOUNT_KIND_SHORT: { [K in AccountKind]: string } = {
  GENERAL: '위탁',
  ISA: 'ISA',
  PENSION: '연금',
  IRP: 'IRP',
  DC: 'DC',
  GOLD: '금',
  OTHER: '기타',
};
/** 계산에 쓰는 제비용합 (core Record.totalCost 와 같음). */
export const totalCost = (r: Pick<Record, 'cost' | 'fee' | 'tax'>): number => r.cost ?? r.fee + r.tax;

export const ASSET_TYPE_LABEL: { [K in AssetType]: string } = { STOCK: '주식', ETF: 'ETF', FUND: '펀드', BOND: '채권', GOLD: '금현물' };
/** KRX 금 99.99 1kg 종목코드. */
export const GOLD_CODE = 'M04020000';
/** 수량 단위 (금현물은 g). */
export const quantityUnit = (assetType: AssetType | undefined): string => (assetType === 'GOLD' ? 'g' : '주');
export const MARKET_LABEL: { [K in Market]: string } = {
  KR: '국내', NASDAQ: '나스닥', NYSE: '뉴욕', AMEX: '아멕스', US_OTHER: '미국 기타',
};
export const RECORD_TYPE_LABEL: { [K in RecordType]: string } = {
  DEPOSIT: '입금', WITHDRAW: '출금', TRANSFER: '계좌 간 이체', EXCHANGE: '환전', BUY: '매수', SELL: '매도',
  DIVIDEND: '배당', CASH_ADJUST: '예수금 조정', CASH_BALANCE: '예수금',
};
export const CURRENCY_LABEL: { [K in Currency]: string } = { KRW: '원화', USD: '달러' };
export const GOAL_TYPE_LABEL: { [K in GoalType]: string } = { ONE_TIME: '1회성', RECURRING: '반복' };
