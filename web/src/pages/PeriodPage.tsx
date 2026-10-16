import { useMemo, useState } from 'react';
import { today } from '../core/dates';
import { rate, signedWon, shortWon } from '../core/format';
import { lengthText, longTermReturn, monthlyReturns, yearToDate, yearlyReturns, type PeriodResult } from '../core/periodReturns';
import { allChipsShown, toggleAllChips, toggleChip } from '../core/todayChange';
import { useAppData } from '../data/DataContext';
import { Card, Row, profitClass } from '../components/ui';
import { usePeriodSeries } from './periodData';

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
  const max = Math.max(1, ...months.map((m) => Math.abs(m.profitKrw)));
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

      <Card title="월별 수익 (최근 12개월)">
        <div className="period-bars" role="group" aria-label="월별 수익">
          {months.map((m) => (
            <button key={m.from} className={`period-bar${m === selected ? ' on' : ''}`} onClick={() => setPicked(m.from)} aria-label={`${monthTitle(m)} ${signedWon(m.profitKrw)}`}>
              <span className="period-bar-track">
                <span className={`period-bar-fill ${m.profitKrw >= 0 ? 'up-bg' : 'down-bg'}`} style={{ height: `${(Math.abs(m.profitKrw) / max) * 50}%`, [m.profitKrw >= 0 ? 'bottom' : 'top']: '50%' }} />
              </span>
              <span className="period-bar-label">{monthLabel(m)}</span>
            </button>
          ))}
        </div>
        {selected && (
          <div className="period-pick">
            <strong>{monthTitle(selected)}{selected.to === day ? ' (오늘까지)' : ''}</strong>
            <Row label="수익금" value={signedWon(selected.profitKrw)} valueClass={profitClass(selected.profitKrw)} />
            <Row label="수익률" value={rate(selected.rate)} valueClass={profitClass(selected.rate)} />
            <Row label="넣은 돈 (뺀 돈은 −)" value={signedWon(selected.netFlowKrw)} />
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
            {[...years].reverse().map((y) => (
              <tr key={y.from}>
                <td>{y.from.slice(0, 4)}{y.to === day ? ' (올해)' : ''}</td>
                <td className={`num ${profitClass(y.profitKrw)}`}>{signedShort(y.profitKrw)}</td>
                <td className={`num ${profitClass(y.rate)}`}>{rate(y.rate)}</td>
                <td className="num">{signedShort(y.netFlowKrw)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      </Card>
    </div>
  );
}
