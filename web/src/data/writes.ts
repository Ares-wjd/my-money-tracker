// Firestore 쓰기. 삭제는 폰과 같이 딸린 문서(종목·기록·목표)를 한 번에 지운다.
import { collection, deleteDoc, doc, setDoc, writeBatch, type DocumentReference } from 'firebase/firestore';
import { firebase } from '../firebase';
import type { Holding, InvestmentAccount, Record, SavingsAccount, SavingsGoal, SavingsGoalGroup } from '../model';
import type { AllocationTarget } from '../core/allocation';
import { allocationTargetDoc, accountDoc, holdingDoc, recordDoc, savingsAccountDoc, savingsGoalDoc, savingsGoalGroupDoc } from './serialize';

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

export const saveAllocationTarget = (uid: string, t: AllocationTarget) => save(uid, 'allocationTargets', t.id, allocationTargetDoc(t));
export const deleteAllocationTarget = (uid: string, id: string) => deleteDoc(doc(col(uid, 'allocationTargets'), id));

/**
 * 표로 일괄 입력: 새 종목과 매매 기록을 한꺼번에 저장한다. records 의 holdingId 가 newHoldings 의 키면 새 종목 id 로 바꾼다.
 * 한 번에 쓸 수 있는 개수(500) 아래로 나눠 저장한다.
 */
export async function importTrades(uid: string, newHoldings: ReadonlyMap<string, Holding>, records: readonly Record[]) {
  const ids = new Map<string, string>();
  const writes: [DocumentReference, object][] = [];
  for (const [key, h] of newHoldings) {
    const r = ref(uid, 'holdings', '');
    ids.set(key, r.id);
    writes.push([r, holdingDoc(h)]);
  }
  for (const rec of records) {
    const holdingId = rec.holdingId !== null ? ids.get(rec.holdingId) ?? rec.holdingId : null;
    writes.push([ref(uid, 'records', ''), recordDoc({ ...rec, holdingId })]);
  }
  for (let i = 0; i < writes.length; i += 400) {
    const batch = writeBatch(db());
    for (const [r, data] of writes.slice(i, i + 400)) batch.set(r, data);
    await batch.commit();
  }
}

export const deleteRecord = (uid: string, id: string) => deleteDoc(doc(col(uid, 'records'), id));
export const deleteSavingsGoal = (uid: string, id: string) => deleteDoc(doc(col(uid, 'savingsGoals'), id));

/**
 * 그룹을 저장하고 목표를 넣고 뺀다 (한 번에). [memberIds] 에 있는 목표는 이 그룹으로, 원래 이 그룹이던 나머지는 그룹에서 뺀다.
 */
export async function saveGoalGroup(uid: string, group: SavingsGoalGroup, memberIds: readonly string[], goals: readonly SavingsGoal[]) {
  const batch = writeBatch(db());
  const r = ref(uid, 'savingsGoalGroups', group.id);
  batch.set(r, savingsGoalGroupDoc(group));
  const members = new Set(memberIds);
  for (const g of goals) {
    const shouldBeIn = members.has(g.id);
    const isIn = g.groupId === r.id;
    if (shouldBeIn !== isIn) batch.set(doc(col(uid, 'savingsGoals'), g.id), savingsGoalDoc({ ...g, groupId: shouldBeIn ? r.id : null }));
  }
  await batch.commit();
  return r.id;
}

/** 그룹을 푼다: 그룹을 지우고 안의 목표는 그대로 (원래 날짜로) 남긴다. */
export function deleteGoalGroup(uid: string, groupId: string, goals: readonly SavingsGoal[]) {
  const batch = writeBatch(db());
  batch.delete(doc(col(uid, 'savingsGoalGroups'), groupId));
  goals.filter((g) => g.groupId === groupId).forEach((g) => batch.set(doc(col(uid, 'savingsGoals'), g.id), savingsGoalDoc({ ...g, groupId: null })));
  return batch.commit();
}

/** 그룹 안 목표를 한꺼번에 구매 완료(또는 되돌리기). */
export function completeGoals(uid: string, goals: readonly SavingsGoal[], completedDate: string | null) {
  const batch = writeBatch(db());
  goals.forEach((g) => batch.set(doc(col(uid, 'savingsGoals'), g.id), savingsGoalDoc({ ...g, completedDate })));
  return batch.commit();
}

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

/** 목적통장과 그 목표·그룹을 함께 삭제한다. */
export function deleteSavingsAccount(uid: string, accountId: string, goals: readonly SavingsGoal[], groups: readonly SavingsGoalGroup[] = []) {
  const batch = writeBatch(db());
  batch.delete(doc(col(uid, 'savingsAccounts'), accountId));
  goals.filter((g) => g.accountId === accountId).forEach((g) => batch.delete(doc(col(uid, 'savingsGoals'), g.id)));
  groups.filter((g) => g.accountId === accountId).forEach((g) => batch.delete(doc(col(uid, 'savingsGoalGroups'), g.id)));
  return batch.commit();
}
