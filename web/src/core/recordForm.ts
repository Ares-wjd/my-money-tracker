// 기록 입력 검사와 기록 만들기. 폰 RecordEditScreen 과 같은 규칙·문구.
import { decimal } from './format';
import { quantityAt } from './portfolio';
import type { Currency, Holding, Record, RecordType } from '../model';

export interface RecordInput {
  type: RecordType;
  date: string;
  currency: Currency;
  amount: number | null;
  krw: number | null;
  rate: number | null;
  toAccountId: string | null;
  holdingId: string | null;
  quantity: number | null;
  price: number | null;
  cost: CostInput;
  memo: string;
}

/**
 * 매수·매도의 제비용 입력 (core CostInput 과 같음). 수수료·세금을 쓰면 제비용합은 그 합(제비용합 칸은 잠김),
 * 제비용합만 쓰면 수수료·세금은 0 (두 칸은 잠김). 빈 칸은 null.
 */
export interface CostInput {
  total: number | null;
  fee: number | null;
  tax: number | null;
}

export const NO_COST: CostInput = { total: null, fee: null, tax: null };
/** 수수료·세금으로 나눠 쓰는 중인지 (= 제비용합 칸을 잠글지) */
export const costBreakdown = (c: CostInput): boolean => c.fee !== null || c.tax !== null;
/** 수수료·세금 칸을 잠글지 (제비용합만 쓰는 중) */
export const costPartsLocked = (c: CostInput): boolean => !costBreakdown(c) && c.total !== null;
/** 제비용합 */
export const effectiveCost = (c: CostInput): number => (costBreakdown(c) ? (c.fee ?? 0) + (c.tax ?? 0) : c.total ?? 0);

/** 수정할 기록 → 제비용 입력: 수수료나 세금이 있으면 나눠서, 아니면 제비용합으로. */
export function costInputOf(r: Pick<Record, 'fee' | 'tax' | 'cost'> | null): CostInput {
  if (!r) return NO_COST;
  if (r.fee !== 0 || r.tax !== 0) return { total: null, fee: r.fee !== 0 ? r.fee : null, tax: r.tax !== 0 ? r.tax : null };
  return { total: r.cost !== null && r.cost !== 0 ? r.cost : null, fee: null, tax: null };
}

/** 문제가 있으면 안내 문구, 없으면 null. */
export function recordProblem(
  input: RecordInput,
  holdings: readonly Holding[],
  allRecords: readonly Record[],
  editingId: string | null,
): string | null {
  const { type, amount, krw, rate, quantity, price } = input;
  const holding = holdings.find((h) => h.id === input.holdingId);
  switch (type) {
    case 'DEPOSIT':
    case 'WITHDRAW':
    case 'TRANSFER':
      if (type === 'TRANSFER' && input.toAccountId === null) return '받는 계좌를 고르세요.';
      if (amount === null || amount <= 0) return '금액을 입력하세요.';
      if (input.currency === 'USD' && (rate === null || rate <= 0)) return '원화 환산에 쓸 환율을 입력하세요.';
      return null;
    case 'EXCHANGE':
      if (krw === null || krw <= 0) return '원화 금액을 입력하세요.';
      if (amount === null || amount <= 0) return '달러 금액을 입력하세요.';
      return null;
    case 'BUY':
    case 'SELL': {
      if (!holding) return holdings.length === 0 ? '먼저 계좌 화면에서 종목을 추가하세요.' : '종목을 고르세요.';
      if (quantity === null || quantity <= 0) return '수량을 입력하세요.';
      if (price === null || price < 0) return '단가를 입력하세요.';
      if (type === 'SELL') {
        const held = quantityAt(holding, allRecords, input.date, editingId ?? undefined);
        if (quantity > held + 1e-9) return `매도 수량이 그날 보유 수량(${decimal(held)})보다 많습니다.`;
      }
      return null;
    }
    case 'DIVIDEND':
      if (!holding) return holdings.length === 0 ? '먼저 계좌 화면에서 종목을 추가하세요.' : '종목을 고르세요.';
      if (amount === null || amount <= 0) return '배당금을 입력하세요.';
      return null;
    case 'CASH_ADJUST':
      return amount === null || amount === 0 ? '조정 금액을 입력하세요.' : null;
    case 'CASH_BALANCE':
      return amount === null ? '예수금을 입력하세요.' : null;
  }
}

/** 입력으로 기록을 만든다. [base] 는 수정 중인 기록 (새 기록이면 null). */
export function buildRecord(input: RecordInput, accountId: string, base: Record | null): Record {
  const common: Record = {
    id: base?.id ?? '',
    accountId,
    type: input.type,
    date: input.date,
    currency: 'KRW',
    amount: 0,
    krwAmount: 0,
    toAccountId: null,
    holdingId: null,
    quantity: 0,
    price: 0,
    fee: 0,
    tax: 0,
    cost: null,
    initial: base?.initial ?? false,
    externalId: base?.externalId ?? null,
    memo: input.memo.trim(),
    createdAt: base?.createdAt ?? 0,
  };
  const amount = input.amount ?? 0;
  switch (input.type) {
    case 'DEPOSIT':
    case 'WITHDRAW':
    case 'TRANSFER':
      return {
        ...common,
        currency: input.currency,
        amount,
        krwAmount: input.currency === 'KRW' ? amount : amount * (input.rate ?? 0),
        toAccountId: input.type === 'TRANSFER' ? input.toAccountId : null,
      };
    case 'EXCHANGE':
      return { ...common, currency: input.currency, amount, krwAmount: input.krw ?? 0 };
    case 'BUY':
    case 'SELL':
    {
      // 수수료·세금·제비용합을 모두 저장한다
      const c = input.cost;
      const costs = costBreakdown(c) ? { fee: c.fee ?? 0, tax: c.tax ?? 0, cost: effectiveCost(c) } : { fee: 0, tax: 0, cost: c.total ?? 0 };
      return { ...common, holdingId: input.holdingId, quantity: input.quantity ?? 0, price: input.price ?? 0, ...costs };
    }
    case 'DIVIDEND':
      return { ...common, holdingId: input.holdingId, amount };
    case 'CASH_ADJUST':
    case 'CASH_BALANCE':
      return { ...common, currency: input.currency, amount };
  }
}

/** 수정할 기록 → 입력값 (환율은 기존 기록에서 거꾸로 구한다). */
export function inputFromRecord(r: Record, fallbackRate: number | null): RecordInput {
  const rate = r.amount > 0 && (r.currency === 'USD' || r.type === 'EXCHANGE') ? r.krwAmount / r.amount : fallbackRate;
  return {
    type: r.type,
    date: r.date,
    currency: r.currency,
    amount: r.amount === 0 ? null : r.amount,
    krw: r.krwAmount === 0 ? null : r.krwAmount,
    rate,
    toAccountId: r.toAccountId,
    holdingId: r.holdingId,
    quantity: r.quantity === 0 ? null : r.quantity,
    price: r.price === 0 && r.type !== 'BUY' && r.type !== 'SELL' ? null : r.price,
    cost: costInputOf(r),
    memo: r.memo,
  };
}
