import { useState } from 'react';
import { Choice, DateField, FormActions, Modal, NumberField, TextField, numberText } from '../components/form';
import { Row } from '../components/ui';
import { plusMonths, today } from '../core/dates';
import { decimal, manwon, parseDecimal, won } from '../core/format';
import { useAppData, useData } from '../data/DataContext';
import { deleteSavingsAccount, deleteSavingsGoal, saveSavingsAccount, saveSavingsGoal } from '../data/writes';
import { GOAL_TYPES, GOAL_TYPE_LABEL, type GoalType, type SavingsAccount, type SavingsGoal } from '../model';

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
          <NumberField label="잔액 (원)" value={r.balance} onChange={(v) => update(i, { balance: v })} allowDecimal={false} preview={manwon} />
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
        onDelete={existing ? () => deleteSavingsAccount(uid, existing.id, data.savingsGoals) : undefined}
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
  const [dueDate, setDueDate] = useState(existing?.dueDate ?? plusMonths(today(), 12));
  const [intervalText, setIntervalText] = useState(String(existing?.intervalMonths ?? 12));

  const amount = parseDecimal(amountText);
  const interval = /^\d+$/.test(intervalText) ? Number(intervalText) : null;
  const problem =
    name.trim() === '' ? '목표 이름을 입력하세요.'
      : amount === null || amount <= 0 ? '목표 금액을 입력하세요.'
        : type === 'RECURRING' && (interval === null || interval < 1 || interval > 600) ? '반복 주기(1~600개월)를 입력하세요.'
          : null;

  return (
    <Modal title={existing ? '목표 편집' : '목표 추가'} onClose={onClose}>
      <TextField label="목표 이름" placeholder="예: 노트북, 휴대폰" value={name} onChange={setName} maxLength={40} />
      <Choice label="종류" options={GOAL_TYPES} value={type} text={(t) => GOAL_TYPE_LABEL[t]} onChange={setType} />
      <NumberField label="목표 금액 (원)" value={amountText} onChange={setAmountText} allowDecimal={false} preview={manwon} />
      <DateField label={type === 'ONE_TIME' ? '목표일' : '기준 지출일 (지나면 주기만큼 자동으로 넘어갑니다)'} value={dueDate} onChange={setDueDate} />
      {type === 'RECURRING' && (
        <NumberField label="반복 주기 (개월, 예: 24 = 2년마다)" value={intervalText} onChange={(v) => setIntervalText(v.replace(/[^0-9]/g, '').slice(0, 3))} allowDecimal={false} />
      )}
      <FormActions
        problem={problem}
        onSave={() =>
          saveSavingsGoal(uid, {
            id: existing?.id ?? '', accountId, name: name.trim(), type, amount: amount ?? 0, dueDate,
            intervalMonths: interval ?? 12, createdAt: existing?.createdAt ?? 0,
          })
        }
        onDone={onClose}
        onDelete={existing ? () => deleteSavingsGoal(uid, existing.id) : undefined}
        deleteLabel="목표 삭제"
      />
    </Modal>
  );
}
