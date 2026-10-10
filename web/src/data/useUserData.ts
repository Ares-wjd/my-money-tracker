import { collection, doc, onSnapshot, orderBy, query, type DocumentData, type Query } from 'firebase/firestore';
import { useEffect, useState } from 'react';
import { firebase } from '../firebase';
import type {
  AppSettings,
  Holding,
  InvestmentAccount,
  MarketSnapshot,
  Record,
  SavingsAccount,
  SavingsGoal,
} from '../model';
import {
  toAccount,
  toHolding,
  toMarketSnapshot,
  toRecord,
  toSavingsAccount,
  toSavingsGoal,
  toSettings,
} from './mappers';

/** 로그인한 사용자의 전체 데이터 (users/{uid} 이하). 폰과 같은 문서를 실시간으로 받는다. */
export interface UserData {
  accounts: InvestmentAccount[];
  holdings: Holding[];
  records: Record[];
  settings: AppSettings;
  market: MarketSnapshot;
  savingsAccounts: SavingsAccount[];
  savingsGoals: SavingsGoal[];
}

type Key = keyof UserData;
const KEYS: readonly Key[] = ['accounts', 'holdings', 'records', 'settings', 'market', 'savingsAccounts', 'savingsGoals'];

export interface UserDataState {
  /** 모든 컬렉션의 첫 응답이 오기 전에는 null */
  data: UserData | null;
  errors: string[];
}

export function useUserData(uid: string): UserDataState {
  const [parts, setParts] = useState<Partial<UserData>>({});
  const [errors, setErrors] = useState<string[]>([]);

  useEffect(() => {
    if (!firebase) return;
    const { db } = firebase;
    setParts({});
    setErrors([]);
    const user = doc(db, 'users', uid);
    const set = <K extends Key>(key: K, value: UserData[K]) => setParts((prev) => ({ ...prev, [key]: value }));
    const fail = (label: string) => (error: Error) =>
      setErrors((prev) => [...prev.filter((e) => !e.startsWith(label)), `${label} 불러오기 실패: ${error.message}`]);

    function listen<T>(label: string, q: Query<DocumentData>, map: (id: string, d: DocumentData) => T | null, key: Key) {
      return onSnapshot(
        q,
        (snapshot) => {
          const items = snapshot.docs.map((d) => map(d.id, d.data())).filter((item): item is T => item !== null);
          set(key, items as UserData[typeof key]);
        },
        fail(label),
      );
    }

    const createdAt = (name: string) => query(collection(user, name), orderBy('createdAt'));
    const unsubscribers = [
      listen('계좌', createdAt('accounts'), toAccount, 'accounts'),
      listen('종목', createdAt('holdings'), toHolding, 'holdings'),
      listen('기록', query(collection(user, 'records')), toRecord, 'records'),
      listen('목적통장', createdAt('savingsAccounts'), toSavingsAccount, 'savingsAccounts'),
      listen('목표', createdAt('savingsGoals'), toSavingsGoal, 'savingsGoals'),
      onSnapshot(doc(user, 'settings', 'app'), (snapshot) => set('settings', toSettings(snapshot.data())), fail('설정')),
      onSnapshot(
        collection(user, 'marketData'),
        (snapshot) => set('market', toMarketSnapshot(snapshot.docs.map((d) => ({ id: d.id, data: d.data() })))),
        fail('시세'),
      ),
    ];
    return () => unsubscribers.forEach((unsubscribe) => unsubscribe());
  }, [uid]);

  const ready = KEYS.every((key) => parts[key] !== undefined);
  return { data: ready ? (parts as UserData) : null, errors };
}
