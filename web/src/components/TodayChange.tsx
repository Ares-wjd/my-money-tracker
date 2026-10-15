import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { decimal, rate, signedWon } from '../core/format';
import { changeLevel, todayLabel, todayTiles, treemap, topGainers, topLosers, type TodayChange, type TodayItem } from '../core/todayChange';
import { profitClass, tickerGroup } from './ui';

/** 계좌 색: 비중 탭과 같은 범주 팔레트를 계좌 순서대로 (폰과 같은 값). */
const accountColor = (index: number) => `var(--series-${(Math.max(index, 0) % 8) + 1})`;

interface Part {
  key: string;
  label: string;
  detail: string | null;
  /** null = 시세 없음 */
  amount: number | null;
  color: string;
}

/** 계좌별 + 환율 몫. accountOrder 는 전체 계좌 순서 (필터와 상관없이 같은 계좌는 같은 색). */
function parts(change: TodayChange, accountOrder: readonly string[]): Part[] {
  return [
    ...change.accounts.map((a) => ({
      key: a.account.id,
      label: a.account.name,
      detail: null,
      amount: a.changeKrw,
      color: accountColor(accountOrder.indexOf(a.account.id)),
    })),
    {
      key: '__fx',
      label: '환율',
      detail: change.fxFrom !== null && change.fxTo !== null ? `${decimal(change.fxFrom, 2)} → ${decimal(change.fxTo, 2)}` : '오늘 환율 없음',
      amount: change.fxKrw,
      color: 'var(--fx-part)',
    },
  ];
}

export function TodayTotal({ change }: { change: TodayChange }) {
  return (
    <div className="today-total">
      <span className={`today-amount ${profitClass(change.totalKrw)}`}>{signedWon(change.totalKrw)}</span>
      <span className={`today-rate ${profitClass(change.rate)}`}>{rate(change.rate)}</span>
    </div>
  );
}

/** 계좌별 + 환율이 기여한 몫 (크기 비율 막대). */
export function ContributionBar({ change, accountOrder }: { change: TodayChange; accountOrder: readonly string[] }) {
  const items = parts(change, accountOrder).filter((p) => p.amount !== null && Math.abs(p.amount) >= 0.5);
  const total = items.reduce((s, p) => s + Math.abs(p.amount!), 0);
  return (
    <div className="today-bar" aria-hidden="true">
      {total > 0 && items.map((p) => <span key={p.key} style={{ background: p.color, flexGrow: Math.max(Math.abs(p.amount!) / total, 0.01) }} />)}
    </div>
  );
}

/** 계좌마다 한 줄 + 환율 한 줄 (모두 더하면 합계). */
export function ContributionRows({ change, accountOrder }: { change: TodayChange; accountOrder: readonly string[] }) {
  return (
    <div className="today-parts">
      {parts(change, accountOrder).map((p) => (
        <div key={p.key} className="today-part">
          <span className="legend-swatch" style={{ background: p.color }} aria-hidden="true" />
          <span className="today-part-name">{p.label}</span>
          <span className="muted small today-part-detail">{p.detail}</span>
          {p.amount === null ? <span className="muted small">시세 없음</span> : <b className={profitClass(p.amount)}>{signedWon(p.amount)}</b>}
        </div>
      ))}
    </div>
  );
}

function Movers({ title, items }: { title: string; items: TodayItem[] }) {
  return (
    <div className="today-movers">
      <span className="muted small">{title}</span>
      {items.length === 0 && <span className="muted small">없음</span>}
      {items.map((i) => (
        <div key={i.key} className="today-mover">
          <span className={`legend-swatch ticker-${tickerGroup(i.holding.market, i.holding.assetType)}`} aria-hidden="true" />
          <span className="today-mover-name">{todayLabel(i)}</span>
          <b className={profitClass(i.rate)}>{rate(i.rate)}</b>
        </div>
      ))}
    </div>
  );
}

/** 홈의 "오늘 변동" 카드 (누르면 상세 페이지). */
export function TodayCard({ change, basis, accountOrder }: { change: TodayChange; basis: string; accountOrder: readonly string[] }) {
  const hasQuotes = change.items.some((i) => i.rate !== null);
  return (
    <Link to="/today" className="card today-card">
      <div className="card-head">
        <h2>오늘 변동</h2>
        <span className="muted small">{basis} ›</span>
      </div>
      <TodayTotal change={change} />
      {hasQuotes && (
        <>
          <ContributionBar change={change} accountOrder={accountOrder} />
          <ContributionRows change={change} accountOrder={accountOrder} />
          <div className="today-movers-row">
            <Movers title="많이 오른 종목" items={topGainers(change)} />
            <Movers title="많이 내린 종목" items={topLosers(change)} />
          </div>
        </>
      )}
      <span className="muted small">현재가 ÷ 전일 종가 기준 · 직접 입력 가격(펀드 등)은 제외 · 누르면 종목별로 보기</span>
    </Link>
  );
}

const TILE_HEIGHT = 300;

/** 종목별 타일: 칸 크기 = 평가금 비중, 색 = 오늘 등락. */
export function TodayTiles({ change }: { change: TodayChange }) {
  const box = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = box.current;
    if (el === null) return;
    const update = () => setWidth(el.clientWidth);
    update();
    const observer = new ResizeObserver(update);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  const tiles = todayTiles(change.items);
  const rects = treemap(tiles.map((t) => t.weight), width, TILE_HEIGHT);
  return (
    <div ref={box} className="today-tiles" style={{ height: TILE_HEIGHT }} role="img" aria-label="종목별 오늘 등락 타일">
      {width > 0 &&
        tiles.map(({ item }, i) => {
          const r = rects[i];
          const level = item === null ? null : changeLevel(item.rate);
          const style = { left: r.x, top: r.y, width: r.w, height: r.h };
          // 좁은 칸은 글자를 줄여 등락률이 잘리지 않게, 큰 칸은 이름을 2줄까지
          const compact = r.w < 72;
          const body = r.w >= 40 && r.h >= 30 && (
            <>
              <b className={r.h >= 80 && !compact ? 'two-lines' : ''}>{item === null ? '기타' : todayLabel(item)}</b>
              {r.h >= 50 && item !== null && <span>{item.rate === null ? '시세 없음' : rate(item.rate)}</span>}
            </>
          );
          const className = `today-tile${compact ? ' compact' : ''} level-${level === null ? 'none' : level < 0 ? `m${-level}` : level}`;
          return item === null ? (
            <div key="rest" className={className} style={style} title="기타">
              {body}
            </div>
          ) : (
            <Link key={item.key} to={`/holdings/${item.holding.id}`} className={className} style={style} title={item.holding.name}>
              {body}
            </Link>
          );
        })}
    </div>
  );
}
