import { useState } from 'react';
import { Card, TickerBadge, Warning } from '../components/ui';
import { WonField } from '../components/form';
import { allocationView, buyOnly, isHighlighted, keyOf, rebalance, type AllocationRow, type AllocationView, type TradeSuggestion } from '../core/allocation';
import { decimal, manwon, parseDecimal, usd, won } from '../core/format';
import { useAppData } from '../data/DataContext';
import { AllocationForm } from '../forms/AllocationForm';
import type { Holding } from '../model';
import type { AllocationTarget } from '../core/allocation';

type Ring = 'current' | 'target' | 'compare';
const RINGS: readonly Ring[] = ['current', 'target', 'compare'];
const RING_LABEL: { [r in Ring]: string } = { current: '실제', target: '목표', compare: '비교' };
const SELECTED_KEY = 'allocation_selected';
const RING_KEY = 'allocation_ring';
/** 색을 따로 주는 종목 수. 7번째부터는 "기타" 로 묶는다 (검증한 팔레트의 앞 6색). */
const MAX_COLORS = 6;

const read = (key: string): string | null => {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
};
const write = (key: string, value: string) => {
  try {
    localStorage.setItem(key, value);
  } catch {
    // 저장 안 되는 환경은 무시
  }
};

const pct = (w: number) => `${decimal(w * 100, 1)}%`;
/** 색: 종목 순서(목표 큰 순)로 고정 — 실제/목표를 바꿔도 같은 종목은 같은 색. */
const colorOf = (index: number) => (index < MAX_COLORS ? `var(--series-${index + 1})` : 'var(--series-other)');

interface Slice {
  key: string;
  name: string;
  color: string;
  weight: number;
}

function slicesOf(rows: readonly AllocationRow[], weight: (r: AllocationRow) => number): Slice[] {
  const slices: Slice[] = [];
  let other = 0;
  rows.forEach((r, i) => {
    if (i < MAX_COLORS) slices.push({ key: r.key, name: r.name, color: colorOf(i), weight: weight(r) });
    else other += weight(r);
  });
  if (other > 0) slices.push({ key: '__other', name: '기타', color: colorOf(MAX_COLORS), weight: other });
  return slices.filter((s) => s.weight > 0);
}

/** 범례: 종목 순서(목표 큰 순) 그대로, 색 6개 + 기타. */
function legendOf(rows: readonly AllocationRow[]): { key: string; name: string; color: string }[] {
  const items = rows.slice(0, MAX_COLORS).map((r, i) => ({ key: r.key, name: r.name, color: colorOf(i) }));
  if (rows.length > MAX_COLORS) items.push({ key: '__other', name: `기타 ${rows.length - MAX_COLORS}종목`, color: colorOf(MAX_COLORS) });
  return items;
}

function arc(cx: number, cy: number, r0: number, r1: number, a0: number, a1: number): string {
  // 한 바퀴 전체는 반씩 나눠 그린다
  if (a1 - a0 >= Math.PI * 2 - 1e-6) return arc(cx, cy, r0, r1, a0, a0 + Math.PI) + arc(cx, cy, r0, r1, a0 + Math.PI, a1);
  const p = (r: number, a: number) => `${cx + r * Math.sin(a)} ${cy - r * Math.cos(a)}`;
  const large = a1 - a0 > Math.PI ? 1 : 0;
  return `M${p(r1, a0)} A${r1} ${r1} 0 ${large} 1 ${p(r1, a1)} L${p(r0, a1)} A${r0} ${r0} 0 ${large} 0 ${p(r0, a0)} Z`;
}

function RingPaths({ slices, r0, r1, hovered, onHover }: { slices: Slice[]; r0: number; r1: number; hovered: string | null; onHover: (k: string | null) => void }) {
  const total = slices.reduce((s, x) => s + x.weight, 0);
  let a = 0;
  return (
    <>
      {slices.map((s) => {
        const a0 = a;
        a += total > 0 ? (s.weight / total) * Math.PI * 2 : 0;
        return (
          <path
            key={s.key}
            d={arc(110, 110, r0, r1, a0, a)}
            fill={s.color}
            className={hovered !== null && hovered !== s.key ? 'dim' : ''}
            onMouseEnter={() => onHover(s.key)}
            onMouseLeave={() => onHover(null)}
          >
            <title>{`${s.name} ${pct(s.weight)}`}</title>
          </path>
        );
      })}
    </>
  );
}

