import { useState } from 'react';
import { FormActions, Modal, NumberField, TextField } from '../components/form';
import { CASH_KEY, DEFAULT_ABSOLUTE, DEFAULT_RELATIVE, codelessKey, isCodeless, keyOf, manualKey, toleranceBand, type AllocationTarget } from '../core/allocation';
import { decimal, parseDecimal } from '../core/format';
import { useAppData, useData } from '../data/DataContext';
import { deleteAllocationTarget, saveAllocationTarget } from '../data/writes';

type Texts = { [key: string]: string };

const toTexts = (m: { [key: string]: number } | undefined): Texts =>
  Object.fromEntries(Object.entries(m ?? {}).map(([k, v]) => [k, decimal(v, 4).replace(/,/g, '')]));
const isForeignKey = (k: string) => k.startsWith('US:') || k.startsWith('manual-us:');
const without = <T,>(m: { [key: string]: T }, k: string) => {
  const next = { ...m };
  delete next[k];
  return next;
};

/** 비중 설정 만들기·편집: 이름, 대상 계좌, 예수금 포함, 강조 기준, 종목별 목표 %·매수 단위, 제외, 직접 추가. */
export function AllocationForm({ existing, onClose, onSaved }: { existing: AllocationTarget | null; onClose: () => void; onSaved?: (id: string) => void }) {
  const { uid } = useData();
  const data = useAppData();
  const [name, setName] = useState(existing?.name ?? '');
  const [accountIds, setAccountIds] = useState<string[]>(existing?.accountIds ?? (data.accounts.length === 1 ? [data.accounts[0].id] : []));
  const [includeCash, setIncludeCash] = useState(existing?.includeCash ?? false);
  // 허용 범위: 절대 기준 A (%p), 상대 기준 R (%). 새 설정은 5/25 룰, 예전 설정은 A 만 (R 없음)
  const bandText = (n: number | null) => (n !== null && n > 0 ? decimal(n, 2).replace(/,/g, '') : '');
  const [absoluteText, setAbsoluteText] = useState(existing ? bandText(existing.threshold) : String(DEFAULT_ABSOLUTE));
  const [relativeText, setRelativeText] = useState(existing ? bandText(existing.relative) : String(DEFAULT_RELATIVE));
  const [excluded, setExcluded] = useState<string[]>(existing?.excluded ?? []);
  // 지금 매수 안 함 (비중 표에는 보이고 매수 계산기에서만 뺌)
  const [noBuy, setNoBuy] = useState<string[]>(existing?.noBuy ?? []);
  // 계좌에 없는(아직 안 산) 종목: 종목 키 → 이름, 직접 입력한 현재가
  const [manual, setManual] = useState<{ [key: string]: string }>(existing?.names ?? {});
  const [priceTexts, setPriceTexts] = useState<Texts>(() => toTexts(existing?.prices));
  const [texts, setTexts] = useState<Texts>(() => toTexts(existing?.targets));
  // 매수 단위 (비우면 1주 단위, 금액이면 그 금액 단위)
  const [unitTexts, setUnitTexts] = useState<Texts>(() => toTexts(existing?.units));
  const [bulkMoney, setBulkMoney] = useState(false);
  const [bulkText, setBulkText] = useState('1000');
  // 직접 추가 입력
  const [adding, setAdding] = useState(false);
  const [addForeign, setAddForeign] = useState(true);
  const [addCode, setAddCode] = useState('');
  const [addName, setAddName] = useState('');
  const [addPrice, setAddPrice] = useState('');

  // 대상 계좌의 종목 (같은 종목은 하나로, 0주 포함)
  const items = new Map<string, string>();
  for (const h of data.holdings.filter((h) => accountIds.includes(h.accountId))) {
    if (!items.has(keyOf(h))) items.set(keyOf(h), h.name);
  }
  const held = new Set(items.keys());
  // 매수 단위 칸의 기본 표시 (금현물은 1g)
  const goldKeys = new Set(data.holdings.filter((h) => accountIds.includes(h.accountId) && h.assetType === 'GOLD').map(keyOf));
  // 직접 추가한 종목, 그리고 이미 목표가 있는데 대상 계좌에 없는 종목(지웠거나 계좌를 뺐을 때)도 남겨 둔다
  for (const [k, n] of Object.entries(manual)) if (!items.has(k)) items.set(k, n);
  for (const k of Object.keys(existing?.targets ?? {})) {
    if (k !== CASH_KEY && !items.has(k)) items.set(k, k.includes(':') ? k.slice(k.indexOf(':') + 1) : k);
  }
  const keys = [...items.keys()];
  if (includeCash) keys.push(CASH_KEY);
  const stockKeys = keys.filter((k) => k !== CASH_KEY);

  const isExcluded = (k: string) => excluded.includes(k);
  const percent = (k: string) => (isExcluded(k) ? 0 : parseDecimal(texts[k] ?? '') ?? 0);
  const sum = keys.reduce((s, k) => s + percent(k), 0);
  const absolute = parseDecimal(absoluteText);
  const relative = parseDecimal(relativeText);
  const band = { threshold: absolute ?? 0, relative: relative !== null && relative > 0 ? relative : null };
  const badNumber = (t: string | undefined, max = Infinity) => (t ?? '').trim() !== '' && ((parseDecimal(t!) ?? -1) < 0 || (parseDecimal(t!) ?? 0) > max);
  const invalidPercent = keys.some((k) => !isExcluded(k) && badNumber(texts[k], 100));
  const invalidUnit = stockKeys.some((k) => badNumber(unitTexts[k]) || badNumber(priceTexts[k]));

  const problem =
    name.trim() === '' ? '이름을 입력하세요.'
      : accountIds.length === 0 ? '대상 계좌를 고르세요.'
        : invalidPercent ? '목표 비중은 0~100% 로 입력하세요.'
          : invalidUnit ? '매수 단위·현재가를 확인하세요.'
            : Math.abs(sum - 100) > 0.01 ? `목표 합계가 100% 가 되어야 합니다 (지금 ${decimal(sum, 2)}%).`
              : badNumber(absoluteText, 100) || badNumber(relativeText, 100) ? '허용 범위의 절대·상대 기준은 0~100 으로 입력하세요.'
                : band.threshold <= 0 && band.relative === null ? '허용 범위의 절대·상대 기준 중 하나는 넣으세요.'
                  : null;

  // 직접 추가: 이름은 필수, 종목코드는 선택 (코드가 없으면 계좌 종목과 자동 연결되지 않는 키)
  const addKey = addCode.trim() === '' ? null : manualKey(addForeign, addCode);
  const addProblem =
    addName.trim() === '' && addCode.trim() === '' ? null
      : addKey !== null && items.has(addKey) ? '이미 목록에 있는 종목입니다.'
        : addCode.trim() === '' && addName.trim() === '' ? '이름을 입력하세요.'
          : null;
  const canAdd = (addName.trim() !== '' || addCode.trim() !== '') && addProblem === null && !badNumber(addPrice);
  const addManual = () => {
    if (!canAdd) return;
    const key = addKey ?? codelessKey(addForeign, Date.now().toString(36));
    setManual({ ...manual, [key]: addName.trim() || addCode.trim().toUpperCase() });
    if (addPrice.trim() !== '') setPriceTexts({ ...priceTexts, [key]: addPrice.trim() });
    setAddCode('');
    setAddName('');
    setAddPrice('');
    setAdding(false);
  };
  const removeManual = (k: string) => {
    setManual(without(manual, k));
    setTexts(without(texts, k));
    setUnitTexts(without(unitTexts, k));
    setPriceTexts(without(priceTexts, k));
  };
  // 종목코드 없이 넣은 종목을 계좌의 실제 종목으로 바꾸기: 목표 %·매수 단위를 옮긴다
  const relink = (from: string, to: string) => {
    setTexts({ ...without(texts, from), [to]: texts[from] ?? '' });
    setUnitTexts({ ...without(unitTexts, from), ...(unitTexts[from] ? { [to]: unitTexts[from] } : {}) });
    setManual(without(manual, from));
    setPriceTexts(without(priceTexts, from));
  };
  const relinkTargets = [...held].filter((k) => percent(k) === 0 && !isExcluded(k));

  const applyBulk = () => {
    const next: Texts = { ...unitTexts };
    for (const k of stockKeys) next[k] = bulkMoney ? bulkText : '';
    setUnitTexts(next);
  };

  const toggleAccount = (id: string) =>
    setAccountIds(accountIds.includes(id) ? accountIds.filter((x) => x !== id) : [...accountIds, id]);

  const numbers = (t: Texts, allowed: (k: string) => boolean) =>
    Object.fromEntries(Object.entries(t).filter(([k, v]) => allowed(k) && (parseDecimal(v) ?? 0) > 0).map(([k, v]) => [k, parseDecimal(v)!]));

  const save = async () => {
    const targets: { [key: string]: number } = {};
    for (const k of keys) if (percent(k) > 0) targets[k] = percent(k);
    const id = await saveAllocationTarget(uid, {
      id: existing?.id ?? '', name: name.trim(), accountIds, targets, includeCash, ...band, createdAt: existing?.createdAt ?? 0,
      // 지금 대상 계좌에 있는 종목만 제외 목록에 남긴다
      excluded: excluded.filter((k) => held.has(k)),
      // 직접 추가한 이름·현재가는 아직 계좌에 없는 종목만 (사서 계좌에 생기면 종목 이름·시세를 쓴다)
      names: Object.fromEntries(Object.entries(manual).filter(([k]) => !held.has(k))),
      prices: numbers(priceTexts, (k) => !held.has(k) && items.has(k)),
      units: numbers(unitTexts, (k) => items.has(k)),
      noBuy: noBuy.filter((k) => items.has(k) && !excluded.includes(k)),
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
      <div className="sub-card">
        <div className="sub-card-head">
          <span className="field-label">허용 범위 (벗어나면 부족·초과로 강조)</span>
          <button type="button" className="small-button" onClick={() => { setAbsoluteText(String(DEFAULT_ABSOLUTE)); setRelativeText(String(DEFAULT_RELATIVE)); }}>5/25 기본값</button>
        </div>
        <div className="field-row">
          <NumberField label="절대 기준 A (%p)" value={absoluteText} onChange={setAbsoluteText} placeholder="안 씀" />
          <NumberField label="상대 기준 R (%)" value={relativeText} onChange={setRelativeText} placeholder="안 씀" />
        </div>
        <p className="muted small">
          허용 편차 = min(A, 목표 × R). 하나를 비우면 그 기준은 쓰지 않습니다. 목표 0% 종목은 강조하지 않습니다.
          {band.threshold > 0 || band.relative !== null
            ? ` 예: 목표 40% → ±${decimal(toleranceBand(band, 40) ?? 0, 2)}%p, 목표 10% → ±${decimal(toleranceBand(band, 10) ?? 0, 2)}%p`
            : ''}
        </p>
      </div>

      <div className="sub-card">
        <span className="field-label">매수 단위 일괄 적용</span>
        <div className="toggle-row">
          <div className="choices" role="radiogroup" aria-label="매수 단위">
            {[false, true].map((m) => (
              <button type="button" key={String(m)} role="radio" aria-checked={bulkMoney === m} className={bulkMoney === m ? 'choice on' : 'choice'} onClick={() => setBulkMoney(m)}>
                {bulkMoney === m && <span aria-hidden="true">✓ </span>}
                {m ? '금액 단위' : '1주 단위'}
              </button>
            ))}
          </div>
          {bulkMoney && (
            <span className="unit-input">
              <input inputMode="numeric" aria-label="일괄 금액 단위 (원)" value={bulkText} onChange={(e) => setBulkText(e.target.value.replace(/[^0-9]/g, '').slice(0, 9))} />원
            </span>
          )}
          <button type="button" className="small-button" onClick={applyBulk} disabled={bulkMoney && !(Number(bulkText) > 0)}>모든 종목에 적용</button>
        </div>
        <span className="field-hint">적용한 뒤에도 아래에서 종목마다 다시 고칠 수 있습니다. 매수 단위를 비우면 1주 단위입니다.</span>
      </div>

      <div className="field">
        <span className="field-label">목표 비중 (%)</span>
        <span className="field-hint">아직 안 산 종목(상장 전 공모주, 장외 채권 등)은 아래 [+ 계좌에 없는 종목 직접 추가]로 넣을 수 있습니다. 비중에서 뺄 종목은 [제외]를 고르세요.</span>
        {keys.map((k) => {
          const label = k === CASH_KEY ? '예수금' : items.get(k) ?? k;
          const off = isExcluded(k);
          const unheld = k !== CASH_KEY && !held.has(k);
          return (
            <div key={k} className={off ? 'target-item excluded' : 'target-item'}>
              <div className="target-row">
                <span>
                  {label}{' '}
                  <span className="muted small">
                    {k === CASH_KEY || k.startsWith('id:') || isCodeless(k) ? '' : k}
                    {off ? ' · 제외' : unheld ? ' · 미보유' : ''}
                    {!off && noBuy.includes(k) ? ' · 지금 매수 안 함' : ''}
                  </span>
                </span>
                <span className="target-inputs">
                  {off ? (
                    <span className="muted small">계산에서 뺌</span>
                  ) : (
                    <span className="unit-input">
                      <input inputMode="decimal" aria-label={`${label} 목표 %`} value={texts[k] ?? ''} placeholder="0" onChange={(e) => setTexts({ ...texts, [k]: e.target.value.replace(/[^0-9.]/g, '').slice(0, 6) })} />%
                    </span>
                  )}
                  {unheld && <button type="button" className="icon-button" aria-label={`${label} 빼기`} onClick={() => removeManual(k)}>✕</button>}
                  {k !== CASH_KEY && held.has(k) && (
                    <label className="check small">
                      <input type="checkbox" checked={off} onChange={() => setExcluded(off ? excluded.filter((x) => x !== k) : [...excluded, k])} />
                      제외
                    </label>
                  )}
                </span>
              </div>
              {k !== CASH_KEY && !off && (
                <div className="target-sub">
                  <span className="unit-input">
                    매수 단위 <input inputMode="numeric" aria-label={`${label} 매수 단위 (원)`} placeholder={goldKeys.has(k) ? '1g' : '1주'} value={unitTexts[k] ?? ''} onChange={(e) => setUnitTexts({ ...unitTexts, [k]: e.target.value.replace(/[^0-9]/g, '').slice(0, 9) })} />원
                  </span>
                  {unheld && (
                    <span className="unit-input">
                      현재가 <input inputMode="decimal" aria-label={`${label} 현재가`} placeholder="모름" value={priceTexts[k] ?? ''} onChange={(e) => setPriceTexts({ ...priceTexts, [k]: e.target.value.replace(/[^0-9.]/g, '').slice(0, 12) })} />
                      {isForeignKey(k) ? '$' : '원'}
                    </span>
                  )}
                  <label className="check small">
                    <input type="checkbox" checked={noBuy.includes(k)} onChange={() => setNoBuy(noBuy.includes(k) ? noBuy.filter((x) => x !== k) : [...noBuy, k])} />
                    지금 매수 안 함
                  </label>
                  {isCodeless(k) && relinkTargets.length > 0 && (
                    <select aria-label={`${label} 계좌 종목으로 바꾸기`} value="" onChange={(e) => e.target.value && relink(k, e.target.value)}>
                      <option value="">계좌 종목으로 바꾸기…</option>
                      {relinkTargets.map((t) => (
                        <option key={t} value={t}>{items.get(t)}</option>
                      ))}
                    </select>
                  )}
                </div>
              )}
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
              <TextField label="종목 이름" placeholder={addForeign ? '예: Invesco QQQM' : '예: 삼성전자, 장외 국채 2030'} value={addName} onChange={setAddName} maxLength={60} />
              <TextField label="종목코드 (없으면 비워 두기)" placeholder={addForeign ? '예: QQQM' : '예: 005930'} value={addCode} onChange={(v) => setAddCode(v.replace(/[^0-9A-Za-z.\-]/g, '').slice(0, 20))} />
            </div>
            <NumberField label={`현재가 (선택, ${addForeign ? '달러' : '원'})`} value={addPrice} onChange={setAddPrice} preview={(n) => (addForeign ? `$${decimal(n, 2)}` : `${decimal(n, 0)}원`)} />
            <span className="field-hint">종목코드가 있으면 나중에 사서 계좌에 생길 때 자동으로 연결됩니다. 없으면 [계좌 종목으로 바꾸기]로 연결하세요.</span>
            {addProblem && <p className="warning">{addProblem}</p>}
            <div className="confirm-buttons">
              <button type="button" onClick={() => setAdding(false)}>취소</button>
              <button type="button" className="primary" disabled={!canAdd} onClick={addManual}>추가</button>
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
