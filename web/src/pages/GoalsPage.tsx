import { useState } from 'react';
import { formatDate, formatShortDate, today } from '../core/dates';
import { decimal, shortWon, won } from '../core/format';
import type { GoalProgress, GoalUnit } from '../core/goals';
import { useAppData, useData } from '../data/DataContext';
import { completeGoals, saveGoalGroup } from '../data/writes';
import { Modal } from '../components/form';
import { Card } from '../components/ui';
import { savingsBalance, type SavingsAccount, type SavingsGoal, type SavingsGoalGroup } from '../model';
import { GoalForm, GroupForm, SavingsAccountForm, SpendForm } from '../forms/SavingsForms';

type Dialog =
  | { kind: 'account'; existing: SavingsAccount | null }
  | { kind: 'goal'; accountId: string; existing: SavingsGoal | null }
  | { kind: 'spend'; goal: SavingsGoal }
  | { kind: 'group'; accountId: string; existing: SavingsGoalGroup | null }
  | { kind: 'balance-prompt'; account: SavingsAccount }
  | null;

function goalSchedule(g: GoalProgress): string {
  const interval = g.goal.intervalMonths;
  const every = g.goal.type === 'ONE_TIME'
    ? '1회성'
    : interval % 12 === 0 ? `${interval / 12}년마다` : `${interval}개월마다`;
  return `${every} · ${formatDate(g.nextDueDate)}`;
}

function status(remaining: number, overdue: boolean, monthsLeft: number, recurring: boolean): string {
  if (remaining <= 0) return '달성';
  if (overdue) return recurring ? '예정일 지남 · 소비했으면 기록하세요' : '목표일 지남';
  return `${monthsLeft}개월 남음`;
}

function Bar({ rate }: { rate: number }) {
  return (
    <span className="bar-row">
      <span className="bar">
        <span className={rate >= 1 ? 'bar-fill done' : 'bar-fill'} style={{ width: `${Math.max(2, rate * 100)}%` }} />
      </span>
      <span className="strong small">{Math.round(rate * 100)}%</span>
    </span>
  );
}

