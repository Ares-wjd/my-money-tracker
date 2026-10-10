// 폰 앱 RecordText 와 같은 기록 표시.
import { amount, decimal, signedAmount, signedUsd, signedWon } from '../core/format';
import type { Holding, InvestmentAccount, Record } from '../model';
import { CURRENCY_LABEL, RECORD_TYPE_LABEL, marketCurrency } from '../model';

export interface RecordLookup {
  holding: (id: string) => Holding | undefined;
  account: (id: string) => InvestmentAccount | undefined;
}

/** 기록 목록에 보여줄 제목. [accountId] 는 지금 보고 있는 계좌 (이체 방향 표시용). */
export function recordTitle(record: Record, lookup: RecordLookup, accountId: string): string {
  const holdingName = (record.holdingId && lookup.holding(record.holdingId)?.name) || '삭제된 종목';
  switch (record.type) {
    case 'BUY':
      return record.initial ? `초기 보유 · ${holdingName}` : `매수 · ${holdingName}`;
    case 'SELL':
      return `매도 · ${holdingName}`;
    case 'DIVIDEND':
      return `배당 · ${holdingName}`;
    case 'TRANSFER':
      return record.accountId === accountId
        ? `이체 → ${(record.toAccountId && lookup.account(record.toAccountId)?.name) || '삭제된 계좌'}`
        : `이체 ← ${lookup.account(record.accountId)?.name ?? '삭제된 계좌'}`;
    case 'EXCHANGE':
      return record.currency === 'KRW' ? '환전 원화 → 달러' : '환전 달러 → 원화';
    case 'CASH_ADJUST':
      return '예수금 조정 (예전 기록, 계산 안 함)';
    case 'CASH_BALANCE':
      return `예수금 (${CURRENCY_LABEL[record.currency]})`;
    default:
      return RECORD_TYPE_LABEL[record.type];
  }
}

/** 계좌 입장에서의 금액 변화 표시. */
export function recordAmountText(record: Record, lookup: RecordLookup, accountId: string): string {
  const holding = record.holdingId ? lookup.holding(record.holdingId) : undefined;
  const holdingCurrency = holding ? marketCurrency(holding.market) : 'KRW';
  switch (record.type) {
    case 'DEPOSIT':
      return signedAmount(record.currency, record.amount);
    case 'WITHDRAW':
      return signedAmount(record.currency, -record.amount);
    case 'TRANSFER':
      return signedAmount(record.currency, (record.accountId === accountId ? -1 : 1) * record.amount);
    case 'EXCHANGE':
      return record.currency === 'KRW'
        ? `${signedWon(-record.krwAmount)} / ${signedUsd(record.amount)}`
        : `${signedUsd(-record.amount)} / ${signedWon(record.krwAmount)}`;
    case 'BUY':
      return signedAmount(holdingCurrency, -(record.quantity * record.price + record.fee + record.tax));
    case 'SELL':
      return signedAmount(holdingCurrency, record.quantity * record.price - record.fee - record.tax);
    case 'DIVIDEND':
      return signedAmount(holdingCurrency, record.amount);
    case 'CASH_ADJUST':
      return signedAmount(record.currency, record.amount);
    case 'CASH_BALANCE':
      return amount(record.currency, record.amount);
  }
}

/** 매매 기록의 부가 설명 (수량 × 단가), 그 외에는 메모. */
export function recordDetailText(record: Record, lookup: RecordLookup): string | null {
  if (record.type === 'BUY' || record.type === 'SELL') {
    const holding = record.holdingId ? lookup.holding(record.holdingId) : undefined;
    const currency = holding ? marketCurrency(holding.market) : 'KRW';
    return `${decimal(record.quantity)}주 × ${amount(currency, record.price)}`;
  }
  return record.memo.trim() === '' ? null : record.memo;
}
