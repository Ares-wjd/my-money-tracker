// core RecordFilterTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { allShown, applyRecordFilter, presentTypes, recordFilterLabel, toggleAll, toggleType } from './recordFilter';
import { record } from './testData';

const day = '2026-10-01';
const records = [
  record({ id: '1', accountId: 'a', type: 'BUY', date: day }),
  record({ id: '2', accountId: 'a', type: 'DEPOSIT', date: day }),
  record({ id: '3', accountId: 'a', type: 'SELL', date: day }),
  record({ id: '4', accountId: 'a', type: 'WITHDRAW', date: day }),
];

describe('기록 유형 필터 (RecordFilterTest)', () => {
  it('기본은 전체, [전체]는 전체 선택·해제', () => {
    const present = presentTypes(records);
    expect(present).toEqual(['DEPOSIT', 'WITHDRAW', 'BUY', 'SELL']);
    expect(recordFilterLabel('TRANSFER')).toBe('이체');
    expect(allShown(present, new Set())).toBe(true);
    expect(applyRecordFilter(records, new Set())).toEqual(records);
    const none = toggleAll(present, new Set());
    expect(applyRecordFilter(records, none)).toEqual([]);
    const trades = toggleType('SELL', toggleType('BUY', none));
    expect(applyRecordFilter(records, trades).map((r) => r.id)).toEqual(['1', '3']);
    expect(allShown(present, trades)).toBe(false);
    expect(toggleAll(present, trades).size).toBe(0);
  });
});
