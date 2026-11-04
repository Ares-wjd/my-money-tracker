// 시장별 변동 (v2.10, 폰 TodayChangeViews 와 같은 화면): 시장마다 지금(또는 마지막) 일봉 하나, 상태 표시, 쉬는 시장은 회색.
import { Link } from 'react-router-dom';
import { decimal, rate, signedWon } from '../core/format';
import { MARKET_GROUP_LABEL, PHASE_LABEL, dayText, kstOf, nextText, stateChip, timeline, type MarketPhase } from '../core/marketClock';
import { isLiveChange, isStale, todayLabel, type MarketMove, type TodayChange } from '../core/todayChange';
import type { MarketCalendar } from '../model';
import { TickerBadge, profitClass } from './ui';

/** 상태 표시: "● 프리마켓", "휴장 · 주말". 색은 styles.css 의 .phase-*. */
export function MarketChip({ move }: { move: MarketMove }) {
  const active = ['PRE', 'REGULAR', 'AFTER', 'DAY', 'FX_OPEN'].includes(move.state.phase);
  return (
    <span className={`phase-chip phase-${active ? move.state.phase : 'IDLE'}`}>
      {active && <span className="phase-dot" aria-hidden="true" />}
      {stateChip(move.state)}
    </span>
  );
}

/** 시장 줄 아래 설명: "11/3(월) 일봉 확정 · 내일 08:00 프리마켓", 환율은 "1,402.5원 (+2.0원) · …". */
export function marketSubText(move: MarketMove, nowMs: number): string {
  const date = move.candleDate ?? move.state.candleDate;
  let head: string;
  if (move.state.group === 'FX') {
    head = move.fxTo === null ? '환율 없음' : `${decimal(move.fxTo, 1)}원` + (move.fxFrom === null ? '' : ` (${move.fxTo - move.fxFrom > 0 ? '+' : ''}${decimal(move.fxTo - move.fxFrom, 1)}원)`);
  } else if (move.changeKrw === null) head = '시세 없음';
  else if (isStale(move)) head = `시세 갱신 전 · ${dayText(date)} 일봉`;
  else if (move.state.phase === 'CLOSED') head = `${dayText(date)} 일봉 확정`;
  else head = `${dayText(date)} 일봉`;
  return [head, nextText(move.state, nowMs)].filter(Boolean).join(' · ');
}

const valueClass = (move: MarketMove) => (move.live ? profitClass(move.changeKrw) : 'muted');

/** 지금 시각 글: "11/3(월) 21:00". */
export function nowText(nowMs: number): string {
  const k = kstOf(nowMs);
  return `${dayText(k.date)} ${String(Math.floor(k.minutes / 60)).padStart(2, '0')}:${String(k.minutes % 60).padStart(2, '0')}`;
}

/** 합계와 비율 (모든 시장이 쉬면 회색). */
export function TodayTotal({ change }: { change: TodayChange }) {
  const cls = isLiveChange(change) ? profitClass(change.totalKrw) : 'muted';
  return (
    <div className="today-total">
      <span className={`today-amount ${cls}`}>{signedWon(change.totalKrw)}</span>
      {change.rate !== null && <span className={`today-rate ${cls}`}>{rate(change.rate)}</span>}
      <span className="muted small today-caption">{isLiveChange(change) ? '각 시장의 최근 일봉 합계' : '모든 시장이 쉬는 중 · 최근 일봉 합계'}</span>
    </div>
  );
}

/** 시장 한 줄: 배지 · 이름 · 상태 · 설명 · 변동 금액·비율. */
function MarketRow({ move, nowMs }: { move: MarketMove; nowMs: number }) {
  return (
    <div className={move.live ? 'market-row' : 'market-row idle'}>
      <span className={`market-badge phase-${move.live ? move.state.phase : 'IDLE'}`}>{move.state.group}</span>
      <span className="market-main">
        <span className="market-head">
          <span className="market-name">{MARKET_GROUP_LABEL[move.state.group]}</span>
          <MarketChip move={move} />
        </span>
        <span className="muted small">{marketSubText(move, nowMs)}</span>
      </span>
      <span className="market-value">
        <span className={`strong ${valueClass(move)}`}>{move.changeKrw === null ? '-' : signedWon(move.changeKrw)}</span>
        {move.rate !== null && <span className={`small ${valueClass(move)}`}>{rate(move.rate)}</span>}
      </span>
    </div>
  );
}

/** 홈의 시장별 변동 카드 (항상 전체 기준). */
export function TodayCard({ change, nowMs }: { change: TodayChange; nowMs: number }) {
  return (
    <Link to="/today" className="card today-card" aria-label="시장별 변동 자세히">
      <div className="card-head">
        <h2>시장별 변동</h2>
        <span className="muted small">{nowText(nowMs)} ›</span>
      </div>
      <TodayTotal change={change} />
      {change.markets.map((m) => (
        <MarketRow key={m.state.group} move={m} nowMs={nowMs} />
      ))}
    </Link>
  );
}

