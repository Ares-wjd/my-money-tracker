// 배당 화면 (v2.13): 계좌 칩 · 12칸 달력 (달을 누르면 날짜 달력) · 연도별 · 종목별 배당수익률. 폰 DividendScreen 과 같은 내용.
import { useMemo, useState } from 'react';
import { Card, profitClass } from '../components/ui';
import { KindTag, divPct, divWon, useDividends } from '../components/Dividend';
import { dividendChange, dividendMonths, dividendTotals, dividendYears, dividendYields, type DividendItem } from '../core/dividends';
import { plusDays, plusMonths, today as todayOf } from '../core/dates';
import { rate } from '../core/format';
import { allChipsShown, toggleAllChips, toggleChip } from '../core/todayChange';
import { useAppData } from '../data/DataContext';

const pad = (n: number) => String(n).padStart(2, '0');
const monthKey = (y: number, m: number) => `${y}-${pad(m)}`;
const shiftMonth = (key: string, n: number) => plusMonths(`${key}-01`, n).slice(0, 7);
const sumKrw = (xs: readonly DividendItem[]) => xs.reduce((s, d) => s + d.amountKrw, 0);

export function DividendPage() {
  const data = useAppData();
  const day = todayOf();
  const accountIds = useMemo(() => data.summary.accounts.map((a) => a.account.id), [data]);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const ids = useMemo(() => (hidden.size === 0 ? null : new Set(accountIds.filter((id) => !hidden.has(id)))), [accountIds, hidden]);
  const div = useDividends(data, ids, day);
  const all = useMemo(() => [...div.received, ...div.planned], [div]);
  const [year, setYear] = useState(Number(day.slice(0, 4)));
  const [month, setMonth] = useState<string | null>(null);
  const accountName = (id: string) => data.summary.accounts.find((a) => a.account.id === id)?.account.name ?? '';
  const allShown = allChipsShown(accountIds, hidden);

  const chips = (
    <Card>
      <div className="today-filter-row">
        <span className="muted small">계좌</span>
        <div className="choices" role="group" aria-label="계좌">
          <button aria-pressed={allShown} className={allShown ? 'choice on' : 'choice'} onClick={() => setHidden(toggleAllChips(accountIds, hidden))}>전체</button>
          {accountIds.map((id) => (
            <button key={id} aria-pressed={!hidden.has(id)} className={hidden.has(id) ? 'choice' : 'choice on'} onClick={() => setHidden(toggleChip(id, hidden))}>
              {accountName(id)}
            </button>
          ))}
        </div>
      </div>
    </Card>
  );

  if (all.length === 0) {
    return (
      <div className="stack">
        {chips}
        <Card title="배당"><p className="muted">배당 기록이 없습니다. 계좌에서 배당을 기록하면 달·해·종목별로 모아 보고, 폰 앱에 한투 연결이 있으면 배당 일정도 받아 옵니다.</p></Card>
      </div>
    );
  }

  return (
    <div className="stack">
      {chips}
      {month === null ? (
        <YearGrid items={all} year={year} day={day} onYear={setYear} onMonth={setMonth} />
      ) : (
        <MonthCalendar items={all.filter((d) => d.month === month)} month={month} day={day} onMonth={(m) => { setMonth(m); setYear(Number(m.slice(0, 4))); }} onBack={() => setMonth(null)} />
      )}
      <YearsCard items={all} day={day} />
      <YieldsCard received={div.received} ids={ids} day={day} />
    </div>
  );
}

