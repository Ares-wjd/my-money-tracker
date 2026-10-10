import { Link, useParams } from 'react-router-dom';
import { formatDate } from '../core/dates';
import { amount, decimal, percent, signedAmount, usd, won } from '../core/format';
import { convert, displayConversion } from '../core/portfolio';
import { useAppData, useData } from '../data/DataContext';
import { Card, CurrencyToggle, NotFound, Row, profitClass } from '../components/ui';
import { ASSET_TYPE_LABEL, MARKET_LABEL, marketCurrency } from '../model';
import { recordAmountText, recordDetailText, recordTitle } from '../records/recordText';

export function HoldingDetailPage() {
  const { holdingId = '' } = useParams();
  const data = useAppData();
  const { displayCurrency, setDisplayCurrency } = useData();
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
  const records = data.recordsOfHolding(holdingId);

  return (
    <div className="stack">
      <div>
        <p className="muted small">
          <Link to={`/accounts/${account.account.id}`}>{account.account.name}</Link>
        </p>
        <h1 className="page-title">{holding.name}</h1>
        <p className="muted">{[MARKET_LABEL[holding.market], ASSET_TYPE_LABEL[holding.assetType], holding.code].filter(Boolean).join(' · ')}</p>
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
              <span className="stat-value">{v.unrealizedProfit === null ? '-' : shownSigned(v.unrealizedProfit)}</span>
            </div>
            <div className="stat">
              <span className="stat-label">수익률 (배당 미포함)</span>
              <span className="stat-value">{percent(v.returnRate)}</span>
            </div>
            <div className="stat">
              <span className="stat-label">수익률 (배당 포함)</span>
              <span className="stat-value">{percent(v.returnRateWithDividends)}</span>
            </div>
          </div>
        </Card>
        <Card>
          <Row label="보유 수량" value={decimal(p.quantity)} />
          <Row label="평균단가" value={shown(p.averagePrice)} />
          <Row label="매입금액" value={shown(p.costBasis)} />
          <Row label="현재가" value={v.price ? shown(v.price.price) + (v.price.date ? ` (${formatDate(v.price.date)})` : '') : '없음'} />
          <Row label="누적 배당" value={shown(p.dividends)} />
          <Row label="실현손익" value={shownSigned(p.realizedProfit)} valueClass={profitClass(p.realizedProfit)} />
          <Row label="수수료·세금 합계" value={shown(p.feesAndTaxes)} />
        </Card>
      </div>

      <Card title={`기록 ${records.length}건`}>
        {records.length === 0 && <p className="muted">기록이 없습니다.</p>}
        {records.map((r) => (
          <div key={r.id} className="list-row static">
            <span className="list-main">
              <span className="list-title">{recordTitle(r, data.lookup, holding.accountId)}</span>
              <span className="list-sub">{[formatDate(r.date), recordDetailText(r, data.lookup)].filter(Boolean).join(' · ')}</span>
            </span>
            <span className="list-end">
              <span className="list-value">{recordAmountText(r, data.lookup, holding.accountId)}</span>
            </span>
          </div>
        ))}
      </Card>
    </div>
  );
}
