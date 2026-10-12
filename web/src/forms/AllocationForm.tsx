import { useState } from 'react';
import { FormActions, Modal, NumberField, TextField } from '../components/form';
import { CASH_KEY, keyOf, manualKey, type AllocationTarget } from '../core/allocation';
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
  const [excluded, setExcluded] = useState<string[]>(existing?.excluded ?? []);
  // 계좌에 없는(아직 안 산) 종목: 종목 키 → 이름
  const [manual, setManual] = useState<{ [key: string]: string }>(existing?.names ?? {});
  const [adding, setAdding] = useState(false);
  const [addForeign, setAddForeign] = useState(true);
  const [addCode, setAddCode] = useState('');
  const [addName, setAddName] = useState('');
  const [texts, setTexts] = useState<{ [key: string]: string }>(() =>
    Object.fromEntries(Object.entries(existing?.targets ?? {}).map(([k, v]) => [k, decimal(v, 2).replace(/,/g, '')])),
  );

  // 대상 계좌의 종목 (같은 종목은 하나로, 0주 포함 — 아직 안 산 종목도 목표에 넣을 수 있게)
  const items = new Map<string, string>();
  for (const h of data.holdings.filter((h) => accountIds.includes(h.accountId))) {
    if (!items.has(keyOf(h))) items.set(keyOf(h), h.name);
  }
  const held = new Set(items.keys());
  // 직접 추가한 종목, 그리고 이미 목표가 있는데 대상 계좌에 없는 종목(지웠거나 계좌를 뺐을 때)도 남겨 둔다
  for (const [k, n] of Object.entries(manual)) if (!items.has(k)) items.set(k, n);
  for (const k of Object.keys(existing?.targets ?? {})) {
    if (k !== CASH_KEY && !items.has(k)) items.set(k, k.includes(':') ? k.slice(k.indexOf(':') + 1) : k);
  }
  const keys = [...items.keys()];
  if (includeCash) keys.push(CASH_KEY);
  // 제외한 종목은 목표 % 를 받지 않고 계산에서 뺀다
  const isExcluded = (k: string) => excluded.includes(k);
  const percent = (k: string) => (isExcluded(k) ? 0 : parseDecimal(texts[k] ?? '') ?? 0);
  const sum = keys.reduce((s, k) => s + percent(k), 0);
  const threshold = parseDecimal(thresholdText);
  const invalid = keys.some((k) => !isExcluded(k) && (texts[k] ?? '').trim() !== '' && (parseDecimal(texts[k]) === null || percent(k) < 0 || percent(k) > 100));

  const problem =
    name.trim() === '' ? '이름을 입력하세요.'
      : accountIds.length === 0 ? '대상 계좌를 고르세요.'
        : invalid ? '목표 비중은 0~100% 로 입력하세요.'
          : Math.abs(sum - 100) > 0.01 ? `목표 합계가 100% 가 되어야 합니다 (지금 ${decimal(sum, 2)}%).`
            : threshold === null || threshold < 0 || threshold > 100 ? '강조 기준은 0~100%p 로 입력하세요.'
              : null;

  const addKey = addCode.trim() === '' ? null : manualKey(addForeign, addCode);
  const addProblem = addKey === null ? null : items.has(addKey) || addKey === CASH_KEY ? '이미 목록에 있는 종목입니다.' : null;
  const addManual = () => {
    if (addKey === null || addProblem) return;
    setManual({ ...manual, [addKey]: addName.trim() || addCode.trim().toUpperCase() });
    setAddCode('');
    setAddName('');
    setAdding(false);
  };
  const removeManual = (k: string) => {
    const nextManual = { ...manual };
    delete nextManual[k];
    const nextTexts = { ...texts };
    delete nextTexts[k];
    setManual(nextManual);
    setTexts(nextTexts);
  };

  const toggleAccount = (id: string) =>
    setAccountIds(accountIds.includes(id) ? accountIds.filter((x) => x !== id) : [...accountIds, id]);

  const save = async () => {
    const targets: { [key: string]: number } = {};
    for (const k of keys) if (percent(k) > 0) targets[k] = percent(k);
    const id = await saveAllocationTarget(uid, {
      id: existing?.id ?? '', name: name.trim(), accountIds, targets, includeCash, threshold: threshold ?? 3, createdAt: existing?.createdAt ?? 0,
      // 지금 대상 계좌에 있는 종목만 제외 목록에 남긴다
      excluded: excluded.filter((k) => held.has(k)),
      // 직접 추가한 이름은 아직 계좌에 없는 종목만 (사서 계좌에 생기면 종목 이름을 쓴다)
      names: Object.fromEntries(Object.entries(manual).filter(([k]) => !held.has(k))),
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
        <span className="field-hint">아직 안 산 종목은 아래 [+ 계좌에 없는 종목 직접 추가]로 넣을 수 있습니다 (나중에 사면 자동으로 연결). 비중에서 뺄 종목은 [제외]를 고르세요.</span>
        {keys.map((k) => {
          const label = k === CASH_KEY ? '예수금' : items.get(k);
          const off = isExcluded(k);
          return (
            <div key={k} className={off ? 'target-row excluded' : 'target-row'}>
              <span>
                {label} <span className="muted small">{k === CASH_KEY ? '' : k.replace(/^id:.*/, '')}</span>
                {off && <span className="muted small"> · 제외</span>}
                {!held.has(k) && k !== CASH_KEY && <span className="muted small"> · 미보유</span>}
              </span>
              <span className="target-inputs">
                {off ? (
                  <span className="muted small">계산에서 뺌</span>
                ) : (
                  <input
                    inputMode="decimal"
                    aria-label={`${label} 목표 %`}
                    value={texts[k] ?? ''}
                    placeholder="0"
                    onChange={(e) => setTexts({ ...texts, [k]: e.target.value.replace(/[^0-9.]/g, '').slice(0, 6) })}
                  />
                )}
                {k !== CASH_KEY && !held.has(k) && (
                  <button type="button" className="icon-button" aria-label={`${label} 빼기`} onClick={() => removeManual(k)}>✕</button>
                )}
                {k !== CASH_KEY && held.has(k) && (
                  <label className="check small">
                    <input type="checkbox" checked={off} onChange={() => setExcluded(off ? excluded.filter((x) => x !== k) : [...excluded, k])} />
                    제외
                  </label>
                )}
              </span>
            </div>
          );
        })}
        {adding ? (
          <div className="sub-card">
            <div className="choices" role="radiogroup" aria-label="시장">
              {[true, false].map((f) => (
                <button type="button" key={String(f)} role="radio" aria-checked={addForeign === f} className={addForeign === f ? 'choice on' : 'choice'} onClick={() => setAddForeign(f)}>
                  {addForeign === f && <span aria-hidden="true">✓ </span>}
                  {f ? '해외' : '국내'}
                </button>
              ))}
            </div>
            <div className="field-row">
              <TextField label="종목코드" placeholder={addForeign ? '예: QQQM' : '예: 005930'} value={addCode} onChange={(v) => setAddCode(v.replace(/[^0-9A-Za-z.\-]/g, '').slice(0, 20))} />
              <TextField label="종목 이름" placeholder={addForeign ? '예: Invesco QQQM' : '예: 삼성전자'} value={addName} onChange={setAddName} maxLength={60} />
            </div>
            {addProblem && <p className="warning">{addProblem}</p>}
            <div className="confirm-buttons">
              <button type="button" onClick={() => setAdding(false)}>취소</button>
              <button type="button" className="primary" disabled={addKey === null || !!addProblem} onClick={addManual}>추가</button>
            </div>
          </div>
        ) : (
          <button type="button" className="text-button align-start" onClick={() => setAdding(true)}>+ 계좌에 없는 종목 직접 추가</button>
        )}
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
