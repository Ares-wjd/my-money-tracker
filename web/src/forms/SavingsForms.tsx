import { useState } from 'react';
import { Choice, DateField, FormActions, Modal, NumberField, TextField, WonField, numberText } from '../components/form';
import { formatDate } from '../core/dates';
import { advancePast, nextDueDate, recordSpending } from '../core/goals';
import { Row } from '../components/ui';
import { plusMonths, today } from '../core/dates';
import { decimal, manwon, parseDecimal, shortWon, won } from '../core/format';
import { useAppData, useData } from '../data/DataContext';
import { deleteGoalGroup, deleteSavingsAccount, deleteSavingsGoal, saveGoalGroup, saveSavingsAccount, saveSavingsGoal } from '../data/writes';
import { GOAL_TYPES, GOAL_TYPE_LABEL, isCompleted, type GoalType, type SavingsAccount, type SavingsGoal, type SavingsGoalGroup } from '../model';

interface SubRow {
  name: string;
  number: string;
  balance: string;
}

const MAX_SUB_ACCOUNTS = 20;

export function SavingsAccountForm({ existing, onClose }: { existing: SavingsAccount | null; onClose: () => void }) {
  const { uid } = useData();
  const data = useAppData();
  const [name, setName] = useState(existing?.name ?? '');
  const [rows, setRows] = useState<SubRow[]>(
    existing?.subAccounts.map((s) => ({ name: s.name, number: s.number, balance: numberText(s.balance, 0) })) ?? [{ name: '', number: '', balance: '' }],
  );
  const [rateText, setRateText] = useState(existing ? decimal(existing.annualRate * 100, 2).replace(/,/g, '') : '');
  const [memo, setMemo] = useState(existing?.memo ?? '');

  const ratePercent = rateText.trim() === '' ? 0 : parseDecimal(rateText);
  const balancesValid = rows.every((r) => r.balance.trim() === '' || (parseDecimal(r.balance) ?? -1) >= 0);
  const total = rows.reduce((s, r) => s + (parseDecimal(r.balance) ?? 0), 0);
  const problem =
    name.trim() === '' ? '통장 이름을 입력하세요.'
      : !balancesValid ? '잔액을 확인하세요.'
        : ratePercent === null || ratePercent < 0 || ratePercent > 100 ? '기대수익률은 0~100% 사이로 입력하세요.'
          : null;
  const update = (i: number, patch: Partial<SubRow>) => setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));

  const save = () => {
    const subAccounts = rows.map((r, i) => ({ name: r.name.trim() || `계좌 ${i + 1}`, number: r.number.trim(), balance: parseDecimal(r.balance) ?? 0 }));
    const balanceChanged = !existing || existing.subAccounts.map((s) => s.balance).join() !== subAccounts.map((s) => s.balance).join();
    return saveSavingsAccount(uid, {
      id: existing?.id ?? '',
      name: name.trim(),
      subAccounts,
      balanceDate: balanceChanged ? today() : existing?.balanceDate ?? null,
      annualRate: (ratePercent ?? 0) / 100,
      memo: memo.trim(),
      createdAt: existing?.createdAt ?? 0,
    });
  };

  return (
    <Modal title={existing ? '목적통장 편집' : '목적통장 추가'} onClose={onClose}>
      <TextField label="통장 이름" placeholder="예: IT기기금, 지출" value={name} onChange={setName} maxLength={40} />
      <div className="field">
        <span className="field-label">계좌</span>
        <span className="field-hint">이 목적통장의 돈이 들어 있는 계좌들입니다 (예: CMA, 채권 계좌). 잔액은 합계로 계산합니다.</span>
      </div>
      {rows.map((r, i) => (
        <div key={i} className="sub-card">
          <div className="sub-card-head">
            <span className="strong small">계좌 {i + 1}</span>
            {rows.length > 1 && (
              <button className="text-button danger-text" onClick={() => setRows(rows.filter((_, j) => j !== i))}>삭제</button>
            )}
          </div>
          <div className="field-row">
            <TextField label="계좌 이름" placeholder="예: CMA, 채권" value={r.name} onChange={(v) => update(i, { name: v })} maxLength={30} />
            <TextField label="계좌번호 (선택)" value={r.number} onChange={(v) => update(i, { number: v.replace(/[^0-9-]/g, '').slice(0, 30) })} />
          </div>
          <WonField label="잔액 (원)" value={r.balance} onChange={(v) => update(i, { balance: v })} preview={manwon} />
        </div>
      ))}
      {rows.length < MAX_SUB_ACCOUNTS && (
        <button onClick={() => setRows([...rows, { name: '', number: '', balance: '' }])}>계좌 추가</button>
      )}
      <Row label="잔액 합계" value={<strong>{won(total)}</strong>} />
      <NumberField label="기대수익률 (연 %, 예: 3.5)" value={rateText} onChange={setRateText} preview={(n) => `연 ${decimal(n, 2)}%`} />
      <TextField label="메모 (선택)" value={memo} onChange={setMemo} maxLength={200} multiline />
      <FormActions
        problem={problem}
        onSave={save}
        onDone={onClose}
        onDelete={existing ? () => deleteSavingsAccount(uid, existing.id, data.savingsGoals, data.savingsGoalGroups) : undefined}
        deleteLabel="목적통장 삭제"
        deleteWarning="이 통장과 모든 목표가 함께 삭제됩니다. 되돌릴 수 없습니다."
      />
    </Modal>
  );
}

