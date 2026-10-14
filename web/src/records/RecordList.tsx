import { useEffect, useRef, useState } from 'react';
import { formatDate } from '../core/dates';
import { won } from '../core/format';
import { useAppData } from '../data/DataContext';
import type { Record, RecordType } from '../model';
import { recordAmountText, recordDetailText, recordTitle } from './recordText';

/** 기록 한 줄 (누르면 수정 창). cumulative 는 입출금·이체 기록의 누적 투자금. */
export function RecordRow({ record, accountId, onClick, cumulative }: { record: Record; accountId: string; onClick: () => void; cumulative?: number }) {
  const data = useAppData();
  return (
    <button className="list-row record-row" onClick={onClick}>
      <span className="list-main">
        <span className="list-title">{recordTitle(record, data.lookup, accountId)}</span>
        <span className="list-sub">{[formatDate(record.date), recordDetailText(record, data.lookup)].filter(Boolean).join(' · ')}</span>
      </span>
      <span className="list-end">
        <span className="list-value">{recordAmountText(record, data.lookup, accountId)}</span>
        {cumulative !== undefined && <span className="list-sub">누적 투자금 {won(cumulative)}</span>}
      </span>
    </button>
  );
}

/** "+ 기록 추가" 버튼과 유형 메뉴. */
export function AddRecordMenu({ types, label, onSelect }: { types: readonly RecordType[]; label: (t: RecordType) => string; onSelect: (t: RecordType) => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);
  return (
    <div className="menu-wrap" ref={ref}>
      <button className="primary small-button" onClick={() => setOpen(!open)} aria-expanded={open}>+ 기록 추가</button>
      {open && (
        <div className="menu" role="menu">
          {types.map((t) => (
            <button key={t} role="menuitem" onClick={() => { setOpen(false); onSelect(t); }}>{label(t)}</button>
          ))}
        </div>
      )}
    </div>
  );
}
