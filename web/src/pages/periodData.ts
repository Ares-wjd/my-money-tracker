import { useMemo } from 'react';
import { today } from '../core/dates';
import { flowSeriesFor, longTermReturn, periodSummaryText, yearToDate, type FlowPoint } from '../core/periodReturns';
import type { AppData } from '../data/DataContext';

/** ids 계좌(null 이면 전체)의 일별 평가금·투자금 합. 그래프 일별 값을 계산 중이면 null. */
export function usePeriodSeries(data: AppData, ids: ReadonlySet<string> | null = null): FlowPoint[] | null {
  return useMemo(
    () => (data.charts === null ? null : flowSeriesFor(data.summary, data.records, data.charts, today(), ids)),
    [data, ids],
  );
}

/** "올해 ▲8.20% (+234.0만원) · 연평균 ▲11.40%" (계산 중이거나 기록이 없으면 빈 글). */
export function periodLine(series: FlowPoint[] | null): string {
  if (series === null || series.length === 0) return '';
  const day = today();
  return periodSummaryText(yearToDate(series, day), longTermReturn(series, day));
}
