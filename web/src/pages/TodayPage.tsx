import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { EMPTY_CALENDAR } from '../core/marketClock';
import {
  TODAY_KINDS,
  TODAY_KIND_LABEL,
  allChipsShown,
  isNoFilter,
  presentKinds,
  toggleAllChips,
  toggleChip,
  type TodayFilter,
  type TodayKind,
} from '../core/todayChange';
import { useAppData } from '../data/DataContext';
import { MarketSection, SessionTimeline, ShareBar, TodayTotal, nowText } from '../components/TodayChange';
import { Card } from '../components/ui';
import { useNow, useTodayChange } from './todayData';

// 필터는 뺀 것을 이 브라우저에 기억한다 (처음엔 전부 = 전체, 새 계좌·종류도 보임).
const ACCOUNTS_KEY = 'today_hidden_accounts';
const KINDS_KEY = 'today_hidden_kinds';

function readList(key: string): string[] {
  try {
    return (localStorage.getItem(key) ?? '').split(',').filter((x) => x !== '');
  } catch {
    return [];
  }
}

function writeList(key: string, values: Iterable<string>) {
  try {
    localStorage.setItem(key, [...values].join(','));
  } catch {
    // 저장할 수 없는 환경이면 이번 화면에서만 적용한다.
  }
}

function readFilter(): TodayFilter {
  return {
    hiddenAccounts: new Set(readList(ACCOUNTS_KEY)),
    hiddenKinds: new Set(readList(KINDS_KEY).filter((k): k is TodayKind => (TODAY_KINDS as readonly string[]).includes(k))),
  };
}

/** 칩 한 줄: [전체] + 항목들. */
function ChipRow<T extends string>(props: { label: string; items: readonly T[]; hidden: ReadonlySet<T>; name: (item: T) => string; onChange: (next: Set<T>) => void }) {
  const { label, items, hidden, name, onChange } = props;
  const all = allChipsShown(items, hidden);
  return (
    <div className="today-filter-row">
      <span className="muted small">{label}</span>
      <div className="choices" role="group" aria-label={label}>
        <button aria-pressed={all} className={all ? 'choice on' : 'choice'} onClick={() => onChange(toggleAllChips(items, hidden))}>전체</button>
        {items.map((item) => (
          <button key={item} aria-pressed={!hidden.has(item)} className={hidden.has(item) ? 'choice' : 'choice on'} onClick={() => onChange(toggleChip(item, hidden))}>
            {name(item)}
          </button>
        ))}
      </div>
    </div>
  );
}

/** 시장별 변동 상세: 필터 · 요약 · 지금 열린 장 · 시장별 카드. */
export function TodayPage() {
  const data = useAppData();
  const [filter, setFilterState] = useState<TodayFilter>(readFilter);
  const nowMs = useNow();
  const change = useTodayChange(data, nowMs, filter);
  const accountOrder = data.summary.accounts.map((a) => a.account.id);
  const kinds = useMemo(() => presentKinds(data.summary), [data]);
  const accountName = (id: string) => data.summary.accounts.find((a) => a.account.id === id)?.account.name ?? '';

  const setFilter = (next: TodayFilter) => {
    setFilterState(next);
    writeList(ACCOUNTS_KEY, next.hiddenAccounts);
    writeList(KINDS_KEY, next.hiddenKinds);
  };

  // 지금 있는 계좌·종류 중에 뺀 것 ("연금저축 · ETF 제외")
  const hiddenNames = [
    ...accountOrder.filter((id) => filter.hiddenAccounts.has(id)).map(accountName),
    ...kinds.filter((k) => filter.hiddenKinds.has(k)).map((k) => TODAY_KIND_LABEL[k]),
  ];

  const filterCard = (
    <Card className="today-filter">
      <ChipRow label="계좌" items={accountOrder} hidden={filter.hiddenAccounts} name={accountName} onChange={(next) => setFilter({ ...filter, hiddenAccounts: next })} />
      <ChipRow label="종류" items={kinds} hidden={filter.hiddenKinds} name={(k) => TODAY_KIND_LABEL[k]} onChange={(next) => setFilter({ ...filter, hiddenKinds: next })} />
    </Card>
  );

  if (change === null) {
    return (
      <div className="stack">
        {filterCard}
        <Card title="시장별 변동">
          <p className="muted">{isNoFilter(filter) ? '보유 종목이 없습니다.' : '고른 범위에 보유 종목이 없습니다. 위에서 계좌나 종류를 고르세요.'}</p>
          <Link to="/">홈으로</Link>
        </Card>
      </div>
    );
  }

  return (
    <div className="stack">
      {filterCard}

      <Card title="시장별 변동">
        {hiddenNames.length > 0 && <span className="small today-filter-note">필터 적용 중 · {hiddenNames.join(' · ')} 제외</span>}
        <span className="muted small">{nowText(nowMs)}</span>
        <TodayTotal change={change} />
        <ShareBar change={change} />
      </Card>

      <Card title="지금 열린 장" action={<span className="muted small">한국 시간 · 미국은 서머타임 자동 반영</span>}>
        <SessionTimeline change={change} nowMs={nowMs} calendar={data.market.calendar ?? EMPTY_CALENDAR} />
      </Card>

      {change.markets.map((m) => (
        <MarketSection key={m.state.group} move={m} nowMs={nowMs} />
      ))}
      <p className="muted small">직접 입력 가격(펀드 등)은 시세 없음 · 한투가 주지 않는 장외 시세는 마지막으로 받은 가격 기준</p>
    </div>
  );
}
