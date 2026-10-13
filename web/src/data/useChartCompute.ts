import { useCallback, useEffect, useRef, useState } from 'react';
import { buildChart, computeChart, planChart, planDates, related, type DayValue } from '../core/chartStore';
import { plusDays, today } from '../core/dates';
import type { UserData } from './useUserData';
import { deleteChart, saveChart } from './writes';

/** 기록·시세가 잇달아 바뀔 때 한 번만 계산하도록 기다리는 시간. */
const DEBOUNCE_MS = 2_000;
/** 화면이 멈추지 않게 한 번에 계산하는 날 수 (그다음 화면에 양보). */
const BATCH_DAYS = 30;

const yieldToUi = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

/** 계산에 쓰는 입력(기록·종목·계좌·시세·설정)이 바뀌었는지. 저장값(charts)만 바뀐 것은 상관없다. */
const inputsChanged = (a: UserData, b: UserData) =>
  a.records !== b.records || a.holdings !== b.holdings || a.accounts !== b.accounts || a.market !== b.market || a.settings !== b.settings;

/**
 * 그래프 일별 값 계산 (폰과 같은 규칙): 데이터가 바뀌면 계좌마다 다시 계산할 범위를 찾아 조금씩 나눠 계산하고 저장한다.
 * recompute() 는 설정의 [그래프 전체 다시 계산].
 */
export function useChartCompute(uid: string, data: UserData | null) {
  const [computing, setComputing] = useState(false);
  const latest = useRef(data);
  latest.current = data;
  const force = useRef(false);
  const running = useRef(false);
  const again = useRef(false);
  const [tick, setTick] = useState(0);

  const computeOnce = useCallback(async () => {
    const d = latest.current;
    if (!d || d.charts === null) return;
    const isForce = force.current;
    force.current = false;
    const end = plusDays(today(), -1);
    const ids = new Set(d.accounts.map((a) => a.id));
    for (const id of d.charts.keys()) if (!ids.has(id)) await deleteChart(uid, id);
    const plans = [];
    for (const account of d.accounts) {
      const stored = d.charts.get(account.id) ?? null;
      const plan = planChart(account, d.holdings, d.records, d.market, d.settings.manualUsdKrw, stored, end, isForce);
      if (plan) plans.push({ account, plan });
      else if (stored && related(account.id, d.records).length === 0) await deleteChart(uid, account.id);
    }
    if (plans.length === 0) return;
    setComputing(true);
    try {
      for (const { account, plan } of plans) {
        const dates = planDates(plan);
        const computed = new Map<string, DayValue>();
        for (let i = 0; i < dates.length; i += BATCH_DAYS) {
          const part = computeChart(plan, account, d.holdings, d.records, d.market, d.settings.manualUsdKrw, dates.slice(i, i + BATCH_DAYS));
          part.forEach((v, k) => computed.set(k, v));
          await yieldToUi();
          const now = latest.current;
          // 계산하는 동안 기록·시세가 바뀌면 새 데이터로 처음부터 (전체 다시 계산이었으면 그대로 이어서)
          if (!now || inputsChanged(now, d)) {
            if (isForce) force.current = true;
            again.current = true;
            return;
          }
        }
        await saveChart(uid, account.id, buildChart(plan, computed));
      }
    } finally {
      setComputing(false);
    }
  }, [uid]);

  const run = useCallback(async () => {
    if (running.current) {
      again.current = true;
      return;
    }
    running.current = true;
    try {
      do {
        again.current = false;
        await computeOnce().catch(() => undefined);
      } while (again.current);
    } finally {
      running.current = false;
    }
  }, [computeOnce]);

  useEffect(() => {
    if (!data) return;
    const timer = setTimeout(() => void run(), DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [data, tick, run]);

  const recompute = useCallback(() => {
    force.current = true;
    setTick((t) => t + 1);
  }, []);

  return { computing, recompute };
}
