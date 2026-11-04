import { Fragment, useMemo, useState } from 'react';
import { today } from '../core/dates';
import { rate, signedWon, shortWon } from '../core/format';
import { lengthText, longTermReturn, monthlyReturns, yearToDate, yearlyReturns, type PeriodResult } from '../core/periodReturns';
import { allChipsShown, toggleAllChips, toggleChip } from '../core/todayChange';
import { useAppData } from '../data/DataContext';
import { Card, Row, profitClass } from '../components/ui';
import { usePeriodSeries } from './periodData';
import { BENCHMARK_INDICES, BENCHMARK_LABEL, benchmarkPeriod, compareText, simulate, type BenchmarkIndex } from '../core/benchmark';
import { usePeriodBenchmark } from '../components/Benchmark';

const monthLabel = (r: PeriodResult) => `${Number(r.from.slice(5, 7))}월`;
const monthTitle = (r: PeriodResult) => `${r.from.slice(0, 4)}년 ${Number(r.from.slice(5, 7))}월`;
const signedShort = (v: number) => (v > 0.5 ? '+' : '') + shortWon(v);

/** 기간별 수익: 계좌 칩 · 올해·연평균 · 최근 12개월 월별 막대 · 연도별 표. */
export function PeriodPage() {
  const data = useAppData();
  const accountIds = useMemo(() => data.summary.accounts.map((a) => a.account.id), [data]);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const ids = useMemo(() => (hidden.size === 0 ? null : new Set(accountIds.filter((id) => !hidden.has(id)))), [accountIds, hidden]);
  const series = usePeriodSeries(data, ids);
  const day = today();
  const months = useMemo(() => (series ? monthlyReturns(series, day) : []), [series, day]);
  const years = useMemo(() => (series ? yearlyReturns(series, day) : []), [series, day]);
  const [picked, setPicked] = useState<string | null>(null);
  const [dotIndex, setDotIndex] = usePeriodBenchmark();
  const sims = useMemo(
    () => new Map(BENCHMARK_INDICES.map((i) => [i, series && series.length ? simulate(series, i, data.market, day) : null] as const)),
    [series, data.market, day],
  );
  const indexRate = (i: BenchmarkIndex, r: PeriodResult | null | undefined): number | null =>
    r ? benchmarkPeriod(sims.get(i) ?? null, i, data.market, r.from, r.to)?.rate ?? null : null;
  const indexLine = (r: PeriodResult | null | undefined): string | null => {
    const rates = BENCHMARK_INDICES.map((i) => [i, indexRate(i, r)] as const);
    return rates.every(([, v]) => v === null) ? null : rates.map(([i, v]) => `${BENCHMARK_LABEL[i]} ${rate(v)}`).join(' · ');
  };
  const accountName = (id: string) => data.summary.accounts.find((a) => a.account.id === id)?.account.name ?? '';

  const all = allChipsShown(accountIds, hidden);
  const chips = (
    <Card>
      <div className="today-filter-row">
        <span className="muted small">계좌</span>
        <div className="choices" role="group" aria-label="계좌">
          <button aria-pressed={all} className={all ? 'choice on' : 'choice'} onClick={() => setHidden(toggleAllChips(accountIds, hidden))}>전체</button>
          {accountIds.map((id) => (
            <button key={id} aria-pressed={!hidden.has(id)} className={hidden.has(id) ? 'choice' : 'choice on'} onClick={() => setHidden(toggleChip(id, hidden))}>
              {accountName(id)}
            </button>
          ))}
        </div>
      </div>
    </Card>
  );

  if (series === null) {
    return (
      <div className="stack">
        {chips}
        <Card title="기간별 수익"><p className="muted"><span className="spinner" aria-hidden="true" />그래프 일별 값을 계산하는 중입니다. 계산이 끝나면 보여드립니다.</p></Card>
      </div>
    );
  }
  if (series.length === 0) {
    return (
      <div className="stack">
        {chips}
        <Card title="기간별 수익"><p className="muted">{hidden.size === accountIds.length ? '계좌를 고르세요.' : '기록이 없습니다.'}</p></Card>
      </div>
    );
  }

  const ytd = yearToDate(series, day);
  const long = longTermReturn(series, day);
  // 점 = 그 달 내 수익률이 지수와 같았다면의 수익금 (막대와 같은 금액 눈금)
  const dots = months.map((m) => {
    if (dotIndex === null || m.rate === null) return null;
    const r = indexRate(dotIndex, m);
    return r === null ? null : r * m.baseKrw;
  });
  const max = Math.max(1, ...months.map((m) => Math.abs(m.profitKrw)), ...dots.map((d) => Math.abs(d ?? 0)));
  const ytdRates = BENCHMARK_INDICES.map((i) => [i, indexRate(i, ytd)] as [BenchmarkIndex, number | null]);
  const compareRows: [string, number | null, string][] = [['내 자산', ytd?.rate ?? null, 'bg-mine'], ...ytdRates.map(([i, v]) => [BENCHMARK_LABEL[i], v, `bg-${i}`] as [string, number | null, string])];
  const compareMax = Math.max(1e-9, ...compareRows.map(([, v]) => Math.abs(v ?? 0)));
  const compareLine = compareText(ytd?.rate ?? null, ytdRates);
  const selected = months.find((m) => m.from === picked) ?? months[months.length - 1];

  return (
    <div className="stack">
      {chips}
      <Card title="기간별 수익">
        <p className="muted small">배당 포함 · 그래프 일별 평가금·투자금으로 계산 (넣고 뺀 돈은 수익에서 뺌)</p>
        <div className="stats">
          <div className="stat">
            <span className="stat-label">올해 수익</span>
            <span className={`stat-value ${profitClass(ytd?.profitKrw)}`}>{ytd ? signedWon(ytd.profitKrw) : '-'}</span>
            <span className={`small ${profitClass(ytd?.rate)}`}>{rate(ytd?.rate)}</span>
          </div>
          <div className="stat">
            <span className="stat-label">{long?.annual !== null && long ? '연평균 수익률' : `기간 수익률${long ? ` (${lengthText(long.days)})` : ''}`}</span>
            <span className={`stat-value ${profitClass(long?.annual ?? long?.period)}`}>{rate(long?.annual ?? long?.period)}</span>
            <span className="muted small">{long?.annual !== null && long ? '넣고 뺀 날짜까지 반영 (XIRR)' : '1년이 안 되어 연으로 바꾸지 않음'}</span>
          </div>
        </div>
      </Card>

      <Card title="올해 수익률 비교">
        {ytd === null || ytdRates.every(([, v]) => v === null) ? (
          <p className="muted">지수 시세가 아직 없습니다. 한투 연결이 있는 폰 앱이 과거 시세와 함께 받아 둡니다.</p>
        ) : (
          <>
            <div className="compare-list">
              {compareRows.map(([label, v, cls], k) => (
                <div key={label} className="compare-row">
                  <span className={k === 0 ? 'strong' : ''}>{label}</span>
                  <span className="compare-track"><span className={`compare-fill ${cls}`} style={{ width: `${(Math.abs(v ?? 0) / compareMax) * 100}%` }} /></span>
                  <span className={`num ${profitClass(v)}${k === 0 ? ' strong' : ''}`}>{rate(v)}</span>
                </div>
              ))}
            </div>
            {compareLine && <p>{compareLine}</p>}
            <p className="muted small">같은 날 같은 돈을 넣고 뺐다고 보고 같은 방식(수정 디츠)으로 계산 · S&amp;P500·나스닥은 원화 환산 · 지수는 배당 제외 · 막대 길이는 크기만</p>
          </>
        )}
      </Card>

      <Card title="월별 수익 (최근 12개월)">
        <div className="period-bars" role="group" aria-label="월별 수익">
          {months.map((m, k) => (
            <button key={m.from} className={`period-bar${m === selected ? ' on' : ''}`} onClick={() => setPicked(m.from)} aria-label={`${monthTitle(m)} ${signedWon(m.profitKrw)}`}>
              <span className="period-bar-track">
                <span className={`period-bar-fill ${m.profitKrw >= 0 ? 'up-bg' : 'down-bg'}`} style={{ height: `${(Math.abs(m.profitKrw) / max) * 50}%`, [m.profitKrw >= 0 ? 'bottom' : 'top']: '50%' }} />
                {dots[k] !== null && dotIndex !== null && <span className={`period-dot bg-${dotIndex}`} style={{ top: `${50 - (dots[k]! / max) * 46}%` }} />}
              </span>
              <span className="period-bar-label">{monthLabel(m)}</span>
            </button>
          ))}
        </div>
        <div className="choices" role="radiogroup" aria-label="막대 위에 보일 지수">
          {BENCHMARK_INDICES.map((i) => (
            <button key={i} role="radio" aria-checked={dotIndex === i} className={dotIndex === i ? 'choice on' : 'choice'} onClick={() => setDotIndex(i)}>
              <span className={`text-${i}`} aria-hidden="true">●</span> {BENCHMARK_LABEL[i]}
            </button>
          ))}
          <button role="radio" aria-checked={dotIndex === null} className={dotIndex === null ? 'choice on' : 'choice'} onClick={() => setDotIndex(null)}>끄기</button>
        </div>
        {dotIndex !== null && <p className="muted small">점: 그 달 수익률이 {BENCHMARK_LABEL[dotIndex]}와 같았다면의 수익금</p>}
        {selected && (
          <div className="period-pick">
            <strong>{monthTitle(selected)}{selected.to === day ? ' (오늘까지)' : ''}</strong>
            <Row label="수익금" value={signedWon(selected.profitKrw)} valueClass={profitClass(selected.profitKrw)} />
            <Row label="수익률" value={rate(selected.rate)} valueClass={profitClass(selected.rate)} />
            <Row label="넣은 돈 (뺀 돈은 −)" value={signedWon(selected.netFlowKrw)} />
            {indexLine(selected) && <span className="muted small">{indexLine(selected)}</span>}
          </div>
        )}
      </Card>

      <Card title="연도별 수익">
        <div className="table-scroll">
        <table className="table period-table">
          <thead>
            <tr><th>연도</th><th className="num">수익금</th><th className="num">수익률</th><th className="num">넣은 돈</th></tr>
          </thead>
          <tbody>
            {[...years].reverse().map((y) => {
              const line = indexLine(y);
              return (
                <Fragment key={y.from}>
                  <tr>
                    <td>{y.from.slice(0, 4)}{y.to === day ? ' (올해)' : ''}</td>
                    <td className={`num ${profitClass(y.profitKrw)}`}>{signedShort(y.profitKrw)}</td>
                    <td className={`num ${profitClass(y.rate)}`}>{rate(y.rate)}</td>
                    <td className="num">{signedShort(y.netFlowKrw)}</td>
                  </tr>
                  {line && <tr><td colSpan={4} className="year-index">{line}</td></tr>}
                </Fragment>
              );
            })}
          </tbody>
        </table>
        </div>
      </Card>
    </div>
  );
}
