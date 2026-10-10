// Firestore 쓰기. 삭제는 폰과 같이 딸린 문서(종목·기록·목표)를 한 번에 지운다.
import { collection, deleteDoc, doc, setDoc, writeBatch, type DocumentReference } from 'firebase/firestore';
import { firebase } from '../firebase';
import type { Holding, InvestmentAccount, Record, SavingsAccount, SavingsGoal } from '../model';
import { accountDoc, holdingDoc, recordDoc, savingsAccountDoc, savingsGoalDoc } from './serialize';

function db() {
  if (!firebase) throw new Error('Firebase 설정이 없습니다.');
  return firebase.db;
}

const col = (uid: string, name: string) => collection(db(), 'users', uid, name);
const ref = (uid: string, name: string, id: string): DocumentReference =>
  id ? doc(col(uid, name), id) : doc(col(uid, name));

/** 저장하고 문서 ID 를 돌려준다. */
async function save(uid: string, name: string, id: string, data: object): Promise<string> {
  const r = ref(uid, name, id);
  await setDoc(r, data);
  return r.id;
}

export const saveAccount = (uid: string, a: InvestmentAccount) => save(uid, 'accounts', a.id, accountDoc(a));
export const saveHolding = (uid: string, h: Holding) => save(uid, 'holdings', h.id, holdingDoc(h));
export const saveRecord = (uid: string, r: Record) => save(uid, 'records', r.id, recordDoc(r));
export const saveSavingsAccount = (uid: string, a: SavingsAccount) => save(uid, 'savingsAccounts', a.id, savingsAccountDoc(a));
export const saveSavingsGoal = (uid: string, g: SavingsGoal) => save(uid, 'savingsGoals', g.id, savingsGoalDoc(g));

export const deleteRecord = (uid: string, id: string) => deleteDoc(doc(col(uid, 'records'), id));
export const deleteSavingsGoal = (uid: string, id: string) => deleteDoc(doc(col(uid, 'savingsGoals'), id));

export function saveManualUsdKrw(uid: string, rate: number | null) {
  return setDoc(doc(db(), 'users', uid, 'settings', 'app'), { manualUsdKrw: rate }, { merge: true });
}

/** 계좌와 그 계좌의 종목·기록(이 계좌가 받는 이체 포함)을 함께 삭제한다. */
export function deleteAccount(uid: string, accountId: string, holdings: readonly Holding[], records: readonly Record[]) {
  const batch = writeBatch(db());
  batch.delete(doc(col(uid, 'accounts'), accountId));
  holdings.filter((h) => h.accountId === accountId).forEach((h) => batch.delete(doc(col(uid, 'holdings'), h.id)));
  records
    .filter((r) => r.accountId === accountId || r.toAccountId === accountId)
    .forEach((r) => batch.delete(doc(col(uid, 'records'), r.id)));
  return batch.commit();
}

/** 종목과 그 종목의 매매·배당 기록을 함께 삭제한다. */
export function deleteHolding(uid: string, holdingId: string, records: readonly Record[]) {
  const batch = writeBatch(db());
  batch.delete(doc(col(uid, 'holdings'), holdingId));
  records.filter((r) => r.holdingId === holdingId).forEach((r) => batch.delete(doc(col(uid, 'records'), r.id)));
  return batch.commit();
}

/** 목적통장과 그 목표를 함께 삭제한다. */
export function deleteSavingsAccount(uid: string, accountId: string, goals: readonly SavingsGoal[]) {
  const batch = writeBatch(db());
  batch.delete(doc(col(uid, 'savingsAccounts'), accountId));
  goals.filter((g) => g.accountId === accountId).forEach((g) => batch.delete(doc(col(uid, 'savingsGoals'), g.id)));
  return batch.commit();
}
