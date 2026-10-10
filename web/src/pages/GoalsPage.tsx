import { formatDate, formatShortDate } from '../core/dates';
import { decimal, won } from '../core/format';
import type { GoalProgress } from '../core/goals';
import { useAppData } from '../data/DataContext';
import { Card } from '../components/ui';
import { savingsBalance } from '../model';

function goalSchedule(g: GoalProgress): string {
  const interval = g.goal.intervalMonths;
  const every = g.goal.type === 'ONE_TIME'
    ? '1회'
    : interval % 12 === 0 ? `${interval / 12}년마다` : `${interval}개월마다`;
  return `${every} · ${formatDate(g.nextDueDate)}`;
}

function goalStatus(g: GoalProgress): string {
  if (g.remaining <= 0) return '달성';
  if (g.overdue) return '목표일 지남';
  return `${g.monthsLeft}개월 남음`;
}

export function GoalsPage() {
  const data = useAppData();
  const totalMonthly = data.savings.reduce((s, x) => s + x.totalMonthlyPayment, 0);
  const totalBalance = data.savingsAccounts.reduce((s, a) => s + savingsBalance(a), 0);

  return (
    <div className="stack">
      <h1 className="page-title">목표</h1>
      {data.savings.length === 0 ? (
        <Card title="목적통장이 없습니다">
          <p className="muted">IT기기금, 지출 통장처럼 목적별 통장을 만들고 목표를 등록하면 매달 넣어야 할 금액을 계산해 드립니다. 지금은 폰 앱에서 추가하세요.</p>
        </Card>
      ) : (
        <>
          <section className="card ink">
            <div className="ink-row">
              <div>
                <span className="hero-label">이번 달 넣을 금액</span>
                <span className="hero-value">{won(totalMonthly)}</span>
              </div>
              <div className="end">
                <span className="hero-label">통장 잔액 합계</span>
                <span className="strong">{won(totalBalance)}</span>
              </div>
            </div>
          </section>
          <div className="grid-2">
            {data.savings.map((s) => (
              <Card
                key={s.account.id}
                title={s.account.name}
                action={<span className="strong accent">월 {won(s.totalMonthlyPayment)}</span>}
              >
                <p className="muted small">
                  연 {decimal(s.account.annualRate * 100, 2)}% · 잔액 {won(savingsBalance(s.account))}
                  {s.account.balanceDate ? ` (${formatShortDate(s.account.balanceDate)})` : ''}
                </p>
                {(s.account.subAccounts.length > 1 || s.account.subAccounts.some((x) => x.number.trim() !== '')) && (
                  <div className="chips">
                    {s.account.subAccounts.map((sub, i) => (
                      <span key={i} className="chip">
                        {sub.name} {won(sub.balance).replace(/원$/, '')}
                      </span>
                    ))}
                  </div>
                )}
                {s.goals.length === 0 && <p className="muted small">목표가 없습니다.</p>}
                {s.goals.map((g) => (
                  <div key={g.goal.id} className="goal">
                    <div className="goal-head">
                      <span className="strong">
                        {g.goal.name} <span className="muted small">· {goalSchedule(g)}</span>
                      </span>
                      <span className="strong">{Math.round(g.achievedRate * 100)}%</span>
                    </div>
                    <div className="bar">
                      <div className={g.remaining <= 0 ? 'bar-fill done' : 'bar-fill'} style={{ width: `${Math.max(2, g.achievedRate * 100)}%` }} />
                    </div>
                    <div className="goal-foot">
                      <span className="muted small">
                        {won(g.allocated).replace(/원$/, '')} / {won(g.goal.amount)} · {goalStatus(g)}
                      </span>
                      <span className="strong small">월 {won(g.monthlyPayment)}</span>
                    </div>
                  </div>
                ))}
                {s.unallocated > 0 && <p className="muted small">목표에 배분하고 남은 잔액 {won(s.unallocated)}</p>}
              </Card>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