function YearGrid({ items, year, day, onYear, onMonth }: { items: DividendItem[]; year: number; day: string; onYear: (y: number) => void; onMonth: (m: string) => void }) {
  const months = dividendMonths(items, year);
  const totals = dividendTotals(items.filter((d) => d.month.startsWith(`${year}-`)));
  const max = Math.max(1, ...months.map((t) => t.received));
  const thisMonth = day.slice(0, 7);
  return (
    <section className="card">
      <div className="div-nav">
        <button className="icon-nav" aria-label="이전 해" onClick={() => onYear(year - 1)}>‹</button>
        <div className="div-nav-title">
          <strong>{year}년</strong>
          <span className="muted small">받음 {divWon(totals.received)} · 예정 {divWon(totals.confirmed + totals.estimated)}</span>
        </div>
        <button className="icon-nav" aria-label="다음 해" onClick={() => onYear(year + 1)}>›</button>
      </div>
      <div className="div-grid">
        {months.map((t, i) => {
          const key = monthKey(year, i + 1);
          const planned = t.confirmed + t.estimated;
          const cls = ['div-tile', t.received <= 0 && planned > 0 ? 'planned-only' : '', key === thisMonth ? 'now' : ''].join(' ');
          return (
            <button key={key} className={cls} style={t.received > 0 ? { ['--shade' as string]: String(0.08 + 0.32 * (t.received / max)) } : undefined} onClick={() => onMonth(key)}>
              <span className="muted small">{i + 1}월{key === thisMonth ? ' · 이번 달' : ''}</span>
              <strong>{t.received + planned > 0 ? divWon(t.received + planned).replace(/원$/, '') : '-'}</strong>
              {planned > 0 && (
                <span className="div-tile-sub">
                  {[t.confirmed > 0 ? `확정 ${divWon(t.confirmed).replace(/원$/, '')}` : null, t.estimated > 0 ? `추정 ${divWon(t.estimated).replace(/원$/, '')}` : null].filter(Boolean).join(' · ')}
                </span>
              )}
            </button>
          );
        })}
      </div>
      <p className="muted small">칸이 진할수록 많이 받은 달 · 점선 칸은 예정만 있는 달 · 칸을 누르면 그 달 날짜 달력</p>
    </section>
  );
}

function MonthCalendar({ items, month, day, onMonth, onBack }: { items: DividendItem[]; month: string; day: string; onMonth: (m: string) => void; onBack: () => void }) {
  const totals = dividendTotals(items);
  const planned = totals.confirmed + totals.estimated;
  const [y, m] = [Number(month.slice(0, 4)), Number(month.slice(5, 7))];
  const first = `${month}-01`;
  const length = Number(plusDays(shiftMonth(month, 1) + '-01', -1).slice(8, 10));
  const lead = new Date(Date.UTC(y, m - 1, 1)).getUTCDay();
  const dated = new Map<string, DividendItem[]>();
  for (const d of items) if (d.kind !== 'ESTIMATED' && d.date !== null) dated.set(d.date, [...(dated.get(d.date) ?? []), d]);
  const undated = items.filter((d) => d.kind === 'ESTIMATED' || d.date === null);
  const cells: (string | null)[] = [...Array(lead).fill(null), ...Array.from({ length }, (_, i) => plusDays(first, i))];
  const sorted = [...items].sort((a, b) => ((a.date ?? `${month}-99`) < (b.date ?? `${month}-99`) ? -1 : 1));
  return (
    <section className="card">
      <button className="text-button" onClick={onBack}>‹ {y}년 12칸 달력으로</button>
      <div className="div-nav">
        <button className="icon-nav" aria-label="이전 달" onClick={() => onMonth(shiftMonth(month, -1))}>‹</button>
        <div className="div-nav-title">
          <strong>{y}년 {m}월</strong>
          <span className="muted small">
            {[totals.received > 0 ? `받음 ${divWon(totals.received)}` : null, planned > 0 ? `예정 ${divWon(planned)} (${[totals.confirmed > 0 ? `확정 ${divWon(totals.confirmed)}` : null, totals.estimated > 0 ? `추정 ${divWon(totals.estimated)}` : null].filter(Boolean).join(' · ')})` : null].filter(Boolean).join(' · ') || '배당 없음'}
          </span>
        </div>
        <button className="icon-nav" aria-label="다음 달" onClick={() => onMonth(shiftMonth(month, 1))}>›</button>
      </div>
      <div className="div-week muted small">{['일', '월', '화', '수', '목', '금', '토'].map((w) => <span key={w}>{w}</span>)}</div>
      <div className="div-days">
        {cells.map((d, i) => {
          if (d === null) return <span key={`e${i}`} />;
          const list = dated.get(d) ?? [];
          const wd = i % 7;
          const cls = ['div-day', list.some((x) => x.kind === 'RECEIVED') ? 'got' : list.length ? 'confirmed' : '', d === day ? 'today' : '', wd === 0 ? 'sun' : wd === 6 ? 'sat' : ''].join(' ');
          return (
            <span key={d} className={cls}>
              {Number(d.slice(8, 10))}
              {list.length > 0 && <small>{divWon(sumKrw(list)).replace(/원$/, '')}</small>}
            </span>
          );
        })}
      </div>
      {undated.map((d, i) => (
        <div key={`u${i}`} className={d.kind === 'ESTIMATED' ? 'div-undated est' : 'div-undated'}>
          <strong className="small">{d.kind === 'ESTIMATED' ? `추정 · 약 ${Number(d.date!.slice(5, 7))}/${Number(d.date!.slice(8, 10))}` : '확정 · 지급일 모름'}</strong>
          <span className="div-undated-name">{d.holding.name}</span>
          <strong>{d.kind === 'ESTIMATED' ? '약 ' : ''}{divWon(d.amountKrw)}</strong>
        </div>
      ))}
      {sorted.length > 0 && (
        <>
          <h3 className="div-list-title">{m}월 목록</h3>
          {sorted.map((d, i) => (
            <div key={`l${i}`} className="div-row">
              <span className="muted small div-row-date">{d.date ? `${Number(d.date.slice(5, 7))}/${Number(d.date.slice(8, 10))}` : '날짜 모름'}</span>
              <span className="div-row-name">{d.holding.name}</span>
              <KindTag kind={d.kind} />
              <strong>{divWon(d.amountKrw)}</strong>
            </div>
          ))}
        </>
      )}
    </section>
  );
}

