import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { decimal, rate, signedWon } from '../core/format';
import { changeLevel, todayLabel, todayTiles, treemap, topGainers, topLosers, type TodayChange, type TodayItem } from '../core/todayChange';
import { TickerBadge, profitClass, type TickerGroup } from './ui';

/** 국내·해외·환율 몫 (폰과 같은 색: 국내 = 국내 주식 색, 해외 = 해외 주식 색, 환율 = 금현물 색). */
function parts(change: TodayChange): { label: string; detail: string | null; amount: number; group: TickerGroup }[] {
  return [
    { label: '국내 주식·ETF', detail: null, amount: change.domesticKrw, group: 'KR_STOCK' },
    { label: '해외 주식', detail: '미국 시장 기준', amount: change.foreignKrw, group: 'US_STOCK' },
    {
      label: '환율',
      detail: change.fxFrom !== null && change.fxTo !== null ? `${decimal(change.fxFrom, 2)} → ${decimal(change.fxTo, 2)}` : '오늘 환율 발표 전',
      amount: change.fxKrw,
      group: 'GOLD',
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

/** 국내·해외·환율이 기여한 몫 (크기 비율 막대). */
export function ContributionBar({ change }: { change: TodayChange }) {
  const items = parts(change).filter((p) => Math.abs(p.amount) >= 0.5);
  const total = items.reduce((s, p) => s + Math.abs(p.amount), 0);
  return (
    <div className="today-bar" aria-hidden="true">
      {total > 0 && items.map((p) => <span key={p.label} className={`ticker-${p.group}`} style={{ flexGrow: Math.max(Math.abs(p.amount) / total, 0.01) }} />)}
    </div>
  );
}

export function ContributionRows({ change }: { change: TodayChange }) {
  return (
    <div className="today-parts">
      {parts(change).map((p) => (
        <div key={p.label} className="today-part">
          <span className={`legend-swatch ticker-${p.group}`} aria-hidden="true" />
          <span>{p.label}</span>
          <span className="muted small today-part-detail">{p.detail}</span>
          <b className={profitClass(p.amount)}>{signedWon(p.amount)}</b>
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
          <TickerBadge code={i.holding.code} name={i.holding.name} market={i.holding.market} assetType={i.holding.assetType} />
          <b className={profitClass(i.rate)}>{rate(i.rate)}</b>
        </div>
      ))}
    </div>
  );
}

/** 홈의 "오늘 변동" 카드 (누르면 상세 페이지). */
export function TodayCard({ change, basis }: { change: TodayChange; basis: string }) {
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
          <ContributionBar change={change} />
          <ContributionRows change={change} />
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
          const body = r.w >= 40 && r.h >= 30 && (
            <>
              <b>{item === null ? '기타' : todayLabel(item)}</b>
              {r.h >= 50 && item !== null && <span>{item.rate === null ? '시세 없음' : rate(item.rate)}</span>}
            </>
          );
          const className = `today-tile level-${level === null ? 'none' : level < 0 ? `m${-level}` : level}`;
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
