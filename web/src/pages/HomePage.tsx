import { Link } from 'react-router-dom';
import { formatShortDate } from '../core/dates';
import { decimal, percent, signedWon, won } from '../core/format';
import { useAppData } from '../data/DataContext';
import { Badge, Card, ListLink, Stat, Warning, profitClass } from '../components/ui';
import { ACCOUNT_KIND_LABEL } from '../model';
import { accountDescription } from './text';

export function HomePage() {
  const data = useAppData();
  const { summary } = data;

  if (data.accounts.length === 0) {
    return (
      <Card title="첫 투자 계좌를 등록해 보세요">
        <p className="muted">계좌를 만들고 입금·매수 기록을 남기면 투자금과 수익률이 자동으로 계산됩니다.</p>
        <Link to="/accounts">계좌 화면으로 →</Link>
      </Card>
    );
  }

  return (
    <div className="stack">
      <section className="card hero">
        <span className="hero-label">투자 자산 평가금</span>
        <span className="hero-value">{won(summary.valueKrw)}</span>
        <span className="pill">
          {summary.profitKrw >= 0 ? '▲ ' : '▼ '}
          {won(Math.abs(summary.profitKrw))} · {percent(summary.returnRate)}
        </span>
        <div className="stats">
          <Stat label="투자금" value={won(summary.investedKrw)} />
          <Stat label="수익금 (배당 미포함)" value={signedWon(summary.profitExDividendsKrw)} />
          <Stat label="배당 미포함 수익률" value={percent(summary.returnRateExDividends)} />
          <Stat label="누적 배당" value={won(summary.dividendsKrw)} />
        </div>
        <span className="hero-foot">
          {[
            data.usdKrw !== null &&
              `환율 ${decimal(data.usdKrw, 2)}원` + (data.usdKrwDate ? ` (${formatShortDate(data.usdKrwDate)})` : ' (직접 입력)'),
            data.quoteDate && `시세 ${formatShortDate(data.quoteDate)} 기준 (폰 앱이 저장한 값)`,
          ]
            .filter(Boolean)
            .join(' · ')}
        </span>
      </section>

      {(summary.missingFx || summary.missingPrice) && (
        <Card>
          {summary.missingFx && <Warning>달러 자산이 있지만 환율이 없어 원화 합계에서 빠져 있습니다.</Warning>}
          {summary.missingPrice && <Warning>현재가가 없는 종목이 있어 평가금에서 빠져 있습니다.</Warning>}
        </Card>
      )}

      <Card title="계좌">
        {summary.accounts.map((a) => (
          <ListLink
            key={a.account.id}
            to={`/accounts/${a.account.id}`}
            leading={<Badge>{ACCOUNT_KIND_LABEL[a.account.kind]}</Badge>}
            title={a.account.name}
            subtitle={accountDescription(a.account, data.linked.has(a.account.id)) || undefined}
            value={won(a.valueKrw)}
            subValue={percent(a.returnRate)}
            subValueClass={profitClass(a.returnRate)}
          />
        ))}
      </Card>
    </div>
  );
}