function YearsCard({ items, day }: { items: DividendItem[]; day: string }) {
  const years = dividendYears(items, day);
  const max = Math.max(1, ...years.map(([, t]) => t.received + t.confirmed + t.estimated));
  const thisYear = Number(day.slice(0, 4));
  const now = years.find(([y]) => y === thisYear)?.[1];
  const last = years.find(([y]) => y === thisYear - 1)?.[1];
  const change = now && last ? dividendChange(now.received + now.confirmed + now.estimated, last.received) : null;
  return (
    <Card title="연도별 배당">
      <p className="muted small">세후 · 받은 배당은 받은 날 환율, 예정은 지금 환율</p>
      <div className="compare-list">
        {[...years].reverse().map(([y, t]) => (
          <div key={y} className="compare-row">
            <span className={y === thisYear ? 'strong' : ''}>{y}</span>
            <span className="compare-track div-year-track">
              <span className="div-received" style={{ width: `${(t.received / max) * 100}%` }} />
              <span className="div-planned" style={{ width: `${(t.confirmed / max) * 100}%` }} />
              <span className="div-est" style={{ width: `${(t.estimated / max) * 100}%` }} />
            </span>
            <span className="num strong">{divWon(t.received + t.confirmed + t.estimated)}</span>
          </div>
        ))}
      </div>
      {now && (
        <p className="small">
          올해 {divWon(now.received)} 받음 + 예정 {divWon(now.confirmed + now.estimated)}
          {change !== null && <> · 작년보다 <strong className={profitClass(change)}>{rate(change)}</strong></>} · 월평균 {divWon((now.received + now.confirmed + now.estimated) / 12)}
        </p>
      )}
    </Card>
  );
}

function YieldsCard({ received, ids, day }: { received: DividendItem[]; ids: ReadonlySet<string> | null; day: string }) {
  const data = useAppData();
  const report = useMemo(() => dividendYields(data.summary, received, day, ids), [data, received, day, ids]);
  return (
    <Card title="종목별 (최근 12개월)">
      <div className="div-yield-boxes">
        <div><span className="muted small">배당수익률 · 현재가</span><strong>{divPct(report.totalOnPrice)}</strong></div>
        <div className="mine"><span className="small">배당수익률 · 내 평단</span><strong>{divPct(report.totalOnCost)}</strong></div>
      </div>
      {report.items.length === 0 ? (
        <p className="muted">최근 12개월에 받은 배당이 없습니다.</p>
      ) : (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr><th>종목</th><th className="num">받은 배당</th><th className="num">현재가</th><th className="num">내 평단</th></tr>
            </thead>
            <tbody>
              {report.items.map((y) => (
                <tr key={y.key}>
                  <td>{y.holding.name}</td>
                  <td className="num strong">{divWon(y.last12Krw)}</td>
                  <td className="num">{divPct(y.yieldOnPrice)}</td>
                  <td className="num div-cost">{divPct(y.yieldOnCost)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="muted small">현재가 = 최근 12개월 받은 배당 ÷ 지금 평가금 · 내 평단 = ÷ 매수 원금(보유 수량 × 평균단가) · 세후, 같은 환율로 계산 · 다 판 종목은 "-"</p>
    </Card>
  );
}
