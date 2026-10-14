import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { formatDate } from '../core/dates';
import { amount, decimal, rate as formatRate, signedWon, usd, won } from '../core/format';
import { HOLDING_SORTS, HOLDING_SORT_LABEL, convert, cumulativeInvested, displayConversion, sortHoldings, type HoldingValuation } from '../core/portfolio';
import { useAppData, useData } from '../data/DataContext';
import { Card, CurrencyToggle, ListLink, NotFound, Row, Stat, TickerBadge, TickerLegend, Warning, profitClass, tickerGroup } from '../components/ui';
import { ASSET_TYPE_LABEL, RECORD_TYPE_LABEL, marketCurrency, type Currency, type Record, type RecordType } from '../model';
import type { CashDetail } from '../core/cash';
import { AccountForm } from '../forms/AccountForm';
import { CashForm } from '../forms/CashForm';
import { HoldingForm } from '../forms/HoldingForm';
import { RecordForm } from '../forms/RecordForm';
import { AddRecordMenu, RecordRow } from '../records/RecordList';
import { accountDescription } from './text';
import { allShown, applyRecordFilter, presentTypes, recordFilterLabel, toggleAll, toggleType } from '../core/recordFilter';

const PAGE = 30;

type Dialog =
  | { kind: 'account' }
  | { kind: 'holding' }
  | { kind: 'cash' }
  | { kind: 'record'; type: RecordType; existing: Record | null }
  | null;

/** 기록 추가 메뉴에 보여줄 유형 (예수금은 예수금 카드의 "예수금 입력" 으로 따로 넣는다). */
const ADDABLE: readonly RecordType[] = ['DEPOSIT', 'WITHDRAW', 'TRANSFER', 'BUY', 'SELL', 'DIVIDEND', 'EXCHANGE'];

