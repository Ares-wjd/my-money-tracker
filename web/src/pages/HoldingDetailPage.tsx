import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { formatDate } from '../core/dates';
import { amount, decimal, percent, signedAmount, signedWon, usd, won } from '../core/format';
import { convert, displayConversion } from '../core/portfolio';
import { useAppData, useData } from '../data/DataContext';
import { Card, CurrencyToggle, NotFound, Row, profitClass } from '../components/ui';
import { ASSET_TYPE_LABEL, MARKET_LABEL, marketCurrency, type Record, type RecordType } from '../model';
import { HoldingForm, PriceForm } from '../forms/HoldingForm';
import { RecordForm } from '../forms/RecordForm';
import { RecordRow } from '../records/RecordList';

type Dialog = { kind: 'holding' } | { kind: 'price' } | { kind: 'record'; type: RecordType; existing: Record | null } | null;

export function HoldingDetailPage() {
  const { holdingId = '' } = useParams();
  const data = useAppData();
  const { displayCurrency, setDisplayCurrency } = useData();
  const [dialog, setDialog] = useState<Dialog>(null);
  const navigate = useNavigate();
  const holding = data.holdings.find((h) => h.id === holdingId);
  const account = holding && data.summary.accounts.find((a) => a.account.id === holding.accountId);
  const v = account?.holdings.find((h) => h.holding.id === holdingId);
  if (!holding || !account || !v) return <NotFound what="종목" />;

  const currency = marketCurrency(holding.market);
  const c = displayConversion(currency, displayCurrency, data.usdKrw);
  const shown = (value: number) => {
    const converted = convert(c, value);
    return converted === null ? '환율 필요' : amount(c.currency, converted);
  };
  const shownSigned = (value: number) => {
    const converted = convert(c, value);
    return converted === null ? '환율 필요' : signedAmount(c.currency, converted);
  };
  const p = v.position;
  // 원화 보기의 해외 종목: 매매일 환율이 모두 있으면 환차익 반영, 없으면 오늘 환율로 단순 환산
  const krw = displayCurrency === 'KRW' ? data.krwView(v) : null;
  const missingTradeFx = displayCurrency === 'KRW' && currency === 'USD' && data.usdKrw !== null && krw === null;
  const unrealized = krw ? krw.unrealizedProfitKrw : v.unrealizedProfit === null ? null : convert(c, v.unrealizedProfit);
  const rate = krw ? krw.returnRate : v.returnRate;
  const rateWithDividends = krw ? krw.returnRateWithDividends : v.returnRateWithDividends;
  const rateLabel = krw ? '원화 수익률' : '수익률';
  const records = data.recordsOfHolding(holdingId);
  const linked = data.linked.has(holding.accountId);

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <p className="muted small">
            <Link to={`/accounts/${account.account.id}`}>{account.account.name}</Link>
          </p>
          <h1 className="page-title">{holding.name}</h1>
          <p className="muted">{[MARKET_LABEL[holding.market], ASSET_TYPE_LABEL[holding.assetType], holding.code].filter(Boolean).join(' · ')}</p>
        </div>
        <button onClick={() => setDialog({ kind: 'holding' })}>종목 편집</button>
      </div>

      {currency === 'USD' && <CurrencyToggle mode={displayCurrency} usdKrw={data.usdKrw} onChange={setDisplayCurrency} />}

      <div className="grid-2">
        <Card className="hero">
          <span className="hero-label">평가금</span>
          <span className="hero-value">{v.marketValue === null ? '가격 입력 필요' : shown(v.marketValue)}</span>
          {currency === 'USD' && (
            <span className="hero-foot">
              {displayCurrency === 'FOREIGN'
                ? `원화 환산 ${v.marketValueKrw === null ? '환율 필요' : won(v.marketValueKrw)}`
                : `달러 기준 ${v.marketValue === null ? '-' : usd(v.marketValue)}`}
            </span>
          )}
          <div className="stats">
            <div className="stat">
              <span className="stat-label">평가손익</span>
              <span className="stat-value">{unrealized === null ? '-' : signedAmount(c.currency, unrealized)}</span>
            </div>
            <div className="stat">
              <span className="stat-label">{rateLabel} (배당 미포함)</span>
              <span className="stat-value">{percent(rate)}</span>
            </div>
            <div className="stat">
              <span className="stat-label">{rateLabel} (배당 포함)</span>
              <span className="stat-value">{percent(rateWithDividends)}</span>
            </div>
            {krw && (
              <>
                <div className="stat">
                  <span className="stat-label">주가 손익</span>
                  <span className="stat-value">{krw.stockProfitKrw === null ? '-' : signedWon(krw.stockProfitKrw)}</span>
                </div>
                <div className="stat">
                  <span className="stat-label">환차익</span>
                  <span className="stat-value">{signedWon(krw.fxProfitKrw)}</span>
                </div>
              </>
            )}
          </div>
        </Card>
        <Card>
          <Row label="보유 수량" value={decimal(p.quantity)} />
          <Row label="평균단가" value={krw ? `${won(krw.averagePriceKrw)} (매수일 환율)` : shown(p.averagePrice)} />
          <Row label="매입금액" value={krw ? won(krw.costBasisKrw) : shown(p.costBasis)} />
          <Row label="현재가" value={v.price ? shown(v.price.price) + (v.price.date ? ` (${formatDate(v.price.date)})` : '') : '없음'} />
          <button className="text-button align-start" onClick={() => setDialog({ kind: 'price' })}>현재가 직접 입력</button>
          <Row label="누적 배당" value={krw ? won(krw.dividendsKrw) : shown(p.dividends)} />
          <Row
            label="실현손익"
            value={krw ? signedWon(krw.realizedProfitKrw) : shownSigned(p.realizedProfit)}
            valueClass={profitClass(krw ? krw.realizedProfitKrw : p.realizedProfit)}
          />
          <Row label="수수료·세금 합계" value={krw ? won(krw.feesAndTaxesKrw) : shown(p.feesAndTaxes)} />
          {missingTradeFx && <p className="warning">매매일 환율이 아직 없어 오늘 환율로 단순 환산했습니다. 폰 앱을 열면 환율을 받아 와 반영됩니다.</p>}
        </Card>
      </div>

      <Card
        title={`기록 ${records.length}건`}
        action={
          <div className="toggle-row">
            {/* 한투 연결 계좌는 매수·매도를 불러오므로 직접 추가하지 않는다. */}
            {!linked && <button onClick={() => setDialog({ kind: 'record', type: 'BUY', existing: null })}>매수</button>}
            {!linked && <button onClick={() => setDialog({ kind: 'record', type: 'SELL', existing: null })}>매도</button>}
            <button onClick={() => setDialog({ kind: 'record', type: 'DIVIDEND', existing: null })}>배당</button>
          </div>
        }
      >
        {linked && <p className="muted small">한투 연결 계좌의 종목이라 매수·매도는 폰 앱에서 한투로부터 불러옵니다.</p>}
        {records.length === 0 && <p className="muted">기록이 없습니다.</p>}
        {records.map((r) => (
          <RecordRow key={r.id} record={r} accountId={holding.accountId} onClick={() => setDialog({ kind: 'record', type: r.type, existing: r })} />
        ))}
      </Card>

      {dialog?.kind === 'holding' && (
        <HoldingForm accountId={holding.accountId} existing={holding} onClose={() => setDialog(null)} onDeleted={() => navigate(`/accounts/${holding.accountId}`)} />
      )}
      {dialog?.kind === 'price' && <PriceForm holding={holding} onClose={() => setDialog(null)} />}
      {dialog?.kind === 'record' && (
        <RecordForm accountId={holding.accountId} type={dialog.type} existing={dialog.existing} presetHoldingId={holding.id} onClose={() => setDialog(null)} />
      )}
    </div>
  );
}