function Donut({ view, ring }: { view: AllocationView; ring: Ring }) {
  const [hovered, setHovered] = useState<string | null>(null);
  const current = slicesOf(view.rows, (r) => r.currentWeight);
  const target = slicesOf(view.rows, (r) => r.targetWeight);
  const hoveredRow = view.rows.find((r) => r.key === hovered);
  const hoveredOther = hovered === '__other';
  const otherRows = view.rows.slice(MAX_COLORS);
  const sum = (rows: AllocationRow[], f: (r: AllocationRow) => number) => rows.reduce((s, r) => s + f(r), 0);

  let centerLabel = ring === 'target' ? '목표' : '합계';
  let centerValue = ring === 'target' ? `${decimal(view.rows.reduce((s, r) => s + r.targetWeight, 0) * 100, 0)}%` : manwonShort(view.totalKrw);
  if (hoveredRow || hoveredOther) {
    centerLabel = hoveredRow ? hoveredRow.name : '기타';
    const cw = hoveredRow ? hoveredRow.currentWeight : sum(otherRows, (r) => r.currentWeight);
    const tw = hoveredRow ? hoveredRow.targetWeight : sum(otherRows, (r) => r.targetWeight);
    centerValue = ring === 'target' ? pct(tw) : ring === 'current' ? pct(cw) : `${pct(cw)} / ${pct(tw)}`;
  }

  return (
    <div className="donut-wrap">
      <svg className="donut" viewBox="0 0 220 220" role="img" aria-label={`비중 도넛 그래프 (${RING_LABEL[ring]})`}>
        {ring === 'compare' ? (
          <>
            <RingPaths slices={current} r0={78} r1={108} hovered={hovered} onHover={setHovered} />
            <RingPaths slices={target} r0={50} r1={72} hovered={hovered} onHover={setHovered} />
          </>
        ) : (
          <RingPaths slices={ring === 'current' ? current : target} r0={66} r1={108} hovered={hovered} onHover={setHovered} />
        )}
        <text x="110" y="104" textAnchor="middle" className="donut-center-label">{centerLabel.length > 10 ? centerLabel.slice(0, 9) + '…' : centerLabel}</text>
        <text x="110" y="128" textAnchor="middle" className="donut-center-value" style={ring === 'compare' && (hoveredRow || hoveredOther) ? { fontSize: 15 } : undefined}>
          {centerValue}
        </text>
      </svg>
      <div className="donut-legend" aria-label="범례">
        <div className="donut-legend-row muted small">
          <span />
          <span>종목</span>
          <span className="num">실제</span>
          <span className="num">목표</span>
        </div>
        {legendOf(view.rows).map((s) => {
          const row = view.rows.find((r) => r.key === s.key);
          const cw = row ? row.currentWeight : sum(otherRows, (r) => r.currentWeight);
          const tw = row ? row.targetWeight : sum(otherRows, (r) => r.targetWeight);
          return (
            <div key={s.key} className="donut-legend-row" onMouseEnter={() => setHovered(s.key)} onMouseLeave={() => setHovered(null)}>
              <span className="swatch" style={{ background: s.color }} />
              <span>{s.name}</span>
              <span className="num">{pct(cw)}</span>
              <span className="num">{pct(tw)}</span>
            </div>
          );
        })}
        {ring === 'compare' && <span className="ring-note">바깥 고리: 실제 · 안쪽 고리: 목표</span>}
      </div>
    </div>
  );
}

function manwonShort(value: number): string {
  if (value >= 1e8) return `${decimal(value / 1e8, 2)}억`;
  if (value >= 1e4) return `${decimal(value / 1e4, 0)}만`;
  return won(value);
}

/** 제외한 종목 키 → 종목 이름 (없으면 코드). */
function excludedName(key: string, holdings: readonly Holding[]): string {
  return holdings.find((h) => keyOf(h) === key)?.name ?? (key.includes(':') ? key.slice(key.indexOf(':') + 1) : key);
}

function tradeText(s: TradeSuggestion, usdKrw: number | null): string {
  const parts = [won(Math.abs(s.amountKrw))];
  if (s.row.isForeign && usdKrw) parts.push(usd(Math.abs(s.amountKrw) / usdKrw));
  if (s.shares !== null) parts.push(`약 ${decimal(s.shares, 0)}주`);
  return parts.join(' · ');
}

