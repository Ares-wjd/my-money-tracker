import { useEffect, useMemo, useState } from 'react';
import { NO_FILTER, todayChange, type TodayChange, type TodayFilter } from '../core/todayChange';
import type { AppData } from '../data/DataContext';

/** 지금 시각 (30초마다 바뀜): 시장 상태가 시간에 따라 바뀌므로. */
export function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(id);
  }, []);
  return now;
}

/** 홈 카드(전체)와 상세 페이지(필터)가 함께 쓰는 시장별 변동. */
export function useTodayChange(data: AppData, nowMs: number, filter: TodayFilter = NO_FILTER): TodayChange | null {
  return useMemo(() => todayChange(data.summary, data.market, nowMs, filter), [data, nowMs, filter]);
}
