// core HoldingChoices 와 같은 규칙 (docs/FEATURES.md 1-2-3, v2.17): 기록 입력에서 고를 수 있는 종목.
import type { Holding, Record, RecordType } from '../model';
import { position } from './portfolio';

/** 지금 보유 수량. */
export const holdingQuantity = (h: Holding, records: readonly Record[]): number =>
  position(h, records.filter((r) => r.holdingId === h.id)).quantity;

/** 지금 0주인지 (숨길 수 있는지). */
export const canHide = (h: Holding, records: readonly Record[]): boolean => holdingQuantity(h, records) <= 1e-9;

/**
 * accountId 계좌에서 type 기록에 고를 수 있는 종목. 매도는 지금 보유 수량이 있는 종목만, 그 밖에는 숨기지 않은 종목.
 * currentId 는 고치는 기록의 종목: 숨겼거나 0주여도 남긴다. 순서는 holdings 그대로.
 */
export function holdingChoices(
  type: RecordType,
  accountId: string,
  holdings: readonly Holding[],
  records: readonly Record[],
  currentId: string | null = null,
): Holding[] {
  return holdings.filter(
    (h) => h.accountId === accountId && (h.id === currentId || (type === 'SELL' ? holdingQuantity(h, records) > 1e-9 : !h.hidden)),
  );
}

/** accountId 계좌의 숨긴 종목 수. */
export const hiddenCount = (accountId: string, holdings: readonly Holding[]): number =>
  holdings.filter((h) => h.accountId === accountId && h.hidden).length;

/** 고를 종목이 없을 때의 안내 (폰 RecordEditScreen 과 같은 문구). */
export function noHoldingMessage(type: RecordType, choices: readonly Holding[], hidden: number): string {
  if (choices.length > 0) return '종목을 고르세요.';
  if (type === 'SELL') return '지금 보유한 종목이 없습니다.';
  if (hidden > 0) return `숨긴 종목 (${hidden})은 계좌 화면에서 다시 보이게 할 수 있어요.`;
  return '먼저 계좌 화면에서 종목을 추가하세요.';
}