export function AccountDetailPage() {
  const { accountId = '' } = useParams();
  const data = useAppData();
  const { displayCurrency, setDisplayCurrency, holdingSort, setHoldingSort } = useData();
  const [visible, setVisible] = useState(PAGE);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [showSoldOut, setShowSoldOut] = useState(false);
  // 기록 유형 필터 (기본은 전부 보임, 이 브라우저에 계좌마다 기억)
  const [hiddenByAccount, setHiddenByAccount] = useState<{ [id: string]: Set<RecordType> }>({});
  const navigate = useNavigate();
  const summary = data.summary.accounts.find((a) => a.account.id === accountId);
  if (!summary) return <NotFound what="계좌" />;

  const linked = data.linked.has(accountId);
  const allRecords = data.recordsOf(accountId);
  // 입금·출금·이체 기록마다 그 기록까지의 누적 투자금 (걸러 봐도 전체 기록 기준)
  const cumulative = cumulativeInvested(accountId, allRecords);
  const hidden = hiddenByAccount[accountId] ?? readHidden(accountId);
  const setHidden = (next: Set<RecordType>) => {
    setHiddenByAccount({ ...hiddenByAccount, [accountId]: next });
    try {
      localStorage.setItem(HIDDEN_KEY + accountId, [...next].join(','));
    } catch {
      // 저장할 수 없는 환경이면 이번 화면에서만 적용한다.
    }
  };
  const types = presentTypes(allRecords);
  const records = applyRecordFilter(allRecords, hidden);
  // 총 수익률은 보기를 따른다 (원화 보기: 매매일 환율이 모두 있으면 환차익 반영 원화 수익률)
  const totalRate = (v: HoldingValuation) => (displayCurrency === 'KRW' ? data.krwView(v)?.returnRate : null) ?? v.returnRate;
  const sorted = sortHoldings(summary.holdings, holdingSort, totalRate, data.todayRate);
  // 0주 종목(다 판 종목)은 맨 아래 "매도 완료 종목" 안에 접어 둔다
  const holdings = sorted.filter((v) => v.position.quantity > 0);
  const soldOut = sorted.filter((v) => v.position.quantity <= 0);
  const hasUsd = holdings.some((h) => marketCurrency(h.holding.market) === 'USD');
  const showUsdCash = summary.cash.usd !== 0 || hasUsd;

  const row = (v: HoldingValuation) => {
    const c = displayConversion(marketCurrency(v.holding.market), displayCurrency, summary.usdKrw);
    const shown = (value: number) => {
      const converted = convert(c, value);
      return converted === null ? '환율 필요' : amount(c.currency, converted);
    };
    // 원화 보기: 매매일 환율이 모두 있으면 환차익을 반영한 원화 평균단가·수익률
    const krw = displayCurrency === 'KRW' ? data.krwView(v) : null;
    const rate = totalRate(v);
    const today = data.todayRate(v);
    return (
      // 행 어디를 눌러도 종목 화면으로 (키보드는 첫 칸의 링크로 이동)
      <tr key={v.holding.id} className="clickable" onClick={() => navigate(`/holdings/${v.holding.id}`)}>
        <td>
          <ListLink
            to={`/holdings/${v.holding.id}`}
            leading={<TickerBadge code={v.holding.code} name={v.holding.name} market={v.holding.market} assetType={v.holding.assetType} />}
            title={v.holding.name}
            subtitle={ASSET_TYPE_LABEL[v.holding.assetType]}
          />
        </td>
        <td className="num">{decimal(v.position.quantity)}{v.holding.assetType === 'GOLD' ? 'g' : ''}</td>
        <td className="num optional">{krw ? won(krw.averagePriceKrw) : shown(v.position.averagePrice)}</td>
        <td className="num optional">{v.price ? shown(v.price.price) : '없음'}</td>
        <td className="num strong">{v.marketValue === null ? '가격 입력 필요' : shown(v.marketValue)}</td>
        <td className={`num ${profitClass(rate)}`}>{formatRate(rate)}</td>
        <td className={`num ${profitClass(today)}`}>{formatRate(today)}</td>
      </tr>
    );
  };

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1 className="page-title">{summary.account.name}</h1>
          <p className="muted">
            {[accountDescription(summary.account, linked, true), summary.account.memo].filter((s) => s.trim() !== '').join(' · ')}
          </p>
        </div>
        <button onClick={() => setDialog({ kind: 'account' })}>계좌 편집</button>
      </div>

      <Card>
        {/* 핵심 3개(투자금·수익금·수익률)를 크게, 나머지는 아래 한 줄로 */}
        <span className="muted">평가금</span>
        <span className="big">{won(summary.valueKrw)}</span>
        <div className="stats">
          <Stat label="투자금" value={won(summary.investedKrw)} />
          <Stat label="수익금" value={signedWon(summary.profitKrw)} valueClass={profitClass(summary.profitKrw)} />
          <Stat label="수익률" value={formatRate(summary.returnRate)} valueClass={profitClass(summary.returnRate)} />
        </div>
        {summary.returnRate === null ? (
          <p className="muted sub-line">
            입금 기록이 없어 수익률을 계산할 수 없어요.{' '}
            <button className="text-button inline" onClick={() => setDialog({ kind: 'record', type: 'DEPOSIT', existing: null })}>입금 기록하기</button>
            {' · '}누적 배당 {won(summary.dividendsKrw)}
          </p>
        ) : (
          <p className="muted sub-line">
            배당 미포함 {formatRate(summary.returnRateExDividends)} · 누적 배당 {won(summary.dividendsKrw)}
          </p>
        )}
        {summary.missingFx && <Warning>환율이 없어 달러 금액이 원화 합계에서 빠져 있습니다.</Warning>}
        {summary.missingPrice && <Warning>현재가가 없는 종목이 있습니다.</Warning>}
      </Card>

      <div className="grid-2">
        <Card title="예수금">
          <Row label={linked ? '원화 (D+2)' : '원화'} value={won(summary.cash.krw)} />
          {showUsdCash && <Row label="달러" value={usd(summary.cash.usd)} />}
          {linked ? (
            <p className="muted small">
              {'한투에서 불러온 값 (폰 앱이 새로고침할 때 갱신, 하루 한 번은 기록)' + (summary.cashDate ? ` · ${formatDate(summary.cashDate)} 기록` : '')}
            </p>
          ) : (
            <>
              {/* 혼합 방식: 입력값(기준점) + 이후 기록. 통화마다 근거를 보여준다 */}
              <p className="muted small">{cashBasis(summary.cashDetails.KRW, 'KRW', showUsdCash)}</p>
              {showUsdCash && <p className="muted small">{cashBasis(summary.cashDetails.USD, 'USD', true)}</p>}
              {(summary.cash.krw < 0 || summary.cash.usd < 0) && <Warning>계산상 예수금이 음수입니다. 입금 기록이나 예수금 입력을 확인하세요.</Warning>}
            </>
          )}
          {!linked && (
            <button className="text-button" onClick={() => setDialog({ kind: 'cash' })}>예수금 입력</button>
          )}
        </Card>
        {linked && (
          <Card title="한투 연결 계좌">
            <p className="muted small">매수·매도 체결과 예수금은 폰 앱에서 한투로부터 불러옵니다. 입금·출금(투자금)과 배당은 직접 기록합니다.</p>
          </Card>
        )}
      </div>

      <Card
        title="보유 종목"
        action={
          <div className="toggle-row">
            {hasUsd && <CurrencyToggle mode={displayCurrency} usdKrw={summary.usdKrw} onChange={setDisplayCurrency} />}
            <button onClick={() => setDialog({ kind: 'holding' })}>+ 종목 추가</button>
          </div>
        }
      >
        {sorted.length > 0 && (
          <div className="holdings-tools">
            <div className="segmented" role="radiogroup" aria-label="정렬">
              {HOLDING_SORTS.map((s) => (
                <button key={s} role="radio" aria-checked={holdingSort === s} className={holdingSort === s ? 'on' : ''} onClick={() => setHoldingSort(s)}>
                  {HOLDING_SORT_LABEL[s]}
                </button>
              ))}
            </div>
            <TickerLegend groups={new Set(holdings.map((v) => tickerGroup(v.holding.market, v.holding.assetType)))} />
          </div>
        )}
        {sorted.length === 0 ? (
          <p className="muted">종목이 없습니다. 종목을 추가한 뒤 매수 기록을 남기세요.</p>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>종목</th>
                  <th className="num">수량</th>
                  <th className="num optional">평균단가</th>
                  <th className="num optional">현재가</th>
                  <th className="num">평가금</th>
                  <th className="num">총 수익률</th>
                  <th className="num">오늘</th>
                </tr>
              </thead>
              <tbody>
                {holdings.map(row)}
                {soldOut.length > 0 && (
                  <tr className="sold-out-toggle">
                    <td colSpan={7}>
                      <button className="text-button" aria-expanded={showSoldOut} onClick={() => setShowSoldOut(!showSoldOut)}>
                        매도 완료 종목 ({soldOut.length}) {showSoldOut ? '▴' : '▾'}
                      </button>
                    </td>
                  </tr>
                )}
                {showSoldOut && soldOut.map(row)}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card
        title={records.length === allRecords.length ? `기록 ${records.length}건` : `기록 ${records.length}건 / 전체 ${allRecords.length}건`}
        action={
          <div className="head-actions">
            {/* 표로 일괄 입력 (입출금·매매·배당 — 연결 계좌는 매매 표를 빼고 보여준다) */}
            <Link className="button-link small-button" to={`/accounts/${accountId}/import`}>표로 일괄 입력</Link>
            <AddRecordMenu
              // 환전은 직접 입력 계좌의 예수금 계산용 (연결 계좌는 예수금을 불러온다)
              types={ADDABLE.filter((t) => !linked || (t !== 'BUY' && t !== 'SELL' && t !== 'EXCHANGE'))}
              label={(t) => RECORD_TYPE_LABEL[t]}
              onSelect={(type) => setDialog({ kind: 'record', type, existing: null })}
            />
          </div>
        }
      >
        {types.length > 1 && (
          <div className="choices record-filter" role="group" aria-label="기록 유형">
            <button aria-pressed={allShown(types, hidden)} className={allShown(types, hidden) ? 'choice on' : 'choice'} onClick={() => setHidden(toggleAll(types, hidden))}>전체</button>
            {types.map((t) => (
              <button key={t} aria-pressed={!hidden.has(t)} className={hidden.has(t) ? 'choice' : 'choice on'} onClick={() => setHidden(toggleType(t, hidden))}>
                {recordFilterLabel(t)}
              </button>
            ))}
          </div>
        )}
        {allRecords.length === 0 && <p className="muted">기록이 없습니다. 기록 추가에서 입금부터 남겨 보세요.</p>}
        {allRecords.length > 0 && records.length === 0 && (
          <p className="muted">{types.every((t) => hidden.has(t)) ? '보여줄 유형을 고르세요.' : '선택한 유형의 기록이 없습니다.'}</p>
        )}
        {records.slice(0, visible).map((r) => (
          <RecordRow key={r.id} record={r} accountId={accountId} cumulative={cumulative.get(r.id)} onClick={() => setDialog({ kind: 'record', type: r.type, existing: r })} />
        ))}
        {records.length > visible && (
          <button className="text-button" onClick={() => setVisible(visible + PAGE)}>
            기록 더 보기 ({records.length - visible}건 남음)
          </button>
        )}
      </Card>

      {dialog?.kind === 'account' && (
        <AccountForm existing={summary.account} onClose={() => setDialog(null)} onDeleted={() => navigate('/accounts')} />
      )}
      {dialog?.kind === 'holding' && <HoldingForm accountId={accountId} existing={null} onClose={() => setDialog(null)} />}
      {dialog?.kind === 'cash' && <CashForm summary={summary} onClose={() => setDialog(null)} />}
      {dialog?.kind === 'record' && (
        <RecordForm accountId={dialog.existing?.accountId ?? accountId} type={dialog.type} existing={dialog.existing} onClose={() => setDialog(null)} />
      )}
    </div>
  );
}

/** 직접 입력 계좌의 예수금 근거: "10/1 입력 1,000,000원 + 이후 기록 −687,600원" 등 (폰과 같은 문구). */
function cashBasis(detail: CashDetail, currency: Currency, withLabel: boolean): string {
  const label = withLabel ? (currency === 'KRW' ? '원화: ' : '달러: ') : '';
  const amount = (v: number) => (currency === 'KRW' ? won(v) : usd(v));
  const signed = (v: number) => (currency === 'KRW' ? signedWon(v) : v > 0 ? `+${usd(v)}` : usd(v));
  if (detail.anchor === null && !detail.estimated) return `${label}아직 입력하지 않았습니다. 증권사 앱의 예수금을 입력하세요.`;
  if (detail.anchor === null || detail.anchorDate === null) return `${label}입력한 예수금이 없어 기록(입금·매매 등)으로 계산한 값`;
  if (!detail.estimated) return `${label}${formatDate(detail.anchorDate)} 입력한 값`;
  return `${label}${formatDate(detail.anchorDate)} 입력 ${amount(detail.anchor)} + 이후 기록 ${signed(detail.change)} (계산)`;
}

const HIDDEN_KEY = 'record_hidden_';

/** 이 브라우저에 기억한 계좌의 뺀 기록 유형. */
function readHidden(accountId: string): Set<RecordType> {
  try {
    const text = localStorage.getItem(HIDDEN_KEY + accountId) ?? '';
    return new Set(text.split(',').filter((t): t is RecordType => t in RECORD_TYPE_LABEL));
  } catch {
    return new Set();
  }
}
