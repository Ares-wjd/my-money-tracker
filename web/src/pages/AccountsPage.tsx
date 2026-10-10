import { percent, won } from '../core/format';
import { useAppData } from '../data/DataContext';
import { Badge, Card, ListLink, profitClass } from '../components/ui';
import { ACCOUNT_KIND_LABEL } from '../model';
import { accountDescription } from './text';

export function AccountsPage() {
  const data = useAppData();
  return (
    <div className="stack">
      <h1 className="page-title">계좌</h1>
      <Card>
        {data.summary.accounts.length === 0 && <p className="muted">계좌가 없습니다. 지금은 폰 앱에서 추가하세요.</p>}
        {data.summary.accounts.map((a) => (
          <ListLink
            key={a.account.id}
            to={`/accounts/${a.account.id}`}
            leading={<Badge>{ACCOUNT_KIND_LABEL[a.account.kind]}</Badge>}
            title={a.account.name}
            subtitle={[accountDescription(a.account, data.linked.has(a.account.id)), `종목 ${a.holdings.filter((h) => h.position.quantity > 0).length}개`]
              .filter(Boolean)
              .join(' · ')}
            value={won(a.valueKrw)}
            subValue={`투자금 ${won(a.investedKrw)} · ${percent(a.returnRate)}`}
            subValueClass={profitClass(a.returnRate)}
          />
        ))}
      </Card>
    </div>
  );
}
