import { useState } from 'react';
import { FormActions, Modal, NumberField, TextField } from '../components/form';
import { CASH_KEY, keyOf, type AllocationTarget } from '../core/allocation';
import { decimal, parseDecimal } from '../core/format';
import { useAppData, useData } from '../data/DataContext';
import { deleteAllocationTarget, saveAllocationTarget } from '../data/writes';

/** 비중 설정 만들기·편집: 이름, 대상 계좌, 예수금 포함, 강조 기준, 종목별 목표 %. */
export function AllocationForm({ existing, onClose, onSaved }: { existing: AllocationTarget | null; onClose: () => void; onSaved?: (id: string) => void }) {
  const { uid } = useData();
  const data = useAppData();
  const [name, setName] = useState(existing?.name ?? '');
  const [accountIds, setAccountIds] = useState<string[]>(existing?.accountIds ?? (data.accounts.length === 1 ? [data.accounts[0].id] : []));
  const [includeCash, setIncludeCash] = useState(existing?.includeCash ?? false);
  const [thresholdText, setThresholdText] = useState(existing ? decimal(existing.threshold, 2).replace(/,/g, '') : '3');
  const [texts, setTexts] = useState<{ [key: string]: string }>(() =>
    Object.fromEntries(Object.entries(existing?.targets ?? {}).map(([k, v]) => [k, decimal(v, 2).replace(/,/g, '')])),
  );

  // 대상 계좌의 종목 (같은 종목은 하나로, 0주 포함 — 아직 안 산 종목도 목표에 넣을 수 있게)
  const items = new Map<string, string>();
  for (const h of data.holdings.filter((h) => accountIds.includes(h.accountId))) {
    if (!items.has(keyOf(h))) items.set(keyOf(h), h.name);
  }
  // 이미 목표가 있는데 대상 계좌에 없는 종목(지웠거나 계좌를 뺐을 때)도 남겨 둔다
  for (const k of Object.keys(existing?.targets ?? {})) {
    if (k !== CASH_KEY && !items.has(k)) items.set(k, k.includes(':') ? k.slice(k.indexOf(':') + 1) : k);
  }
  const keys = [...items.keys()];
  if (includeCash) keys.push(CASH_KEY);
  const percent = (k: string) => parseDecimal(texts[k] ?? '') ?? 0;
  const sum = keys.reduce((s, k) => s + percent(k), 0);
  const threshold = parseDecimal(thresholdText);
  const invalid = keys.some((k) => (texts[k] ?? '').trim() !== '' && (parseDecimal(texts[k]) === null || percent(k) < 0 || percent(k) > 100));

  const problem =
    name.trim() === '' ? '이름을 입력하세요.'
      : accountIds.length === 0 ? '대상 계좌를 고르세요.'
        : invalid ? '목표 비중은 0~100% 로 입력하세요.'
          : Math.abs(sum - 100) > 0.01 ? `목표 합계가 100% 가 되어야 합니다 (지금 ${decimal(sum, 2)}%).`
            : threshold === null || threshold < 0 || threshold > 100 ? '강조 기준은 0~100%p 로 입력하세요.'
              : null;

  const toggleAccount = (id: string) =>
    setAccountIds(accountIds.includes(id) ? accountIds.filter((x) => x !== id) : [...accountIds, id]);

  const save = async () => {
    const targets: { [key: string]: number } = {};
    for (const k of keys) if (percent(k) > 0) targets[k] = percent(k);
    const id = await saveAllocationTarget(uid, {
      id: existing?.id ?? '', name: name.trim(), accountIds, targets, includeCash, threshold: threshold ?? 3, createdAt: existing?.createdAt ?? 0,
    });
    onSaved?.(id);
  };

  const left = 100 - sum;
  return (
    <Modal title={existing ? '비중 설정 편집' : '비중 설정 추가'} onClose={onClose}>
      <TextField label="이름" placeholder="예: ISA 자산배분, 미국 개별주" value={name} onChange={setName} maxLength={40} />
      <div className="field">
        <span className="field-label">대상 계좌 (여러 개면 같은 종목을 합쳐서 계산)</span>
        {data.accounts.map((a) => (
          <label key={a.id} className="check">
            <input type="checkbox" checked={accountIds.includes(a.id)} onChange={() => toggleAccount(a.id)} />
            {a.name}
          </label>
        ))}
      </div>
      <label className="check">
        <input type="checkbox" checked={includeCash} onChange={(e) => setIncludeCash(e.target.checked)} />
        예수금도 비중에 넣기
      </label>
      <NumberField label="차이 강조 기준 (%p)" value={thresholdText} onChange={setThresholdText} preview={(n) => `목표와 ±${decimal(n, 2)}%p 이상 차이 나면 강조`} />
      <div className="field">
        <span className="field-label">목표 비중 (%)</span>
        <span className="field-hint">아직 안 산 종목은 계좌에서 종목을 먼저 추가하면 여기에 나옵니다 (0주여도 됩니다).</span>
        {keys.map((k) => (
          <label key={k} className="target-row">
            <span>{k === CASH_KEY ? '예수금' : items.get(k)} <span className="muted small">{k === CASH_KEY ? '' : k.replace(/^id:.*/, '')}</span></span>
            <input
              inputMode="decimal"
              aria-label={`${k === CASH_KEY ? '예수금' : items.get(k)} 목표 %`}
              value={texts[k] ?? ''}
              placeholder="0"
              onChange={(e) => setTexts({ ...texts, [k]: e.target.value.replace(/[^0-9.]/g, '').slice(0, 6) })}
            />
          </label>
        ))}
        {keys.length > 0 && (
          <span className={Math.abs(left) > 0.01 ? 'sum-line bad' : 'sum-line'}>
            합계 {decimal(sum, 2)}%{Math.abs(left) > 0.01 ? (left > 0 ? ` · ${decimal(left, 2)}% 남음` : ` · ${decimal(-left, 2)}% 넘음`) : ' ✓'}
          </span>
        )}
      </div>
      <FormActions
        problem={problem}
        onSave={save}
        onDone={onClose}
        onDelete={existing ? () => deleteAllocationTarget(uid, existing.id) : undefined}
        deleteLabel="비중 설정 삭제"
      />
    </Modal>
  );
}
