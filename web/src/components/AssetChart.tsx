import { useEffect, useMemo, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { CHART_INTERVALS, CHART_INTERVAL_LABEL, hasEarlier, pointDates, series, type ChartInterval, type ChartPoint } from '../core/chart';
import { formatDate, formatShortDate, today as todayOf } from '../core/dates';
import { signedWon, won } from '../core/format';
import type { AppData } from '../data/DataContext';
import { profitClass } from './ui';

/** 축 눈금용 짧은 금액: 1.2억, 1,278만, 5,000 */
export function compactWon(value: number): string {
  const abs = Math.abs(value);
  const sign = value < 0 ? '-' : '';
  if (abs >= 1e8) return `${sign}${(abs / 1e8).toFixed(abs >= 1e9 ? 0 : 1).replace(/\.0$/, '')}억`;
  if (abs >= 1e4) return `${sign}${Math.round(abs / 1e4).toLocaleString('en-US')}만`;
  return `${sign}${Math.round(abs).toLocaleString('en-US')}`;
}

/** 위아래 여유를 둔 보기 좋은 눈금 (3~5개). */
function niceTicks(min: number, max: number): number[] {
  if (min === max) {
    const pad = Math.max(1, Math.abs(max) * 0.1);
    min -= pad;
    max += pad;
  }
  const rough = (max - min) / 4;
  const mag = Math.pow(10, Math.floor(Math.log10(rough)));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= rough) ?? rough;
  const start = Math.floor(min / step) * step;
  const ticks: number[] = [];
  for (let v = start; v <= max + step * 0.001; v += step) ticks.push(v);
  if (ticks[ticks.length - 1] < max) ticks.push(ticks[ticks.length - 1] + step);
  return ticks;
}

const HEIGHT = 260;
const PAD = { top: 12, right: 12, bottom: 28, left: 56 };

