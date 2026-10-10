import { useState } from 'react';
import { percent, won } from '../core/format';
import { AccountForm } from '../forms/AccountForm';
import { useAppData } from '../data/DataContext';
import { Badge, Card, ListLink, profitClass } from '../components/ui';
import { ACCOUNT_KIND_LABEL } from '../model';
import { accountDescription } from './text';

export function AccountsPage() {
  const data = useAppData();
  const [adding, setAdding] = useState(false);
  return (
    <div className="stack">
      <div className="page-head">
        <h1 className="page-title">계좌</h1>
        <button className="primary small-button" onClick={() => setAdding(true)}>+ 계좌 추가</button>
      </div>
      {adding && <AccountForm existing={null} onClose={() => setAdding(false)} />}
      <Card>
        {data.summary.accounts.length === 0 && <p className="muted">계좌가 없습니다. 계좌 추가를 눌러 시작하세요.</p>}
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
