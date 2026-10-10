// 모델 → Firestore 문서. 폰(PortfolioRepository·SavingsRepository)이 저장하는 필드와 똑같이 맞춘다
// (Firestore 보안 규칙이 필드 이름·형식을 검사한다).
import type { Holding, InvestmentAccount, Record, SavingsAccount, SavingsGoal } from '../model';
import { savingsBalance } from '../model';

const createdAt = (value: number) => (value > 0 ? value : Date.now());

export function accountDoc(a: InvestmentAccount) {
  return { name: a.name, kind: a.kind, number: a.number, memo: a.memo, createdAt: createdAt(a.createdAt) };
}

export function holdingDoc(h: Holding) {
  return {
    accountId: h.accountId,
    name: h.name,
    code: h.code,
    market: h.market,
    assetType: h.assetType,
    manualPrice: h.manualPrice,
    manualPriceDate: h.manualPriceDate,
    createdAt: createdAt(h.createdAt),
  };
}

export function recordDoc(r: Record) {
  return {
    accountId: r.accountId,
    type: r.type,
    date: r.date,
    currency: r.currency,
    amount: r.amount,
    krwAmount: r.krwAmount,
    toAccountId: r.toAccountId,
    holdingId: r.holdingId,
    quantity: r.quantity,
    price: r.price,
    fee: r.fee,
    tax: r.tax,
    initial: r.initial,
    externalId: r.externalId,
    memo: r.memo,
    createdAt: createdAt(r.createdAt),
  };
}

export function savingsAccountDoc(a: SavingsAccount) {
  return {
    name: a.name,
    subAccounts: a.subAccounts.map((s) => ({ name: s.name, number: s.number, balance: s.balance })),
    // 예전 버전·조회 편의를 위해 합계도 함께 저장한다 (폰과 같음).
    balance: savingsBalance(a),
    balanceDate: a.balanceDate,
    annualRate: a.annualRate,
    memo: a.memo,
    createdAt: createdAt(a.createdAt),
  };
}

export function savingsGoalDoc(g: SavingsGoal) {
  return {
    accountId: g.accountId,
    name: g.name,
    type: g.type,
    amount: g.amount,
    dueDate: g.dueDate,
    intervalMonths: Math.round(g.intervalMonths),
    createdAt: createdAt(g.createdAt),
  };
}

/** 예수금 기록 문서 ID: 같은 계좌·날짜·통화로 다시 입력하면 덮어쓴다 (폰과 같은 규칙). */
export function cashRecordId(accountId: string, date: string, currency: string): string {
  return `cash_${accountId}_${date.replace(/-/g, '')}_${currency}`.replace(/[^A-Za-z0-9_-]/g, '_');
}

/** 원화는 원 단위, 달러는 센트 단위로 반올림 (폰과 같음). */
export function roundCash(currency: 'KRW' | 'USD', value: number): number {
  return currency === 'KRW' ? Math.round(value) : Math.round(value * 100) / 100;
}
