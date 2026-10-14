import { useMemo } from 'react';
import { formatShortDate, today } from '../core/dates';
import { NO_FILTER, todayChange, type TodayChange, type TodayFilter } from '../core/todayChange';
import type { AppData } from '../data/DataContext';

/** 홈 카드(전체)와 상세 페이지(필터)가 함께 쓰는 오늘 변동. */
export function useTodayChange(data: AppData, filter: TodayFilter = NO_FILTER): TodayChange | null {
  return useMemo(() => todayChange(data.summary, data.market, today(), filter), [data, filter]);
}

/** 계좌 색 순서 (전체 계좌 순서, 필터와 상관없이 같은 계좌는 같은 색). */
export const accountOrderOf = (data: AppData): string[] => data.summary.accounts.map((a) => a.account.id);

/** 기준 글: 폰 앱이 저장한 시세 날짜. */
export const todayBasis = (quoteDate: string | null): string => (quoteDate ? `시세 ${formatShortDate(quoteDate)} 기준` : '');
