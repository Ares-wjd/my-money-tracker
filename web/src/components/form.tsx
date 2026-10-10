import { useEffect, useState, type ReactNode } from 'react';
import { parseDecimal } from '../core/format';

/** 가운데 입력 창. Esc 나 바깥을 누르면 닫힌다. */
export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal-head">
          <h2>{title}</h2>
          <button className="icon-button" onClick={onClose} aria-label="닫기">✕</button>
        </div>
        <div className="modal-body">{children}</div>
      </div>
    </div>
  );
}

export function Field({ label, hint, children }: { label: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

export function TextField(props: { label: string; value: string; onChange: (v: string) => void; maxLength?: number; placeholder?: string; multiline?: boolean; hint?: ReactNode }) {
  return (
    <Field label={props.label} hint={props.hint}>
      {props.multiline ? (
        <textarea value={props.value} maxLength={props.maxLength} placeholder={props.placeholder} rows={3} onChange={(e) => props.onChange(e.target.value)} />
      ) : (
        <input value={props.value} maxLength={props.maxLength} placeholder={props.placeholder} onChange={(e) => props.onChange(e.target.value)} />
      )}
    </Field>
  );
}

/** 숫자 입력. 쉼표는 무시하고, 아래에 읽은 값을 보여준다 (예: "1,234원"). */
export function NumberField(props: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  preview?: (n: number) => string;
  allowNegative?: boolean;
  allowDecimal?: boolean;
  placeholder?: string;
}) {
  const parsed = parseDecimal(props.value);
  const pattern = props.allowNegative ? /[^0-9.,-]/g : /[^0-9.,]/g;
  return (
    <Field label={props.label} hint={parsed !== null && props.preview ? props.preview(parsed) : undefined}>
      <input
        inputMode="decimal"
        value={props.value}
        placeholder={props.placeholder}
        onChange={(e) => {
          let v = e.target.value.replace(pattern, '');
          if (props.allowDecimal === false) v = v.replace(/\./g, '');
          props.onChange(v.slice(0, 20));
        }}
      />
    </Field>
  );
}

export function DateField({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return (
    <Field label={label}>
      <input type="date" value={value} onChange={(e) => e.target.value && onChange(e.target.value)} />
    </Field>
  );
}

export function Choice<T extends string>(props: { label: string; options: readonly T[]; value: T | null; text: (v: T) => string; onChange: (v: T) => void }) {
  return (
    <div className="field">
      <span className="field-label">{props.label}</span>
      <div className="choices" role="radiogroup" aria-label={props.label}>
        {props.options.map((o) => (
          <button type="button" key={o} role="radio" aria-checked={props.value === o} className={props.value === o ? 'choice on' : 'choice'} onClick={() => props.onChange(o)}>
            {props.text(o)}
          </button>
        ))}
      </div>
    </div>
  );
}

/** 숫자 → 입력칸 문자열 (불필요한 0 없이, 쉼표 없이). */
export function numberText(value: number | null | undefined, digits = 6): string {
  if (value === null || value === undefined || value === 0) return '';
  return String(Number(value.toFixed(digits)));
}

/** 저장 버튼·삭제(한 번 더 확인)·오류 표시. 저장 중에는 버튼을 막는다. */
export function FormActions(props: {
  problem?: string | null;
  onSave: () => Promise<unknown>;
  onDone: () => void;
  onDelete?: () => Promise<unknown>;
  deleteLabel?: string;
  deleteWarning?: string;
  saveLabel?: string;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      setConfirming(false);
      props.onDone();
    } catch (e) {
      setError(`저장 실패: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="form-actions">
      {props.problem && <p className="warning">{props.problem}</p>}
      {error && <p className="error">{error}</p>}
      <button className="primary" disabled={busy || !!props.problem} onClick={() => run(props.onSave)}>
        {busy ? '저장 중…' : props.saveLabel ?? '저장'}
      </button>
      {props.onDelete && !confirming && (
        <button className="danger-outline" disabled={busy} onClick={() => setConfirming(true)}>
          {props.deleteLabel ?? '삭제'}
        </button>
      )}
      {props.onDelete && confirming && (
        <div className="confirm">
          <p>{props.deleteWarning ?? '삭제할까요? 되돌릴 수 없습니다.'}</p>
          <div className="confirm-buttons">
            <button onClick={() => setConfirming(false)} disabled={busy}>취소</button>
            <button className="danger" disabled={busy} onClick={() => run(props.onDelete!)}>삭제</button>
          </div>
        </div>
      )}
    </div>
  );
}
