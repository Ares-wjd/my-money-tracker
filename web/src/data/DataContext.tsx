import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import { today } from '../core/dates';
import { summarizeSavings, type SavingsSummary } from '../core/goals';
import { currentPrice, latestUsdKrw } from '../core/market';
import { compareRecords, summarize, type DisplayCurrency, type PortfolioSummary } from '../core/portfolio';
import { linkedAccountIds, type Holding, type InvestmentAccount, type Record } from '../model';
import type { RecordLookup } from '../records/recordText';
import { latestQuoteDate } from './mappers';
import { useUserData, type UserData } from './useUserData';

/** 화면들이 함께 쓰는 데이터와 계산 결과 (폰 앱의 PortfolioData 에 해당). */
export interface AppData extends UserData {
  summary: PortfolioSummary;
  savings: SavingsSummary[];
  /** 계산에 쓰는 환율: 폰이 저장한 최신 환율, 없으면 직접 입력한 환율 */
  usdKrw: number | null;
  usdKrwDate: string | null;
  quoteDate: string | null;
  linked: Set<string>;
  lookup: RecordLookup;
  recordsOf: (accountId: string) => Record[];
  recordsOfHolding: (holdingId: string) => Record[];
}

interface DataState {
  data: AppData | null;
  errors: string[];
  displayCurrency: DisplayCurrency;
  setDisplayCurrency: (mode: DisplayCurrency) => void;
}

const DataContext = createContext<DataState | null>(null);
const DISPLAY_KEY = 'holding_currency';

function readDisplayCurrency(): DisplayCurrency {
  try {
    return localStorage.getItem(DISPLAY_KEY) === 'KRW' ? 'KRW' : 'FOREIGN';
  } catch {
    return 'FOREIGN';
  }
}

export function DataProvider({ uid, children }: { uid: string; children: ReactNode }) {
  const { data: raw, errors } = useUserData(uid);
  const [displayCurrency, setMode] = useState<DisplayCurrency>(readDisplayCurrency);

  const data = useMemo<AppData | null>(() => {
    if (raw === null) return null;
    const fx = latestUsdKrw(raw.market);
    const usdKrw = fx?.[1] ?? raw.settings.manualUsdKrw;
    const day = today();
    const holdingById = new Map<string, Holding>(raw.holdings.map((h) => [h.id, h]));
    const accountById = new Map<string, InvestmentAccount>(raw.accounts.map((a) => [a.id, a]));
    const newestFirst = (a: Record, b: Record) => compareRecords(b, a);
    return {
      ...raw,
      summary: summarize(raw.accounts, raw.holdings, raw.records, usdKrw, (h) => currentPrice(h, raw.market)),
      savings: raw.savingsAccounts.map((account) =>
        summarizeSavings(account, raw.savingsGoals.filter((g) => g.accountId === account.id), day),
      ),
      usdKrw,
      usdKrwDate: fx?.[0] ?? null,
      quoteDate: latestQuoteDate(raw.market),
      linked: linkedAccountIds(raw.records),
      lookup: { holding: (id) => holdingById.get(id), account: (id) => accountById.get(id) },
      recordsOf: (accountId) =>
        raw.records.filter((r) => r.accountId === accountId || r.toAccountId === accountId).sort(newestFirst),
      recordsOfHolding: (holdingId) => raw.records.filter((r) => r.holdingId === holdingId).sort(newestFirst),
    };
  }, [raw]);

  const setDisplayCurrency = (mode: DisplayCurrency) => {
    setMode(mode);
    try {
      localStorage.setItem(DISPLAY_KEY, mode);
    } catch {
      // 저장할 수 없는 환경(사생활 보호 창 등)이면 이번 화면에서만 적용한다.
    }
  };

  return <DataContext.Provider value={{ data, errors, displayCurrency, setDisplayCurrency }}>{children}</DataContext.Provider>;
}

export function useData(): DataState {
  const state = useContext(DataContext);
  if (!state) throw new Error('DataProvider 안에서만 쓸 수 있습니다.');
  return state;
}

/** 데이터를 받은 뒤에만 그려지는 화면(Layout 안쪽)에서 쓴다. */
export function useAppData(): AppData {
  const { data } = useData();
  if (data === null) throw new Error('데이터를 받기 전에는 쓸 수 없습니다.');
  return data;
}