export function AssetChart({ data }: { data: AppData }) {
  const [interval, setChartInterval] = useState<ChartInterval>('MONTH');
  const [accountId, setAccountId] = useState<string | null>(null);
  const [rangeFactor, setRangeFactor] = useState(1);
  const [selected, setSelected] = useState<number | null>(null);
  const [showTable, setShowTable] = useState(false);
  // 그래프 칸이 다시 그려질 때(표 ↔ 그래프 전환 등)마다 폭을 다시 잰다.
  const [box, setBox] = useState<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(800);

  useEffect(() => {
    if (!box) return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(280, Math.floor(entry.contentRect.width))));
    observer.observe(box);
    return () => observer.disconnect();
  }, [box]);

  const today = todayOf();
  const { points, earlier } = useMemo(() => {
    const target = data.records.filter((r) => accountId === null || r.accountId === accountId || r.toAccountId === accountId);
    if (target.length === 0) return { points: [] as ChartPoint[], earlier: false };
    const first = target.reduce((m, r) => (r.date < m ? r.date : m), target[0].date);
    const dates = pointDates(interval, today, first, rangeFactor);
    return {
      points: series(dates, today, data.accounts, data.holdings, data.records, data.market, data.settings.manualUsdKrw, accountId),
      earlier: hasEarlier(interval, today, first, rangeFactor),
    };
  }, [data, interval, accountId, rangeFactor, today]);

  useEffect(() => setSelected(null), [interval, accountId, rangeFactor]);

  const index = selected ?? points.length - 1;
  const point = points[index];
  const values = points.flatMap((p) => [p.valueKrw, p.investedKrw]);
  const ticks = values.length ? niceTicks(Math.min(...values), Math.max(...values)) : [0, 1];
  const lo = ticks[0];
  const hi = ticks[ticks.length - 1];
  const plotW = width - PAD.left - PAD.right;
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const x = (i: number) => PAD.left + (points.length <= 1 ? plotW : (i / (points.length - 1)) * plotW);
  const y = (v: number) => PAD.top + plotH - ((v - lo) / (hi - lo || 1)) * plotH;
  const path = (pick: (p: ChartPoint) => number) => points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(pick(p)).toFixed(1)}`).join(' ');

  const onPointer = (e: PointerEvent<SVGSVGElement>) => {
    if (points.length === 0) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const px = ((e.clientX - rect.left) / rect.width) * width;
    const i = points.length <= 1 ? 0 : Math.round(((px - PAD.left) / plotW) * (points.length - 1));
    setSelected(Math.min(points.length - 1, Math.max(0, i)));
  };
  const onKey = (e: KeyboardEvent<SVGSVGElement>) => {
    if (e.key === 'ArrowLeft') setSelected(Math.max(0, index - 1));
    else if (e.key === 'ArrowRight') setSelected(Math.min(points.length - 1, index + 1));
    else return;
    e.preventDefault();
  };
  const xLabels = points.length <= 1 ? [0] : [0, Math.floor((points.length - 1) / 2), points.length - 1].filter((v, i, a) => a.indexOf(v) === i);

  return (
    <section className="card chart-card">
      <div className="card-head">
        <h2>자산 추이</h2>
        <button className="text-button" onClick={() => setShowTable(!showTable)}>{showTable ? '그래프로 보기' : '표로 보기'}</button>
      </div>
      <div className="chart-filters">
        <div className="segmented" role="radiogroup" aria-label="보기 단위">
          {CHART_INTERVALS.map((iv) => (
            <button key={iv} role="radio" aria-checked={interval === iv} className={interval === iv ? 'on' : ''} onClick={() => { setChartInterval(iv); setRangeFactor(1); }}>
              {CHART_INTERVAL_LABEL[iv]}
            </button>
          ))}
        </div>
        {data.accounts.length > 1 && (
          <div className="choices" role="radiogroup" aria-label="계좌">
            {[null, ...data.accounts.map((a) => a.id)].map((id) => (
              <button key={id ?? 'all'} role="radio" aria-checked={accountId === id} className={accountId === id ? 'choice on' : 'choice'} onClick={() => setAccountId(id)}>
                {id === null ? '전체' : data.lookup.account(id)?.name}
              </button>
            ))}
          </div>
        )}
      </div>

      {points.length === 0 ? (
        <p className="muted empty">기록이 생기면 그래프가 그려집니다.</p>
      ) : (
        <>
          <div className="chart-readout" aria-live="polite">
            <span className="muted small">{formatDate(point.date)}{index === points.length - 1 ? ' (현재가 기준)' : ''}</span>
            <span className="readout-main">
              <span className="readout-value">{won(point.valueKrw)}</span>
              <span className={`readout-sub ${profitClass(point.valueKrw - point.investedKrw)}`}>{signedWon(point.valueKrw - point.investedKrw)}</span>
            </span>
            <span className="muted small">투자금 {won(point.investedKrw)} · 누적 배당 {won(point.dividendsKrw)}</span>
          </div>
          <div className="legend">
            <span className="legend-item"><svg width="22" height="8" aria-hidden="true"><line x1="1" y1="4" x2="21" y2="4" className="line-value" /></svg>평가금</span>
            <span className="legend-item"><svg width="22" height="8" aria-hidden="true"><line x1="1" y1="4" x2="21" y2="4" className="line-invested" /></svg>투자금</span>
          </div>
          {showTable ? (
            <div className="table-wrap chart-table">
              <table className="table">
                <thead>
                  <tr><th>날짜</th><th className="num">평가금</th><th className="num">투자금</th><th className="num">수익</th><th className="num">누적 배당</th></tr>
                </thead>
                <tbody>
                  {[...points].reverse().map((p) => (
                    <tr key={p.date}>
                      <td>{formatDate(p.date)}</td>
                      <td className="num">{won(p.valueKrw)}</td>
                      <td className="num">{won(p.investedKrw)}</td>
                      <td className={`num ${profitClass(p.valueKrw - p.investedKrw)}`}>{signedWon(p.valueKrw - p.investedKrw)}</td>
                      <td className="num">{won(p.dividendsKrw)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div ref={setBox} className="chart-box">
              <svg
                width={width}
                height={HEIGHT}
                viewBox={`0 0 ${width} ${HEIGHT}`}
                role="img"
                aria-label={`자산 추이 그래프, ${points.length}개 시점. 왼쪽·오른쪽 화살표로 시점을 옮길 수 있습니다.`}
                tabIndex={0}
                onPointerMove={onPointer}
                onPointerDown={onPointer}
                onPointerLeave={() => setSelected(null)}
                onKeyDown={onKey}
              >
                {ticks.map((t) => (
                  <g key={t}>
                    <line x1={PAD.left} x2={width - PAD.right} y1={y(t)} y2={y(t)} className="grid" />
                    <text x={PAD.left - 8} y={y(t)} className="axis" textAnchor="end" dominantBaseline="middle">{compactWon(t)}</text>
                  </g>
                ))}
                {xLabels.map((i) => (
                  <text key={i} x={x(i)} y={HEIGHT - 8} className="axis" textAnchor={i === 0 && points.length > 1 ? 'start' : i === points.length - 1 ? 'end' : 'middle'}>
                    {formatShortDate(points[i].date)}
                  </text>
                ))}
                {points.length > 1 && <path d={path((p) => p.investedKrw)} className="line-invested" fill="none" />}
                {points.length > 1 && <path d={path((p) => p.valueKrw)} className="line-value" fill="none" />}
                <line x1={x(index)} x2={x(index)} y1={PAD.top} y2={PAD.top + plotH} className="crosshair" />
                <circle cx={x(index)} cy={y(point.investedKrw)} r={4} className="dot-invested" />
                <circle cx={x(index)} cy={y(point.valueKrw)} r={5} className="dot-value" />
              </svg>
            </div>
          )}
          <div className="chart-foot">
            <span className="muted small">과거 종가·환율은 폰 앱이 받아 둔 만큼만 반영됩니다 (없는 날은 그 전 값 사용).</span>
            {earlier && <button className="text-button" onClick={() => setRangeFactor(rangeFactor + 1)}>이전 기간 더 보기</button>}
          </div>
        </>
      )}
    </section>
  );
}