const SEGMENT_PHASES: MarketPhase[] = ['PRE', 'REGULAR', 'AFTER', 'DAY', 'FX_OPEN'];

/** 시장별 비중 막대 (변동 금액 크기 비율, 쉬는 시장은 회색). */
export function ShareBar({ change }: { change: TodayChange }) {
  const parts = change.markets.filter((m) => Math.abs(m.changeKrw ?? 0) > 0.5);
  const total = parts.reduce((s, m) => s + Math.abs(m.changeKrw!), 0);
  if (total <= 0) return null;
  return (
    <div className="share-bar" aria-hidden="true">
      {parts.map((m) => (
        <span key={m.state.group} className={m.live ? `seg-${m.state.phase}` : 'seg-IDLE'} style={{ width: `${(Math.abs(m.changeKrw!) / total) * 100}%` }} />
      ))}
    </div>
  );
}

/** "지금 열린 장" 시간표: 오늘 0~24시에 시장별 구간, 지금 시각 선. */
export function SessionTimeline({ change, nowMs, calendar }: { change: TodayChange; nowMs: number; calendar: MarketCalendar }) {
  const now = kstOf(nowMs);
  const segments = timeline(now.date, calendar);
  const pct = (m: number) => `${(m / 1440) * 100}%`;
  return (
    <div className="timeline">
      {change.markets.map((m) => (
        <div key={m.state.group} className="timeline-lane">
          <span className="timeline-label">{MARKET_GROUP_LABEL[m.state.group]}</span>
          <span className="timeline-track">
            {segments.filter((s) => s.group === m.state.group).map((s) => (
              <span key={`${s.phase}-${s.start}`} className={`timeline-seg seg-${s.phase}`} style={{ left: pct(s.start), width: pct(s.end - s.start) }} title={`${PHASE_LABEL[s.phase]}`} />
            ))}
            <span className="timeline-now" style={{ left: pct(now.minutes) }} />
          </span>
        </div>
      ))}
      <div className="timeline-axis"><span>0시</span><span>6시</span><span>12시</span><span>18시</span><span>24시</span></div>
      <div className="timeline-legend">
        {SEGMENT_PHASES.map((p) => (
          <span key={p}><span className={`legend-box seg-${p}`} />{p === 'FX_OPEN' ? '외환시장' : PHASE_LABEL[p]}</span>
        ))}
        <span><span className="legend-now" />지금 {nowText(nowMs).split(' ')[1]}</span>
      </div>
    </div>
  );
}

/** 시장 카드 (상세): 머리줄 · 설명 · 종목 목록 (변동 금액 크기 순, 쉬는 시장은 회색). */
export function MarketSection({ move, nowMs }: { move: MarketMove; nowMs: number }) {
  const lines = [marketSubText(move, nowMs)];
  if (move.regularKrw !== null && Math.abs(move.extendedKrw ?? 0) > 0.5) lines.push(`정규 ${signedWon(move.regularKrw)} · 장외 ${signedWon(move.extendedKrw!)}`);
  if (move.state.group === 'US') lines.push('오늘 환율로 원화 환산 (환율 효과는 아래에 따로)');
  if (move.state.group === 'FX') lines.push('해외 종목 기준가 평가금 + 달러 예수금에 대한 환율 효과 · 서울 외환시장 09:00 ~ 다음 날 02:00');
  const items = [...move.items].sort((a, b) => Math.abs(b.changeKrw ?? -1) - Math.abs(a.changeKrw ?? -1));
  return (
    <section className={move.live ? 'card market-section' : 'card market-section idle'}>
      <div className="market-row head">
        <span className={`market-badge phase-${move.live ? move.state.phase : 'IDLE'}`}>{move.state.group}</span>
        <span className="market-main">
          <span className="market-head">
            <h2 className="market-name">{MARKET_GROUP_LABEL[move.state.group]}</h2>
            <MarketChip move={move} />
          </span>
        </span>
        <span className="market-value">
          <span className={`strong ${valueClass(move)}`}>{move.changeKrw === null ? '-' : signedWon(move.changeKrw)}</span>
          {move.rate !== null && <span className={`small ${valueClass(move)}`}>{rate(move.rate)}</span>}
        </span>
      </div>
      <p className="muted small market-lines">{lines.map((l) => <span key={l}>{l}</span>)}</p>
      {items.map((i) => (
        <Link key={i.key} to={`/holdings/${i.holding.id}`} className="list-row">
          <TickerBadge code={i.holding.code} name={i.holding.name} market={i.holding.market} assetType={i.holding.assetType} />
          <span className="list-main"><span className="list-title">{todayLabel(i)}</span></span>
          <span className="list-end">
            {i.changeKrw === null ? (
              <span className="list-sub">시세 없음</span>
            ) : (
              <>
                <span className={`list-value ${i.live ? profitClass(i.changeKrw) : 'muted'}`}>{signedWon(i.changeKrw)}</span>
                <span className={`list-sub ${i.live ? profitClass(i.rate) : 'muted'}`}>{rate(i.rate)}</span>
              </>
            )}
          </span>
        </Link>
      ))}
    </section>
  );
}