export function AllocationPage() {
  const data = useAppData();
  const [editing, setEditing] = useState<{ existing: AllocationTarget | null } | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(() => read(SELECTED_KEY));
  const [ring, setRing] = useState<Ring>(() => (RINGS as readonly string[]).includes(read(RING_KEY) ?? '') ? (read(RING_KEY) as Ring) : 'compare');
  const [amountText, setAmountText] = useState('');
  const [showRebalance, setShowRebalance] = useState(false);

  const targets = data.allocationTargets;
  const selected = targets.find((t) => t.id === selectedId) ?? targets[0] ?? null;
  const select = (id: string) => {
    setSelectedId(id);
    write(SELECTED_KEY, id);
  };
  const view = selected ? allocationView(selected, data.summary.accounts, data.usdKrw) : null;
  const amount = parseDecimal(amountText) ?? 0;
  const buys = view ? buyOnly(view, amount) : [];
  const trades = view ? rebalance(view) : [];

  return (
    <div className="stack">
      <div className="page-head">
        <h1 className="page-title">비중</h1>
        <button className="primary small-button" onClick={() => setEditing({ existing: null })}>+ 비중 설정 추가</button>
      </div>

      {!selected || !view ? (
        <Card title="비중 설정이 없습니다">
          <p className="muted">ISA 자산배분, 미국 개별주처럼 계좌별 목표 비중을 정하면 실제 비중과 비교하고, 비중에 맞춰 얼마씩 사면 되는지 계산해 드립니다.</p>
        </Card>
      ) : (
        <>
          {targets.length > 1 && (
            <div className="allocation-picker" role="radiogroup" aria-label="비중 설정">
              {targets.map((t) => (
                <button key={t.id} role="radio" aria-checked={t.id === selected.id} className={t.id === selected.id ? 'choice on' : 'choice'} onClick={() => select(t.id)}>
                  {t.id === selected.id && <span aria-hidden="true">✓ </span>}
                  {t.name}
                </button>
              ))}
            </div>
          )}

          <Card
            title={selected.name}
            action={<button className="small-button" onClick={() => setEditing({ existing: selected })}>편집</button>}
          >
            <p className="muted small">
              {selected.accountIds.map((id) => data.lookup.account(id)?.name).filter(Boolean).join(', ') || '대상 계좌 없음'}
              {' · '}합계 {won(view.totalKrw)}{selected.includeCash ? ' (예수금 포함)' : ''} · 강조 ±{decimal(selected.threshold, 2)}%p
            </p>
            {selected.excluded.length > 0 && (
              <p className="muted small">제외: {selected.excluded.map((k) => excludedName(k, data.holdings)).join(', ')}</p>
            )}
            {view.missingPrice && <Warning>현재가가 없는 종목이 있어 비중에서 빠져 있습니다.</Warning>}
            <div className="segmented" role="radiogroup" aria-label="그래프 보기">
              {RINGS.map((r) => (
                <button key={r} role="radio" aria-checked={ring === r} className={ring === r ? 'on' : ''} onClick={() => { setRing(r); write(RING_KEY, r); }}>
                  {RING_LABEL[r]}
                </button>
              ))}
            </div>
            <Donut view={view} ring={ring} />
          </Card>

          <Card title="목표와 실제">
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>종목</th>
                    <th className="num">목표</th>
                    <th className="num">실제</th>
                    <th className="num">차이</th>
                    <th className="num optional">평가금</th>
                  </tr>
                </thead>
                <tbody>
                  {view.rows.map((r) => {
                    const hl = isHighlighted(view, r);
                    return (
                      <tr key={r.key} className={hl ? 'highlight' : ''}>
                        <td>
                          <span className="toggle-row">
                            {r.holding && <TickerBadge code={r.holding.code} name={r.holding.name} market={r.holding.market} assetType={r.holding.assetType} />}
                            <span>{r.name}</span>
                          </span>
                        </td>
                        <td className="num">{pct(r.targetWeight)}</td>
                        <td className="num">{pct(r.currentWeight)}</td>
                        <td className="num strong">
                          {r.diffPoints > 0 ? '+' : ''}{decimal(r.diffPoints, 1)}%p
                          {hl && <span className="diff-tag">{r.diffPoints > 0 ? '초과' : '부족'}</span>}
                        </td>
                        <td className="num optional">{won(r.valueKrw)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </Card>

          <Card title="매수 계산기">
            <p className="muted small">넣을 돈을 입력하면, 팔지 않고 사기만 해서 목표에 가장 가까워지도록 종목별로 얼마를 살지 알려 드립니다. 계좌에 없는 종목은 현재가가 없어 주 수는 나오지 않습니다.</p>
            <WonField label="이번에 넣을 돈" value={amountText} onChange={setAmountText} preview={manwon} quick />
            {amount > 0 && buys.length === 0 && <p className="muted small">목표 비중이 있는 종목이 없습니다.</p>}
            {buys.map((s) => (
              <div key={s.row.key} className="row">
                <span>{s.row.name}</span>
                <strong>{tradeText(s, data.usdKrw)}</strong>
              </div>
            ))}
            <button className="text-button align-start" aria-expanded={showRebalance} onClick={() => setShowRebalance(!showRebalance)}>
              팔기까지 해서 정확히 맞추려면 {showRebalance ? '▴' : '▾'}
            </button>
            {showRebalance &&
              (trades.length === 0 ? (
                <p className="muted small">이미 목표와 같습니다.</p>
              ) : (
                trades.map((s) => (
                  <div key={s.row.key} className="row">
                    <span>{s.row.name}</span>
                    <strong>{s.amountKrw > 0 ? '사기 ' : '팔기 '}{tradeText(s, data.usdKrw)}</strong>
                  </div>
                ))
              ))}
          </Card>
        </>
      )}

      {editing && (
        <AllocationForm existing={editing.existing} onClose={() => setEditing(null)} onSaved={(id) => select(id)} />
      )}
    </div>
  );
}
