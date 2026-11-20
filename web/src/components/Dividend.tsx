// 배당 (v2.13, 폰 DividendViews 와 같은 화면): 홈 카드 · 12칸 달력 → 날짜 달력 · 연도별 · 종목별 배당수익률.
import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { dividendChange, dividendHome, plannedDividends, receivedDividends, type DividendHome, type DividendItem, type DividendKind } from '../core/dividends';
import { today as todayOf } from '../core/dates';
import { decimal, rateShort, shortWon } from '../core/format';
import type { AppData } from '../data/DataContext';
import { profitClass } from './ui';

export interface DividendData {
  received: DividendItem[];
  planned: DividendItem[];
}

/** 받은 배당 + 예정 배당 (ids 계좌, null 이면 전체). */
export function useDividends(data: AppData, ids: ReadonlySet<string> | null, day: string = todayOf()): DividendData {
  return useMemo(
    () => ({
      received: receivedDividends(data.holdings, data.records, data.market, day, data.summary.usdKrw, ids),
      planned: plannedDividends(data.summary, data.records, data.market, day, ids),
    }),
    [data, ids, day],
  );
}

export const divWon = (v: number) => shortWon(v);
export const divPct = (r: number | null) => (r === null ? '-' : `${decimal(r * 100, 1)}%`);

/** 비교 두 줄 (v2.14): "작년 ○○" 아래 줄에 짧은 비율. planned 이고 지금이 0원이면 "아직 예정 없음". */
function CompareLine({ label, before, now, planned = false }: { label: string; before: number; now: number; planned?: boolean }) {
  const change = dividendChange(now, before);
  return (
    <>
      <span className="muted small">{label} {divWon(before)}</span>
      {planned && now <= 0.5 ? (
        <span className="muted small">아직 예정 없음</span>
      ) : (
        change !== null && <strong className={`small ${profitClass(change)}`}>{rateShort(change)}</strong>
      )}
    </>
  );
}

/** 홈 배당 카드 (전체 계좌): 올해 받은 배당·작년 같은 기간, 다음 달 예정·작년 같은 달, 1~12월 올해·작년 막대. */
export function DividendCard({ home }: { home: DividendHome }) {
  const nextMonth = Number(home.nextMonth.slice(5, 7));
  const max = Math.max(1, ...home.months.map((t) => t.received + t.confirmed + t.estimated), ...home.lastYearMonths);
  const h = (v: number) => `${(v / max) * 100}%`;
  return (
    <Link to="/dividends" className="card div-card" aria-label="배당 자세히">
      <div className="card-head">
        <h2>배당</h2>
        <span className="muted small">세후 · 원화 ›</span>
      </div>
      <div className="div-stats">
        <div>
          <span className="muted small">올해 받은 배당</span>
          <span className="div-amount">{divWon(home.thisYear)}</span>
          <CompareLine label="작년 같은 기간" before={home.lastYearSamePeriod} now={home.thisYear} />
        </div>
        <div>
          <span className="muted small">{nextMonth}월 예정</span>
          <span className="div-amount">{divWon(home.nextMonthPlanned)}</span>
          <CompareLine label={`작년 ${nextMonth}월`} before={home.lastYearNextMonth} now={home.nextMonthPlanned} planned />
        </div>
      </div>
      <div className="div-mini" aria-hidden="true">
        {home.months.map((t, i) => (
          <span key={i} className="div-mini-month">
            <span className="div-mini-bar div-stack" style={{ height: h(t.received + t.confirmed + t.estimated) }}>
              <span className="div-planned" style={{ flex: t.confirmed + t.estimated }} />
              <span className="div-received" style={{ flex: t.received }} />
            </span>
            <span className="div-mini-bar div-last" style={{ height: h(home.lastYearMonths[i]) }} />
          </span>
        ))}
      </div>
      <div className="div-mini-labels" aria-hidden="true">
        {home.months.map((_, i) => <span key={i}>{i + 1}</span>)}
      </div>
      <div className="legend">
        <span className="legend-item"><span className="legend-swatch div-received" />올해 받음</span>
        <span className="legend-item"><span className="legend-swatch div-planned" />올해 예정</span>
        <span className="legend-item"><span className="legend-swatch div-last" />작년 같은 달</span>
      </div>
    </Link>
  );
}

/** 홈에 놓는 카드: 배당 기록이나 예정이 있을 때만. */
export function HomeDividendCard({ data }: { data: AppData }) {
  const day = todayOf();
  const div = useDividends(data, null, day);
  const home = useMemo(() => dividendHome(div.received, div.planned, day), [div, day]);
  if (div.received.length === 0 && div.planned.length === 0) return null;
  return <DividendCard home={home} />;
}

export function KindTag({ kind }: { kind: DividendKind }) {
  const text = kind === 'RECEIVED' ? '받음' : kind === 'CONFIRMED' ? '확정 · 한투' : '추정';
  return <span className={`div-tag div-tag-${kind}`}>{text}</span>;
}
