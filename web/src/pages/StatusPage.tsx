import type { User } from 'firebase/auth';
import { useAuth } from '../auth';
import { latestQuoteDate } from '../data/mappers';
import { useUserData } from '../data/useUserData';
import { Logo } from '../Logo';
import { linkedAccountIds, savingsBalance } from '../model';

/** W1 확인용 화면: 폰과 같은 데이터가 보이는지 확인한다 (W2 에서 홈·계좌·목표 화면으로 바뀐다). */
export function StatusPage({ user }: { user: User }) {
  const { signOut } = useAuth();
  const { data, errors } = useUserData(user.uid);

  return (
    <main className="page">
      <header className="topbar">
        <div className="brand">
          <Logo size={32} />
          <strong>Money Tracker</strong>
        </div>
        <div className="account">
          <span className="muted">{user.email}</span>
          <button onClick={() => signOut()}>로그아웃</button>
        </div>
      </header>

      {errors.map((e) => (
        <p key={e} className="error">{e}</p>
      ))}

      {data === null ? (
        <p className="muted">데이터를 불러오는 중…</p>
      ) : (
        <>
          <section className="card">
            <h2>연결 확인</h2>
            <p>
              계좌 {data.accounts.length}개 · 종목 {data.holdings.length}개 · 기록 {data.records.length}건 ·
              목적통장 {data.savingsAccounts.length}개 · 목표 {data.savingsGoals.length}개
            </p>
            <p className="muted">
              시세 기준일: {latestQuoteDate(data.market) ?? '아직 없음 (폰 앱에서 새로고침하면 저장됩니다)'}
            </p>
            <p className="muted">PC 웹은 준비 중입니다. 지금은 폰과 같은 데이터가 보이는지만 확인합니다.</p>
          </section>

          <section className="card">
            <h2>투자 계좌</h2>
            {data.accounts.length === 0 && <p className="muted">계좌가 없습니다.</p>}
            <ul className="list">
              {data.accounts.map((account) => (
                <li key={account.id}>
                  <span>{account.name}</span>
                  <span className="muted">
                    {linkedAccountIds(data.records).has(account.id) ? '한투 연결 · ' : ''}
                    종목 {data.holdings.filter((h) => h.accountId === account.id).length}개
                  </span>
                </li>
              ))}
            </ul>
          </section>

          <section className="card">
            <h2>목적통장</h2>
            {data.savingsAccounts.length === 0 && <p className="muted">목적통장이 없습니다.</p>}
            <ul className="list">
              {data.savingsAccounts.map((account) => (
                <li key={account.id}>
                  <span>{account.name}</span>
                  <span className="muted">{Math.round(savingsBalance(account)).toLocaleString('ko-KR')}원</span>
                </li>
              ))}
            </ul>
          </section>
        </>
      )}
    </main>
  );
}
