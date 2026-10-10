import { useState } from 'react';
import { Choice, FormActions, Modal, TextField } from '../components/form';
import { useAppData, useData } from '../data/DataContext';
import { deleteAccount, saveAccount } from '../data/writes';
import { ACCOUNT_KINDS, ACCOUNT_KIND_LABEL, type AccountKind, type InvestmentAccount } from '../model';

export function AccountForm({ existing, onClose, onDeleted }: { existing: InvestmentAccount | null; onClose: () => void; onDeleted?: () => void }) {
  const { uid } = useData();
  const data = useAppData();
  const [name, setName] = useState(existing?.name ?? '');
  const [kind, setKind] = useState<AccountKind>(existing?.kind ?? 'GENERAL');
  const [number, setNumber] = useState(existing?.number ?? '');
  const [memo, setMemo] = useState(existing?.memo ?? '');

  return (
    <Modal title={existing ? '계좌 편집' : '계좌 추가'} onClose={onClose}>
      <TextField label="계좌 이름" placeholder="예: 한투 국내, 연금저축" value={name} onChange={setName} maxLength={40} />
      <Choice label="계좌 종류" options={ACCOUNT_KINDS} value={kind} text={(k) => ACCOUNT_KIND_LABEL[k]} onChange={setKind} />
      <TextField label="계좌번호 (선택)" value={number} onChange={(v) => setNumber(v.replace(/[^0-9-]/g, '').slice(0, 30))} />
      <TextField label="메모 (선택)" value={memo} onChange={setMemo} maxLength={200} multiline />
      <FormActions
        problem={name.trim() === '' ? '계좌 이름을 입력하세요.' : null}
        onSave={() =>
          saveAccount(uid, { id: existing?.id ?? '', name: name.trim(), kind, number: number.trim(), memo: memo.trim(), createdAt: existing?.createdAt ?? 0 })
        }
        onDone={onClose}
        onDelete={existing ? () => deleteAccount(uid, existing.id, data.holdings, data.records).then(() => onDeleted?.()) : undefined}
        deleteLabel="계좌 삭제"
        deleteWarning="이 계좌의 종목과 모든 기록(이 계좌가 받은 이체 포함)이 함께 삭제됩니다. 되돌릴 수 없습니다."
      />
    </Modal>
  );
}
