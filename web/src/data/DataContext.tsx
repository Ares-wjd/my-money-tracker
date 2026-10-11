import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import { today } from '../core/dates';
import { summarizeSavings, type SavingsSummary } from '../core/goals';
import { currentPrice, dailyChangeRate, latestUsdKrw, tradeUsdKrw } from '../core/market';
import { compareRecords, krwHoldingView, summarize, type DisplayCurrency, type HoldingSort, type HoldingValuation, type KrwHoldingView, type PortfolioSummary } from '../core/portfolio';
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
  /** 원화 보기의 해외 종목 숫자 (매매일 환율로 환차익 반영). 매매일 환율이 하나라도 없으면 null. */
  krwView: (valuation: HoldingValuation) => KrwHoldingView | null;
  /** 오늘 수익률 (전일 종가 대비). 자동 시세를 쓰지 않거나 전일 종가가 없으면 null. */
  todayRate: (valuation: HoldingValuation) => number | null;
}

interface DataState {
  uid: string;
  data: AppData | null;
  errors: string[];
  displayCurrency: DisplayCurrency;
  setDisplayCurrency: (mode: DisplayCurrency) => void;
  holdingSort: HoldingSort;
  setHoldingSort: (sort: HoldingSort) => void;
}

const DataContext = createContext<DataState | null>(null);
const DISPLAY_KEY = 'holding_currency';
const SORT_KEY = 'holding_sort';

function readHoldingSort(): HoldingSort {
  try {
    const v = localStorage.getItem(SORT_KEY);
    return v === 'TOTAL_RETURN' || v === 'TODAY_RETURN' ? v : 'VALUE';
  } catch {
    return 'VALUE';
  }
}

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
  const [holdingSort, setSort] = useState<HoldingSort>(readHoldingSort);

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
        summarizeSavings(
          account,
          raw.savingsGoals.filter((g) => g.accountId === account.id),
          day,
          raw.savingsGoalGroups.filter((g) => g.accountId === account.id),
        ),
      ),
      usdKrw,
      usdKrwDate: fx?.[0] ?? null,
      quoteDate: latestQuoteDate(raw.market),
      linked: linkedAccountIds(raw.records),
      lookup: { holding: (id) => holdingById.get(id), account: (id) => accountById.get(id) },
      recordsOf: (accountId) =>
        raw.records.filter((r) => r.accountId === accountId || r.toAccountId === accountId).sort(newestFirst),
      recordsOfHolding: (holdingId) => raw.records.filter((r) => r.holdingId === holdingId).sort(newestFirst),
      krwView: (valuation) => krwHoldingView(valuation, raw.records, usdKrw, (d) => tradeUsdKrw(raw.market, d, day)),
      todayRate: (valuation) => dailyChangeRate(valuation.holding, raw.market),
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

  const setHoldingSort = (sort: HoldingSort) => {
    setSort(sort);
    try {
      localStorage.setItem(SORT_KEY, sort);
    } catch {
      // 저장할 수 없는 환경이면 이번 화면에서만 적용한다.
    }
  };

  return (
    <DataContext.Provider value={{ uid, data, errors, displayCurrency, setDisplayCurrency, holdingSort, setHoldingSort }}>
      {children}
    </DataContext.Provider>
  );
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