export function GoalForm({ accountId, existing, onClose }: { accountId: string; existing: SavingsGoal | null; onClose: () => void }) {
  const { uid } = useData();
  const [name, setName] = useState(existing?.name ?? '');
  const [type, setType] = useState<GoalType>(existing?.type ?? 'ONE_TIME');
  const [amountText, setAmountText] = useState(existing ? String(Math.round(existing.amount)) : '');
  // 반복 목표는 지금 계산된 다음 지출일을 보여준다
  const [dueDate, setDueDate] = useState(existing ? nextDueDate(existing) : plusMonths(today(), 12));
  const [intervalText, setIntervalText] = useState(String(existing?.intervalMonths ?? 12));

  const amount = parseDecimal(amountText);
  const interval = /^\d+$/.test(intervalText) ? Number(intervalText) : null;
  const problem =
    name.trim() === '' ? '목표 이름을 입력하세요.'
      : amount === null || amount <= 0 ? '목표 금액을 입력하세요.'
        : type === 'RECURRING' && (interval === null || interval < 1 || interval > 600) ? '반복 주기(1~600개월)를 입력하세요.'
          : null;

  const save = () => {
    const months = interval ?? 12;
    // 반복 목표에 지난 날짜를 넣으면 주기만큼 더한 날짜로 저장한다
    const due = type === 'RECURRING' && dueDate < today() ? advancePast(dueDate, months, today()) : dueDate;
    return saveSavingsGoal(uid, {
      id: existing?.id ?? '', accountId, name: name.trim(), type, amount: amount ?? 0, dueDate: due,
      intervalMonths: months, createdAt: existing?.createdAt ?? 0,
      // 그룹·구매 완료는 1회성만, 소비 기록은 반복만
      groupId: type === 'ONE_TIME' ? existing?.groupId ?? null : null,
      completedDate: type === 'ONE_TIME' ? existing?.completedDate ?? null : null,
      lastSpentDate: type === 'RECURRING' ? existing?.lastSpentDate ?? null : null,
      spentDates: type === 'RECURRING' ? existing?.spentDates ?? [] : [],
    });
  };

  return (
    <Modal title={existing ? '목표 편집' : '목표 추가'} onClose={onClose}>
      <TextField label="목표 이름" placeholder="예: 노트북, 휴대폰" value={name} onChange={setName} maxLength={40} />
      <Choice label="종류" options={GOAL_TYPES} value={type} text={(t) => GOAL_TYPE_LABEL[t]} onChange={setType} />
      <WonField label="목표 금액 (원)" value={amountText} onChange={setAmountText} preview={manwon} quick />
      <DateField label={type === 'ONE_TIME' ? '목표일' : '다음 지출일'} value={dueDate} onChange={setDueDate} />
      {type === 'RECURRING' && (
        <>
          <NumberField label="반복 주기 (개월, 예: 24 = 2년마다)" value={intervalText} onChange={(v) => setIntervalText(v.replace(/[^0-9]/g, '').slice(0, 3))} allowDecimal={false} />
          <p className="muted small">실제로 사면 목표의 [소비 기록]을 누르세요. 그날부터 주기를 다시 셉니다. 예정일이 지나도 날짜는 저절로 넘어가지 않습니다.</p>
          {existing && existing.spentDates.length > 0 && (
            <p className="muted small">지난 소비: {existing.spentDates.slice(-5).reverse().map((d) => formatDate(d)).join(', ')}</p>
          )}
        </>
      )}
      <FormActions
        problem={problem}
        onSave={save}
        onDone={onClose}
        onDelete={existing ? () => deleteSavingsGoal(uid, existing.id) : undefined}
        deleteLabel="목표 삭제"
      />
    </Modal>
  );
}

