import { useState } from 'react';
import { Link } from 'react-router-dom';
import { rate, signedWon } from '../core/format';
import { excludedItems, includedItems } from '../core/todayChange';
import { useAppData } from '../data/DataContext';
import { ContributionRows, TodayTiles, TodayTotal } from '../components/TodayChange';
import { Card, TickerBadge, profitClass } from '../components/ui';
import { todayBasis, useTodayChange } from './todayData';

type TodaySort = 'AMOUNT' | 'RATE';

/** 오늘 변동 상세: 요약 · 종목별 타일 · 종목 목록. */
export function TodayPage() {
  const data = useAppData();
  const change = useTodayChange(data);
  const [sort, setSort] = useState<TodaySort>('AMOUNT');

  if (change === null) {
    return (
      <Card title="오늘 변동">
        <p className="muted">보유 종목이 없습니다.</p>
        <Link to="/">홈으로</Link>
      </Card>
    );
  }

  const key = (v: number | null) => v ?? -Infinity;
  const sorted = [
    ...includedItems(change).sort((a, b) => (sort === 'AMOUNT' ? key(b.changeKrw) - key(a.changeKrw) : key(b.rate) - key(a.rate))),
    ...excludedItems(change),
  ];

  return (
    <div className="stack">
      <Card title="오늘 변동">
        <span className="muted small">{[todayBasis(data.quoteDate), '해외는 미국 시장 기준'].filter(Boolean).join(' · ')}</span>
        <TodayTotal change={change} />
        <ContributionRows change={change} />
      </Card>

      <Card title="종목별">
        <p className="muted small">칸 크기 = 평가금 비중 · 색 = 오늘 등락 (빨강 상승, 파랑 하락, 진할수록 큼) · 누르면 종목 화면</p>
        <TodayTiles change={change} />
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
