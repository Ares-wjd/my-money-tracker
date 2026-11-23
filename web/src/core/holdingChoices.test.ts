// core HoldingChoicesTest 와 같은 케이스 (v2.17).
import { describe, expect, it } from 'vitest';
import type { Holding, Record, RecordType } from '../model';
import { canHide, hiddenCount, holdingChoices, noHoldingMessage } from './holdingChoices';
import { holding, record } from './testData';

const held = holding({ id: 'held', accountId: 'a', name: '보유' });
const sold = holding({ id: 'sold', accountId: 'a', name: '다 판 종목' });
const hidden = holding({ id: 'hidden', accountId: 'a', name: '숨긴 종목', hidden: true });
const other = holding({ id: 'other', accountId: 'b', name: '다른 계좌' });
const holdings: Holding[] = [held, sold, hidden, other];
const records: Record[] = [
  record({ id: '1', accountId: 'a', type: 'BUY', date: '2026-11-01', holdingId: 'held', quantity: 10, price: 100 }),
  record({ id: '2', accountId: 'a', type: 'BUY', date: '2026-11-01', holdingId: 'sold', quantity: 5, price: 100 }),
  record({ id: '3', accountId: 'a', type: 'SELL', date: '2026-11-02', holdingId: 'sold', quantity: 5, price: 120 }),
  record({ id: '4', accountId: 'b', type: 'BUY', date: '2026-11-01', holdingId: 'other', quantity: 1, price: 100 }),
];
const ids = (type: RecordType, current: string | null = null) => holdingChoices(type, 'a', holdings, records, current).map((h) => h.id);

describe('종목 숨기기', () => {
  it('매수·배당은 숨긴 종목을 뺀다', () => {
    expect(ids('BUY')).toEqual(['held', 'sold']);
    expect(ids('DIVIDEND')).toEqual(['held', 'sold']);
  });
  it('매도는 지금 보유한 종목만', () => {
    expect(ids('SELL')).toEqual(['held']);
  });
  it('고치는 기록의 종목은 남긴다', () => {
    expect(ids('BUY', 'hidden')).toEqual(['held', 'sold', 'hidden']);
    expect(ids('SELL', 'sold')).toEqual(['held', 'sold']);
  });
  it('0주일 때만 숨길 수 있다', () => {
    expect(canHide(held, records)).toBe(false);
    expect(canHide(sold, records)).toBe(true);
    expect(hiddenCount('a', holdings)).toBe(1);
    expect(hiddenCount('b', holdings)).toBe(0);
  });
  it('안내 문구', () => {
    expect(noHoldingMessage('SELL', [], 1)).toBe('지금 보유한 종목이 없습니다.');
    expect(noHoldingMessage('BUY', [], 2)).toBe('숨긴 종목 (2)은 계좌 화면에서 다시 보이게 할 수 있어요.');
    expect(noHoldingMessage('BUY', [], 0)).toBe('먼저 계좌 화면에서 종목을 추가하세요.');
  });
});
