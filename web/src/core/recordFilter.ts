// core 의 RecordFilter 와 같은 규칙: 기록 유형 필터. 뺀 유형(hidden)을 기억하므로 기본은 전부 보임(= 전체), 새로 생긴 유형도 보인다.
import { RECORD_TYPE_LABEL, type Record, type RecordType } from '../model';

/** 칩 순서. */
export const RECORD_FILTER_ORDER: readonly RecordType[] = ['DEPOSIT', 'WITHDRAW', 'TRANSFER', 'BUY', 'SELL', 'DIVIDEND', 'CASH_BALANCE', 'EXCHANGE', 'CASH_ADJUST'];

export const recordFilterLabel = (type: RecordType): string => (type === 'TRANSFER' ? '이체' : RECORD_TYPE_LABEL[type]);

/** 칩으로 보여줄 유형: 기록에 있는 유형만, 정해진 순서로. */
export function presentTypes(records: readonly Record[]): RecordType[] {
  const types = new Set(records.map((r) => r.type));
  return RECORD_FILTER_ORDER.filter((t) => types.has(t));
}

export const applyRecordFilter = (records: readonly Record[], hidden: ReadonlySet<RecordType>): Record[] =>
  hidden.size === 0 ? [...records] : records.filter((r) => !hidden.has(r.type));

/** 칩에 있는 유형이 모두 보이는지 ([전체] 칩이 켜진 상태). */
export const allShown = (present: readonly RecordType[], hidden: ReadonlySet<RecordType>): boolean => present.every((t) => !hidden.has(t));

/** [전체] 칩: 모두 보이면 전체 해제(모든 유형을 뺌), 아니면 전체 선택. */
export const toggleAll = (present: readonly RecordType[], hidden: ReadonlySet<RecordType>): Set<RecordType> =>
  allShown(present, hidden) ? new Set(RECORD_FILTER_ORDER) : new Set();

/** 유형 칩 하나 켜기·끄기. */
export function toggleType(type: RecordType, hidden: ReadonlySet<RecordType>): Set<RecordType> {
  const next = new Set(hidden);
  if (next.has(type)) next.delete(type);
  else next.add(type);
  return next;
}
