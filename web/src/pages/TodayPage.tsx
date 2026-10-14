import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { rate, signedWon } from '../core/format';
import {
  TODAY_KINDS,
  TODAY_KIND_LABEL,
  allChipsShown,
  excludedItems,
  includedItems,
  isNoFilter,
  presentKinds,
  toggleAllChips,
  toggleChip,
  type TodayFilter,
  type TodayKind,
} from '../core/todayChange';
import { useAppData } from '../data/DataContext';
import { ContributionRows, TodayTiles, TodayTotal } from '../components/TodayChange';
import { Card, TickerBadge, profitClass } from '../components/ui';
import { accountOrderOf, todayBasis, useTodayChange } from './todayData';

type TodaySort = 'AMOUNT' | 'RATE';

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

/** 오늘 변동 상세: 요약 · 종목별 타일 · 종목 목록. */
export function TodayPage() {
  const data = useAppData();
  const [filter, setFilterState] = useState<TodayFilter>(readFilter);
  const change = useTodayChange(data, filter);
  const [sort, setSort] = useState<TodaySort>('AMOUNT');
  const accountOrder = accountOrderOf(data);
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
        <Card title="오늘 변동">
          <p className="muted">{isNoFilter(filter) ? '보유 종목이 없습니다.' : '고른 범위에 보유 종목이 없습니다. 위에서 계좌나 종류를 고르세요.'}</p>
          <Link to="/">홈으로</Link>
        </Card>
      </div>
    );
  }

  const key = (v: number | null) => v ?? -Infinity;
  const sorted = [
    ...includedItems(change).sort((a, b) => (sort === 'AMOUNT' ? key(b.changeKrw) - key(a.changeKrw) : key(b.rate) - key(a.rate))),
    ...excludedItems(change),
  ];

  return (
    <div className="stack">
      {filterCard}

      <Card title="오늘 변동">
        {hiddenNames.length > 0 && <span className="small today-filter-note">필터 적용 중 · {hiddenNames.join(' · ')} 제외</span>}
        <span className="muted small">{[todayBasis(data.quoteDate), '해외는 미국 시장 기준'].filter(Boolean).join(' · ')}</span>
        <TodayTotal change={change} />
        <ContributionRows change={change} accountOrder={accountOrder} />
      </Card>

      <Card title="종목별">
        <p className="muted small">칸 크기 = 평가금 비중 · 색 = 오늘 등락 (빨강 상승, 파랑 하락, 진할수록 큼) · 누르면 종목 화면</p>
        {change.items.length > 0 ? <TodayTiles change={change} /> : <p className="muted">고른 범위에 종목이 없습니다.</p>}
      </Card>

      <Card
        title={sort === 'AMOUNT' ? '변동 금액 순' : '등락률 순'}
        action={
          <div className="segmented" role="radiogroup" aria-label="정렬">
            {(['AMOUNT', 'RATE'] as const).map((s) => (
              <button key={s} role="radio" aria-checked={sort === s} className={sort === s ? 'on' : ''} onClick={() => setSort(s)}>
                {s === 'AMOUNT' ? '금액' : '등락률'}
              </button>
            ))}
          </div>
        }
      >
        {sorted.map((i) => (
          <Link key={i.key} to={`/holdings/${i.holding.id}`} className="list-row">
            <TickerBadge code={i.holding.code} name={i.holding.name} market={i.holding.market} assetType={i.holding.assetType} />
            <span className="list-main">
              <span className="list-title">{i.holding.name}</span>
            </span>
            <span className="list-end">
              {i.changeKrw === null ? (
                <span className="list-sub">시세 없음</span>
              ) : (
                <>
                  <span className={`list-value ${profitClass(i.changeKrw)}`}>{signedWon(i.changeKrw)}</span>
                  <span className={`list-sub ${profitClass(i.rate)}`}>{rate(i.rate)}</span>
                </>
              )}
            </span>
          </Link>
        ))}
        <p className="muted small">해외 종목 금액은 오늘 환율 기준 (환율 효과는 위에 따로) · 직접 입력 가격(펀드 등)은 시세 없음</p>
      </Card>
    </div>
  );
}