export function GoalsPage() {
  const data = useAppData();
  const { uid } = useData();
  const [dialog, setDialog] = useState<Dialog>(null);
  const [showCompleted, setShowCompleted] = useState<Set<string>>(new Set());
  const totalMonthly = data.savings.reduce((s, x) => s + x.totalMonthlyPayment, 0);
  const totalBalance = data.savingsAccounts.reduce((s, a) => s + savingsBalance(a), 0);

  const complete = async (account: SavingsAccount, goals: SavingsGoal[]) => {
    await completeGoals(uid, goals, today());
    setDialog({ kind: 'balance-prompt', account });
  };

  const goalRow = (account: SavingsAccount, g: GoalProgress) => (
    <div key={g.goal.id} className="goal-item">
      <button className="goal" onClick={() => setDialog({ kind: 'goal', accountId: account.id, existing: g.goal })}>
        <span className="goal-head">
          <span className="strong">
            {g.goal.name} <span className="muted small">· {goalSchedule(g)}</span>
          </span>
          <span className="strong small">월 {won(g.monthlyPayment)}</span>
        </span>
        <Bar rate={g.achievedRate} />
        <span className="goal-foot">
          <span className={g.overdue ? 'small danger-text' : 'muted small'}>
            {shortWon(g.allocated).replace(/원$/, '')} / {shortWon(g.goal.amount)} · {status(g.remaining, g.overdue, g.monthsLeft, g.goal.type === 'RECURRING')}
          </span>
          {g.goal.lastSpentDate && <span className="muted small">지난 소비 {formatDate(g.goal.lastSpentDate)}</span>}
        </span>
      </button>
      <div className="goal-actions">
        {g.goal.type === 'RECURRING' ? (
          <button className="small-button" onClick={() => setDialog({ kind: 'spend', goal: g.goal })}>소비 기록</button>
        ) : (
          <button className="small-button" onClick={() => complete(account, [g.goal])}>구매 완료</button>
        )}
      </div>
    </div>
  );

  const groupBlock = (account: SavingsAccount, unit: Extract<GoalUnit, { kind: 'group' }>) => (
    <div key={unit.group.id} className="goal-group">
      <div className="goal-head">
        <span className="strong">
          {unit.group.name} <span className="muted small">· 그룹 · {formatDate(unit.dueDate)}</span>
        </span>
        <span className="toggle-row">
          <label className="check small">
            <input
              type="checkbox"
              checked={unit.group.combined}
              onChange={(e) => saveGoalGroup(uid, { ...unit.group, combined: e.target.checked }, unit.members.map((m) => m.goal.id), data.savingsGoals)}
            />
            하나로 계산
          </label>
        </span>
      </div>
      {unit.combined ? (
        <>
          <Bar rate={unit.combined.achievedRate} />
          <span className="goal-foot">
            <span className={unit.combined.overdue ? 'small danger-text' : 'muted small'}>
              {shortWon(unit.combined.allocated).replace(/원$/, '')} / {shortWon(unit.combined.amount)} ·{' '}
              {status(unit.combined.remaining, unit.combined.overdue, unit.combined.monthsLeft, false)}
            </span>
            <span className="strong small">월 {won(unit.combined.monthlyPayment)}</span>
          </span>
          <div className="group-members">
            {unit.members.map((m) => (
              <button key={m.goal.id} className="member-row" onClick={() => setDialog({ kind: 'goal', accountId: account.id, existing: m.goal })}>
                <span>{m.goal.name}</span>
                <span className="muted small">
                  {shortWon(m.allocated).replace(/원$/, '')} / {shortWon(m.goal.amount)}
                </span>
              </button>
            ))}
          </div>
        </>
      ) : (
        <div className="group-members">{unit.members.map((m) => goalRow(account, m))}</div>
      )}
      <div className="goal-actions">
        <button className="small-button" onClick={() => complete(account, unit.members.map((m) => m.goal))}>그룹 구매 완료</button>
        <button className="small-button" onClick={() => setDialog({ kind: 'group', accountId: account.id, existing: unit.group })}>그룹 편집</button>
      </div>
    </div>
  );

  return (
    <div className="stack">
      <div className="page-head">
        <h1 className="page-title">목표</h1>
        <button className="primary small-button" onClick={() => setDialog({ kind: 'account', existing: null })}>+ 목적통장 추가</button>
      </div>
      {data.savings.length === 0 ? (
        <Card title="목적통장이 없습니다">
          <p className="muted">IT기기금, 지출 통장처럼 목적별 통장을 만들고 목표를 등록하면 매달 넣어야 할 금액을 계산해 드립니다.</p>
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
            {data.savings.map((s) => {
              const oneTimeCount = s.goals.filter((g) => g.goal.type === 'ONE_TIME').length;
              const open = showCompleted.has(s.account.id);
              return (
                <Card
                  key={s.account.id}
                  title={s.account.name}
                  action={
                    <div className="toggle-row">
                      <span className="strong accent">월 {won(s.totalMonthlyPayment)}</span>
                      <button className="small-button" onClick={() => setDialog({ kind: 'account', existing: s.account })}>편집</button>
                    </div>
                  }
                >
                  <p className="muted small">
                    연 {decimal(s.account.annualRate * 100, 2)}% · 잔액 {won(savingsBalance(s.account))}
                    {s.account.balanceDate ? ` · ${formatShortDate(s.account.balanceDate)} 기준` : ''}
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
                  {s.units.length === 0 && <p className="muted small">목표가 없습니다.</p>}
                  {s.units.map((u) => (u.kind === 'single' ? goalRow(s.account, u.progress) : groupBlock(s.account, u)))}
                  {s.unallocated > 0 && <p className="muted small">목표에 배분하고 남은 잔액 {won(s.unallocated)}</p>}
                  <div className="toggle-row">
                    <button className="text-button" onClick={() => setDialog({ kind: 'goal', accountId: s.account.id, existing: null })}>+ 목표 추가</button>
                    {oneTimeCount >= 2 && (
                      <button className="text-button" onClick={() => setDialog({ kind: 'group', accountId: s.account.id, existing: null })}>+ 그룹 만들기</button>
                    )}
                  </div>
                  {s.completed.length > 0 && (
                    <>
                      <button
                        className="text-button align-start"
                        aria-expanded={open}
                        onClick={() => {
                          const next = new Set(showCompleted);
                          if (open) next.delete(s.account.id);
                          else next.add(s.account.id);
                          setShowCompleted(next);
                        }}
                      >
                        완료한 목표 ({s.completed.length}) {open ? '▴' : '▾'}
                      </button>
                      {open &&
                        s.completed.map((g) => (
                          <div key={g.id} className="completed-row">
                            <span>
                              {g.name} <span className="muted small">· {shortWon(g.amount)} · {g.completedDate ? formatDate(g.completedDate) : ''} 완료</span>
                            </span>
                            <button className="small-button" onClick={() => completeGoals(uid, [g], null)}>되돌리기</button>
                          </div>
                        ))}
                    </>
                  )}
                </Card>
              );
            })}
          </div>
        </>
      )}
      {dialog?.kind === 'account' && <SavingsAccountForm existing={dialog.existing} onClose={() => setDialog(null)} />}
      {dialog?.kind === 'goal' && <GoalForm accountId={dialog.accountId} existing={dialog.existing} onClose={() => setDialog(null)} />}
      {dialog?.kind === 'group' && <GroupForm accountId={dialog.accountId} existing={dialog.existing} onClose={() => setDialog(null)} />}
      {dialog?.kind === 'spend' && (
        <SpendForm
          goal={dialog.goal}
          onClose={() => setDialog(null)}
          onSaved={() => {
            const account = data.savingsAccounts.find((a) => a.id === dialog.goal.accountId);
            setDialog(account ? { kind: 'balance-prompt', account } : null);
          }}
        />
      )}
      {dialog?.kind === 'balance-prompt' && (
        <Modal title="통장 잔액도 고칠까요?" onClose={() => setDialog(null)}>
          <p className="muted">돈이 나간 것은 통장 잔액을 고쳐야 목표 계산에 반영됩니다.</p>
          <div className="confirm-buttons">
            <button onClick={() => setDialog(null)}>나중에</button>
            <button className="primary" onClick={() => setDialog({ kind: 'account', existing: dialog.account })}>잔액 입력</button>
          </div>
        </Modal>
      )}
    </div>
  );
}
