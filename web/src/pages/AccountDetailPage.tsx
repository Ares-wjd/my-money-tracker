import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { formatDate } from '../core/dates';
import { amount, decimal, percent, signedWon, usd, won } from '../core/format';
import { convert, displayConversion } from '../core/portfolio';
import { useAppData, useData } from '../data/DataContext';
import { Card, CurrencyToggle, ListLink, NotFound, Row, Stat, TickerBadge, Warning, profitClass } from '../components/ui';
import { ASSET_TYPE_LABEL, marketCurrency } from '../model';
import { recordAmountText, recordDetailText, recordTitle } from '../records/recordText';
import { accountDescription } from './text';

const PAGE = 30;

export function AccountDetailPage() {
  const { accountId = '' } = useParams();
  const data = useAppData();
  const { displayCurrency, setDisplayCurrency } = useData();
  const [visible, setVisible] = useState(PAGE);
  const summary = data.summary.accounts.find((a) => a.account.id === accountId);
  if (!summary) return <NotFound what="계좌" />;

  const linked = data.linked.has(accountId);
  const records = data.recordsOf(accountId);
  const holdings = [...summary.holdings].sort((a, b) => (b.marketValueKrw ?? 0) - (a.marketValueKrw ?? 0));
  const hasUsd = holdings.some((h) => marketCurrency(h.holding.market) === 'USD');
  const showUsdCash = summary.cash.usd !== 0 || hasUsd;

  return (
    <div className="stack">
      <div>
        <h1 className="page-title">{summary.account.name}</h1>
        <p className="muted">
          {[accountDescription(summary.account, linked, true), summary.account.memo].filter((s) => s.trim() !== '').join(' · ')}
        </p>
      </div>

      <Card>
        <span className="muted">평가금</span>
        <span className="big">{won(summary.valueKrw)}</span>
        <span className={profitClass(summary.profitKrw)}>
          {signedWon(summary.profitKrw)} · {percent(summary.returnRate)}
        </span>
        <div className="stats">
          <Stat label="투자금" value={won(summary.investedKrw)} />
          <Stat label="배당 미포함" value={percent(summary.returnRateExDividends)} valueClass={profitClass(summary.returnRateExDividends)} />
          <Stat label="누적 배당" value={won(summary.dividendsKrw)} />
          <Stat label="수익금 (배당 미포함)" value={signedWon(summary.profitExDividendsKrw)} valueClass={profitClass(summary.profitExDividendsKrw)} />
        </div>
        {summary.missingFx && <Warning>환율이 없어 달러 금액이 원화 합계에서 빠져 있습니다.</Warning>}
        {summary.missingPrice && <Warning>현재가가 없는 종목이 있습니다.</Warning>}
      </Card>

      <div className="grid-2">
        <Card title="예수금">
          <Row label={linked ? '원화 (D+2)' : '원화'} value={won(summary.cash.krw)} />
          {showUsdCash && <Row label="달러" value={usd(summary.cash.usd)} />}
          <p className="muted small">
            {linked
              ? '한투에서 불러온 값 (폰 앱이 새로고침할 때 갱신)' + (summary.cashDate ? ` · ${formatDate(summary.cashDate)} 변경` : '')
              : summary.cashDate
                ? `${formatDate(summary.cashDate)} 입력한 값`
                : '아직 입력하지 않았습니다.'}
          </p>
        </Card>
        {linked && (
          <Card title="한투 연결 계좌">
            <p className="muted small">매수·매도 체결과 예수금은 폰 앱에서 한투로부터 불러옵니다. 입금·출금(투자금)과 배당은 직접 기록합니다.</p>
          </Card>
        )}
      </div>

      <Card title="보유 종목" action={hasUsd ? <CurrencyToggle mode={displayCurrency} usdKrw={summary.usdKrw} onChange={setDisplayCurrency} /> : undefined}>
        {holdings.length === 0 ? (
          <p className="muted">종목이 없습니다.</p>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>종목</th>
                  <th className="num">수량</th>
                  <th className="num">평균단가</th>
                  <th className="num">현재가</th>
                  <th className="num">평가금</th>
                  <th className="num">수익률</th>
                </tr>
              </thead>
              <tbody>
                {holdings.map((v) => {
                  const c = displayConversion(marketCurrency(v.holding.market), displayCurrency, summary.usdKrw);
                  const shown = (value: number) => {
                    const converted = convert(c, value);
                    return converted === null ? '환율 필요' : amount(c.currency, converted);
                  };
                  return (
                    <tr key={v.holding.id}>
                      <td>
                        <ListLink
                          to={`/holdings/${v.holding.id}`}
                          leading={<TickerBadge code={v.holding.code} name={v.holding.name} />}
                          title={v.holding.name}
                          subtitle={ASSET_TYPE_LABEL[v.holding.assetType]}
                        />
                      </td>
                      <td className="num">{decimal(v.position.quantity)}</td>
                      <td className="num">{shown(v.position.averagePrice)}</td>
                      <td className="num">{v.price ? shown(v.price.price) : '없음'}</td>
                      <td className="num strong">{v.marketValue === null ? '가격 입력 필요' : shown(v.marketValue)}</td>
                      <td className={`num ${profitClass(v.returnRate)}`}>{percent(v.returnRate)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card title={`기록 ${records.length}건`}>
        {records.length === 0 && <p className="muted">기록이 없습니다.</p>}
        {records.slice(0, visible).map((r) => (
          <div key={r.id} className="list-row static">
            <span className="list-main">
              <span className="list-title">{recordTitle(r, data.lookup, accountId)}</span>
              <span className="list-sub">{[formatDate(r.date), recordDetailText(r, data.lookup)].filter(Boolean).join(' · ')}</span>
            </span>
            <span className="list-end">
              <span className="list-value">{recordAmountText(r, data.lookup, accountId)}</span>
            </span>
          </div>
        ))}
        {records.length > visible && (
          <button className="text-button" onClick={() => setVisible(visible + PAGE)}>
            기록 더 보기 ({records.length - visible}건 남음)
          </button>
        )}
      </Card>
    </div>
  );
}