/** 소비 기록 (반복 목표): 산 날짜만 고른다. */
export function SpendForm({ goal, onClose, onSaved }: { goal: SavingsGoal; onClose: () => void; onSaved: () => void }) {
  const { uid } = useData();
  const [date, setDate] = useState(today());
  const next = plusMonths(date, Math.max(1, goal.intervalMonths));
  return (
    <Modal title={`${goal.name} 소비 기록`} onClose={onClose}>
      <p className="muted small">실제로 산 날짜를 고르세요. 그날부터 주기({goal.intervalMonths}개월)를 다시 셉니다.</p>
      <DateField label="소비한 날" value={date} onChange={setDate} />
      <Row label="다음 지출일" value={<strong>{formatDate(next)}</strong>} />
      {goal.lastSpentDate && <p className="muted small">지난 소비: {formatDate(goal.lastSpentDate)}</p>}
      <FormActions saveLabel="기록" onSave={() => saveSavingsGoal(uid, recordSpending(goal, date))} onDone={onSaved} />
    </Modal>
  );
}

/** 그룹 만들기·편집: 이름, 구매 날짜, 계산 방식, 묶을 1회성 목표. */
export function GroupForm({ accountId, existing, onClose }: { accountId: string; existing: SavingsGoalGroup | null; onClose: () => void }) {
  const { uid } = useData();
  const data = useAppData();
  // 묶을 수 있는 목표: 이 통장의 진행 중인 1회성 목표 중 다른 그룹에 없는 것
  const candidates = data.savingsGoals.filter(
    (g) => g.accountId === accountId && g.type === 'ONE_TIME' && !isCompleted(g) && (g.groupId === null || g.groupId === existing?.id || !data.savingsGoalGroups.some((x) => x.id === g.groupId)),
  );
  const [name, setName] = useState(existing?.name ?? '');
  const [selected, setSelected] = useState<Set<string>>(new Set(existing ? candidates.filter((g) => g.groupId === existing.id).map((g) => g.id) : []));
  const earliest = candidates.filter((g) => selected.has(g.id)).map((g) => g.dueDate).sort()[0];
  const [dueDate, setDueDate] = useState<string | null>(existing?.dueDate ?? null);
  const [combined, setCombined] = useState(existing?.combined ?? true);
  const shownDate = dueDate ?? earliest ?? plusMonths(today(), 12);

  const toggle = (id: string) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelected(next);
  };
  const problem = name.trim() === '' ? '그룹 이름을 입력하세요.' : selected.size === 0 ? '묶을 목표를 고르세요.' : null;

  return (
    <Modal title={existing ? '그룹 편집' : '그룹 만들기'} onClose={onClose}>
      <TextField label="그룹 이름" placeholder="예: IT 세트" value={name} onChange={setName} maxLength={40} />
      <div className="field">
        <span className="field-label">묶을 목표 (1회성)</span>
        {candidates.length === 0 && <span className="field-hint">묶을 수 있는 1회성 목표가 없습니다.</span>}
        {candidates.map((g) => (
          <label key={g.id} className="check">
            <input type="checkbox" checked={selected.has(g.id)} onChange={() => toggle(g.id)} />
            {g.name} <span className="muted small">· {shortWon(g.amount)} · {formatDate(g.dueDate)}</span>
          </label>
        ))}
      </div>
      <DateField label="그룹 구매 날짜" value={shownDate} onChange={setDueDate} />
      <label className="check">
        <input type="checkbox" checked={combined} onChange={(e) => setCombined(e.target.checked)} />
        하나로 계산 <span className="muted small">(끄면 각 목표를 원래 날짜로 따로 계산)</span>
      </label>
      <FormActions
        problem={problem}
        onSave={() =>
          saveGoalGroup(
            uid,
            { id: existing?.id ?? '', accountId, name: name.trim(), dueDate: shownDate, combined, createdAt: existing?.createdAt ?? 0 },
            [...selected],
            data.savingsGoals,
          )
        }
        onDone={onClose}
        onDelete={existing ? () => deleteGoalGroup(uid, existing.id, data.savingsGoals) : undefined}
        deleteLabel="그룹 풀기"
        deleteWarning="그룹을 풀까요? 목표는 그대로 남고 원래 날짜로 계산합니다."
      />
    </Modal>
  );
}
