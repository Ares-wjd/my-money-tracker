// 테스트용 모델 생성 도우미 (core 테스트의 기본값과 같게).
import type { Holding, InvestmentAccount, Record, SavingsAccount, SavingsGoal } from '../model';

export const account = (p: Partial<InvestmentAccount> & { id: string; name: string }): InvestmentAccount => ({
  kind: 'GENERAL', number: '', memo: '', createdAt: 0, ...p,
});

export const holding = (p: Partial<Holding> & { id: string; accountId: string; name: string }): Holding => ({
  code: '', market: 'KR', assetType: 'STOCK', manualPrice: null, manualPriceDate: null, createdAt: 0, ...p,
});

export const record = (p: Partial<Record> & Pick<Record, 'id' | 'accountId' | 'type' | 'date'>): Record => ({
  currency: 'KRW', amount: 0, krwAmount: 0, toAccountId: null, holdingId: null, quantity: 0, price: 0, fee: 0, tax: 0,
  initial: false, externalId: null, memo: '', createdAt: 0, ...p,
});

export const savingsAccount = (p: Partial<SavingsAccount> & { id: string; name: string }): SavingsAccount => ({
  subAccounts: [], balanceDate: null, annualRate: 0, memo: '', createdAt: 0, ...p,
});

export const goal = (p: Partial<SavingsGoal> & Pick<SavingsGoal, 'accountId' | 'name' | 'amount' | 'dueDate'>): SavingsGoal => ({
  id: '', type: 'ONE_TIME', intervalMonths: 12, createdAt: 0, ...p,
});
