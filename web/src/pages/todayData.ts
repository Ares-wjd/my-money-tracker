import { useMemo } from 'react';
import { formatShortDate, today } from '../core/dates';
import { todayChange, type TodayChange } from '../core/todayChange';
import type { AppData } from '../data/DataContext';

/** 홈 카드와 상세 페이지가 함께 쓰는 오늘 변동. */
export function useTodayChange(data: AppData): TodayChange | null {
  return useMemo(() => todayChange(data.summary, data.market, today()), [data]);
}

/** 기준 글: 폰 앱이 저장한 시세 날짜. */
export const todayBasis = (quoteDate: string | null): string => (quoteDate ? `시세 ${formatShortDate(quoteDate)} 기준` : '');
