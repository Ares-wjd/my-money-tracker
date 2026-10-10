// Firestore 문서 → 모델. 폰(PortfolioRepository·SavingsRepository·MarketDataRepository)의 읽기 규칙과 같게 맞춘다.
import {
  ACCOUNT_KINDS,
  ASSET_TYPES,
  CURRENCIES,
  GOAL_TYPES,
  MARKETS,
  RECORD_TYPES,
  type AppSettings,
  type Holding,
  type InvestmentAccount,
  type MarketSnapshot,
  type PriceHistory,
  type Record,
  type SavingsAccount,
  type SavingsGoal,
  type SavingsSubAccount,
} from '../model';

type Data = { [key: string]: unknown };

export const PRICE_PREFIX = 'price_';
export const FX_DOC = 'fx_USD';

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

const str = (v: unknown): string | null => (typeof v === 'string' ? v : null);
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const date = (v: unknown): string | null => (typeof v === 'string' && DATE_PATTERN.test(v) ? v : null);
const oneOf = <T extends string>(values: readonly T[], v: unknown, fallback: T): T =>
  values.includes(v as T) ? (v as T) : fallback;

export function toAccount(id: string, d: Data): InvestmentAccount | null {
  const name = str(d.name);
  if (name === null) return null;
  return {
    id,
    name,
    kind: oneOf(ACCOUNT_KINDS, d.kind, 'GENERAL'),
    number: str(d.number) ?? '',
    memo: str(d.memo) ?? '',
    createdAt: num(d.createdAt) ?? 0,
  };
}

export function toHolding(id: string, d: Data): Holding | null {
  const accountId = str(d.accountId);
  if (accountId === null) return null;
  return {
    id,
    accountId,
    name: str(d.name) ?? '',
    code: str(d.code) ?? '',
    market: oneOf(MARKETS, d.market, 'KR'),
    assetType: oneOf(ASSET_TYPES, d.assetType, 'STOCK'),
    manualPrice: num(d.manualPrice),
    manualPriceDate: date(d.manualPriceDate),
    createdAt: num(d.createdAt) ?? 0,
  };
}

/** 모르는 유형(더 새 버전이 만든 기록)이나 날짜가 없는 기록은 건너뛴다. */
export function toRecord(id: string, d: Data): Record | null {
  const accountId = str(d.accountId);
  const recordDate = date(d.date);
  if (accountId === null || recordDate === null || !RECORD_TYPES.includes(d.type as Record['type'])) return null;
  return {
    id,
    accountId,
    type: d.type as Record['type'],
    date: recordDate,
    currency: oneOf(CURRENCIES, d.currency, 'KRW'),
    amount: num(d.amount) ?? 0,
    krwAmount: num(d.krwAmount) ?? 0,
    toAccountId: str(d.toAccountId),
    holdingId: str(d.holdingId),
    quantity: num(d.quantity) ?? 0,
    price: num(d.price) ?? 0,
    fee: num(d.fee) ?? 0,
    tax: num(d.tax) ?? 0,
    initial: d.initial === true,
    externalId: str(d.externalId),
    memo: str(d.memo) ?? '',
    createdAt: num(d.createdAt) ?? 0,
  };
}

export function toSavingsAccount(id: string, d: Data): SavingsAccount | null {
  const name = str(d.name);
  if (name === null) return null;
  const subAccounts: SavingsSubAccount[] = Array.isArray(d.subAccounts)
    ? d.subAccounts
        .filter((item): item is Data => typeof item === 'object' && item !== null)
        .map((item) => ({ name: str(item.name) ?? '', number: str(item.number) ?? '', balance: num(item.balance) ?? 0 }))
    : // 예전 버전에서 만든 통장: 잔액 하나를 계좌 하나로 옮긴다.
      [{ name: '기본 계좌', number: '', balance: num(d.balance) ?? 0 }];
  return {
    id,
    name,
    subAccounts,
    balanceDate: date(d.balanceDate),
    annualRate: num(d.annualRate) ?? 0,
    memo: str(d.memo) ?? '',
    createdAt: num(d.createdAt) ?? 0,
  };
}

export function toSavingsGoal(id: string, d: Data): SavingsGoal | null {
  const accountId = str(d.accountId);
  const name = str(d.name);
  const dueDate = date(d.dueDate);
  if (accountId === null || name === null || dueDate === null) return null;
  return {
    id,
    accountId,
    name,
    type: oneOf(GOAL_TYPES, d.type, 'ONE_TIME'),
    amount: num(d.amount) ?? 0,
    dueDate,
    intervalMonths: num(d.intervalMonths) ?? 12,
    createdAt: num(d.createdAt) ?? 0,
  };
}

export function toSettings(d: Data | undefined): AppSettings {
  return { manualUsdKrw: num(d?.manualUsdKrw) };
}

function dateMap(v: unknown): Map<string, number> {
  const result = new Map<string, number>();
  if (typeof v !== 'object' || v === null) return result;
  for (const [key, value] of Object.entries(v)) {
    const d = date(key);
    const n = num(value);
    if (d !== null && n !== null) result.set(d, n);
  }
  return result;
}

/** marketData 컬렉션 문서들(price_<키>, fx_USD) → 시세 스냅샷. */
export function toMarketSnapshot(docs: readonly { id: string; data: Data }[]): MarketSnapshot {
  const prices = new Map<string, PriceHistory>();
  let usdKrw = new Map<string, number>();
  for (const { id, data } of docs) {
    if (id === FX_DOC) {
      usdKrw = dateMap(data.rates);
    } else if (id.startsWith(PRICE_PREFIX)) {
      const latest = num(data.latest);
      prices.set(id.slice(PRICE_PREFIX.length), {
        closes: dateMap(data.closes),
        latest: latest === null ? null : { price: latest, date: date(data.latestDate) },
      });
    }
  }
  return { prices, usdKrw };
}

/** 가장 최근 "최신가" 저장일 (웹 화면의 시세 기준 표시용). */
export function latestQuoteDate(snapshot: MarketSnapshot): string | null {
  let result: string | null = null;
  for (const history of snapshot.prices.values()) {
    const d = history.latest?.date ?? null;
    if (d !== null && (result === null || d > result)) result = d;
  }
  return result;
}
