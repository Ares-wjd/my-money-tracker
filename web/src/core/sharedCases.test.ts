// 공통 테스트 케이스 (testdata 폴더의 json, core SharedCasesTest.kt 와 같은 파일): 같은 입력에 폰과 PC 가 같은 숫자를 내는지.
// 입력은 Firestore 문서 모양 그대로, 기대 결과는 있는 항목만 검사한다. 형식은 testdata/README.md.
import { describe, expect, it } from 'vitest';
import type { Holding, InvestmentAccount, Record } from '../model';
import { toAccount, toCalendar, toHolding, toMarketSnapshot, toRecord } from '../data/mappers';
import { MARKET_GROUPS, marketStates, nextText, stateChip, timeline, usHoliday, usOpen, type MarketGroup } from './marketClock';
import { BENCHMARK_INDICES, compareText, covers, simulate, todayDiff, type BenchmarkIndex } from './benchmark';
import { checkAlertCount, dataCheck } from './dataCheck';
import { currentPrice, latestUsdKrw } from './market';
import { cumulativeInvested, noRateReason, shortSells, summarize } from './portfolio';
import { longTermReturn, monthlyReturns, periodSummaryText, yearToDate, yearlyReturns, type FlowPoint } from './periodReturns';
import { todayChange } from './todayChange';

type Data = { [key: string]: unknown };
// 기대 결과는 파일마다 모양이 달라 느슨하게 읽는다.
type Expect = { [key: string]: any };

const files = import.meta.glob<{ default: Data }>('../../../testdata/**/*.json', { eager: true });
const casesIn = (dir: string) =>
  Object.entries(files)
    .filter(([path]) => path.includes(`/testdata/${dir}/`))
    .map(([path, mod]) => [path.slice(path.lastIndexOf('/') + 1), mod.default] as const)
    .sort(([a], [b]) => (a < b ? -1 : 1));

/** 틀린 곳을 모두 모아 한 번에 보여준다. */
class Checker {
  readonly problems: string[] = [];
  constructor(private readonly file: string) {}

  num(path: string, expected: unknown, actual: number | null | undefined) {
    const e = expected === null || expected === undefined ? null : (expected as number);
    const a = actual ?? null;
    if (e === null && a === null) return;
    if (e === null || a === null || Math.abs(e - a) > Math.max(1e-6, 1e-9 * Math.abs(e))) this.problems.push(`${this.file} · ${path}: 기대 ${e}, 실제 ${a}`);
  }

  text(path: string, expected: unknown, actual: string | null | undefined) {
    const e = expected === null || expected === undefined ? null : String(expected);
    if (e !== (actual ?? null)) this.problems.push(`${this.file} · ${path}: 기대 ${e}, 실제 ${actual ?? null}`);
  }
}

const docs = (o: unknown): [string, Data][] => Object.entries((o ?? {}) as { [id: string]: Data });
const parse = <T>(o: unknown, map: (id: string, d: Data) => T | null): T[] => docs(o).flatMap(([id, d]) => map(id, d) ?? []);
const sortedKeys = (keys: Iterable<string>) => `[${[...keys].sort().join(', ')}]`;

function checkPortfolio(file: string, c: Data): string[] {
  const check = new Checker(file);
  const today = c.today as string;
  const accounts: InvestmentAccount[] = parse(c.accounts, toAccount);
  const holdings: Holding[] = parse(c.holdings, toHolding);
  const records: Record[] = parse(c.records, toRecord);
  const snapshot = toMarketSnapshot(docs(c.market).map(([id, data]) => ({ id, data })));
  const usdKrw = latestUsdKrw(snapshot)?.[1] ?? (c.manualUsdKrw as number | null) ?? null;
  const summary = summarize(accounts, holdings, records, usdKrw, (h) => currentPrice(h, snapshot));
  const ex = c.expect as Expect;

  if (ex.summary) {
    for (const key of ['investedKrw', 'valueKrw', 'profitKrw', 'returnRate', 'returnRateExDividends', 'dividendsKrw'] as const) {
      if (key in ex.summary) check.num(`summary.${key}`, ex.summary[key], summary[key]);
    }
  }
  for (const [id, e] of Object.entries((ex.accounts ?? {}) as Expect)) {
    const a = summary.accounts.find((s) => s.account.id === id);
    if (!a) {
      check.problems.push(`${file} · accounts.${id}: 계좌 없음`);
      continue;
    }
    for (const key of ['investedKrw', 'valueKrw', 'profitKrw', 'returnRate'] as const) {
      if (key in e) check.num(`accounts.${id}.${key}`, e[key], a[key]);
    }
    if ('cashKrw' in e) check.num(`accounts.${id}.cashKrw`, e.cashKrw, a.cash.krw);
    if ('cashUsd' in e) check.num(`accounts.${id}.cashUsd`, e.cashUsd, a.cash.usd);
    if ('noRateReason' in e) check.text(`accounts.${id}.noRateReason`, e.noRateReason, noRateReason(id, a.investedKrw, records));
  }
  for (const [id, e] of Object.entries((ex.positions ?? {}) as Expect)) {
    const v = summary.accounts.flatMap((a) => a.holdings).find((h) => h.holding.id === id);
    if (!v) {
      check.problems.push(`${file} · positions.${id}: 종목 없음`);
      continue;
    }
    for (const key of ['quantity', 'averagePrice', 'realizedProfit', 'dividends'] as const) {
      if (key in e) check.num(`positions.${id}.${key}`, e[key], v.position[key]);
    }
  }
  for (const [accountId, e] of Object.entries((ex.cumulativeInvested ?? {}) as Expect)) {
    const actual = cumulativeInvested(accountId, records);
    check.text(`cumulativeInvested.${accountId} 기록`, sortedKeys(Object.keys(e)), sortedKeys(actual.keys()));
    for (const [recordId, value] of Object.entries(e)) check.num(`cumulativeInvested.${accountId}.${recordId}`, value, actual.get(recordId));
  }
  if (ex.shortSells) {
    const actual = shortSells(records);
    const e = ex.shortSells as { id: string; held: number }[];
    check.text('shortSells 기록', e.map((x) => x.id).join(', '), actual.map((s) => s.record.id).join(', '));
    actual.forEach((s, i) => e[i] && check.num(`shortSells.${s.record.id}.held`, e[i].held, s.held));
  }
  if ('todayChange' in ex) {
    const t = todayChange(summary, snapshot, Date.parse(ex.todayChange?.now ?? `${today}T12:00:00+09:00`));
    const e = ex.todayChange;
    if (e === null) {
      if (t !== null) check.problems.push(`${file} · todayChange: 기대 null, 실제 ${t.totalKrw}`);
    } else if (t === null) {
      check.problems.push(`${file} · todayChange: 기대 값, 실제 null`);
    } else {
      check.num('todayChange.totalKrw', e.totalKrw, t.totalKrw);
      if ('rate' in e) check.num('todayChange.rate', e.rate, t.rate);
      if (e.markets) {
        check.text('todayChange.markets', MARKET_GROUPS.filter((g) => g in e.markets).join(', '), t.markets.map((m) => m.state.group).join(', '));
        for (const m of t.markets) {
          const x = e.markets[m.state.group];
          if (!x) continue;
          const p = `todayChange.markets.${m.state.group}`;
          if ('phase' in x) check.text(`${p}.phase`, x.phase, m.state.phase);
          if ('live' in x) check.text(`${p}.live`, String(x.live), String(m.live));
          for (const key of ['changeKrw', 'rate', 'regularKrw', 'extendedKrw', 'fxFrom', 'fxTo'] as const) {
            if (key in x) check.num(`${p}.${key}`, x[key], m[key]);
          }
        }
      }
    }
  }
  if (ex.dataCheck) {
    const issues = dataCheck(accounts, holdings, records, summary, snapshot, today);
    const expected = ex.dataCheck.issues as { key: string; kind: string; detail: string }[];
    check.text('dataCheck 항목', expected.map((x) => x.key).join(', '), issues.map((i) => i.key).join(', '));
    issues.forEach((i, n) => {
      const x = expected[n];
      if (!x) return;
      check.text(`dataCheck.${i.key}.kind`, x.kind, i.kind);
      check.text(`dataCheck.${i.key}.detail`, x.detail, i.detail);
    });
    if ('alertCount' in ex.dataCheck) check.num('dataCheck.alertCount', ex.dataCheck.alertCount, checkAlertCount(issues, new Set(ex.dataCheck.ignored ?? [])));
  }
  return check.problems;
}

function checkClock(file: string, c: Data): string[] {
  const check = new Checker(file);
  const cal = toCalendar(c.calendar as Data | undefined);
  for (const one of (c.cases ?? []) as { now: string; expect: Expect }[]) {
    const nowMs = Date.parse(one.now);
    const states = marketStates(nowMs, cal);
    for (const [group, e] of Object.entries(one.expect)) {
      const s = states[group as MarketGroup];
      const p = `${one.now} ${group}`;
      if ('phase' in e) check.text(`${p}.phase`, e.phase, s.phase);
      if ('candleDate' in e) check.text(`${p}.candleDate`, e.candleDate, s.candleDate);
      if ('chip' in e) check.text(`${p}.chip`, e.chip, stateChip(s));
      if ('next' in e) check.text(`${p}.next`, e.next, nextText(s, nowMs));
    }
  }
  const tl = c.timeline as { date: string; expect: [string, string, number, number][] } | undefined;
  if (tl) {
    check.text('timeline', tl.expect.map((x) => `${x[0]}/${x[1]}/${x[2]}-${x[3]}`).join(', '), timeline(tl.date, cal).map((x) => `${x.group}/${x.phase}/${x.start}-${x.end}`).join(', '));
  }
  for (const [, list] of Object.entries((c.usHolidays ?? {}) as { [y: string]: [string, string][] })) {
    for (const [d, name] of list) check.text(`usHoliday ${d}`, name, usHoliday(d));
  }
  for (const d of (c.usOpenDays ?? []) as string[]) check.text(`usOpen ${d}`, 'true', String(usOpen(d)));
  return check.problems;
}

function checkPeriod(file: string, c: Data): string[] {
  const check = new Checker(file);
  const today = c.today as string;
  const series: FlowPoint[] = (c.series as [string, number, number][]).map(([date, valueKrw, investedKrw]) => ({ date, valueKrw, investedKrw }));
  const ex = c.expect as Expect;
  const ytd = yearToDate(series, today);
  const long = longTermReturn(series, today);
  if (ex.ytd) {
    check.num('ytd.profitKrw', ex.ytd.profitKrw, ytd?.profitKrw);
    if ('rate' in ex.ytd) check.num('ytd.rate', ex.ytd.rate, ytd?.rate);
    if ('netFlowKrw' in ex.ytd) check.num('ytd.netFlowKrw', ex.ytd.netFlowKrw, ytd?.netFlowKrw);
  }
  if (ex.long) {
    for (const key of ['annual', 'period', 'days'] as const) {
      if (key in ex.long) check.num(`long.${key}`, ex.long[key], long?.[key]);
    }
  }
  if (ex.months) {
    const items = ex.months.items as { from: string; profitKrw: number; rate?: number | null }[];
    const actual = monthlyReturns(series, today, ex.months.count);
    check.text('months 시작일', items.map((x) => x.from).join(', '), actual.map((m) => m.from).join(', '));
    actual.forEach((m, i) => {
      const x = items[i];
      if (!x) return;
      check.num(`months.${m.from}.profitKrw`, x.profitKrw, m.profitKrw);
      if ('rate' in x) check.num(`months.${m.from}.rate`, x.rate, m.rate);
    });
  }
  if (ex.years) {
    const items = ex.years as { from: string; profitKrw: number }[];
    const actual = yearlyReturns(series, today);
    check.text('years 시작일', items.map((x) => x.from).join(', '), actual.map((y) => y.from).join(', '));
    actual.forEach((y, i) => items[i] && check.num(`years.${y.from}.profitKrw`, items[i].profitKrw, y.profitKrw));
  }
  if ('summaryText' in ex) check.text('summaryText', ex.summaryText, periodSummaryText(ytd, long));
  return check.problems;
}

function checkBenchmark(file: string, c: Data): string[] {
  const check = new Checker(file);
  const today = c.today as string;
  const series: FlowPoint[] = (c.series as [string, number, number][]).map(([date, valueKrw, investedKrw]) => ({ date, valueKrw, investedKrw }));
  const snapshot = toMarketSnapshot(docs(c.market).map(([id, data]) => ({ id, data })));
  const ex = c.expect as Expect;
  const mine = yearToDate(series, today);
  if ('mineYtd' in ex) check.num('mineYtd', ex.mineYtd, mine?.rate);
  const rates: [BenchmarkIndex, number | null][] = [];
  for (const index of BENCHMARK_INDICES) {
    const sim = simulate(series, index, snapshot, today);
    const ytd = sim ? yearToDate(sim, today) : null;
    rates.push([index, ytd?.rate ?? null]);
    if (!(index in ex.indices)) continue;
    const e = ex.indices[index];
    if (e === null) {
      if (sim !== null) check.problems.push(`${file} · ${index}: 기대 null, 실제 ${sim.map((p) => p.valueKrw)}`);
      continue;
    }
    if (sim === null) {
      check.problems.push(`${file} · ${index}: 계산 결과 없음`);
      continue;
    }
    if (e.values) {
      if (e.values.length !== sim.length) check.problems.push(`${file} · ${index}.values 개수: 기대 ${e.values.length}, 실제 ${sim.length}`);
      sim.forEach((p, i) => check.num(`${index}.values.${p.date}`, e.values[i], p.valueKrw));
    }
    if ('ytd' in e) check.num(`${index}.ytd`, e.ytd, ytd?.rate);
    if ('todayDiff' in e) check.num(`${index}.todayDiff`, e.todayDiff, todayDiff(series, sim)?.diffKrw);
    if ('covers' in e) check.text(`${index}.covers`, e.covers, String(covers(index, snapshot, mine?.from ?? today)));
  }
  if ('compareText' in ex) check.text('compareText', ex.compareText, compareText(mine?.rate ?? null, rates));
  return check.problems;
}

describe('공통 테스트 케이스 (testdata)', () => {
  it('케이스 파일이 있다', () => {
    expect(casesIn('portfolio').length).toBeGreaterThan(0);
    expect(casesIn('period').length).toBeGreaterThan(0);
    expect(casesIn('benchmark').length).toBeGreaterThan(0);
  });

  it.each(casesIn('portfolio'))('계좌 계산 %s', (file, c) => {
    expect(checkPortfolio(file, c)).toEqual([]);
  });

  it.each(casesIn('clock'))('시장 상태 %s', (file, c) => {
    expect(checkClock(file, c)).toEqual([]);
  });

  it.each(casesIn('period'))('기간 수익률 %s', (file, c) => {
    expect(checkPeriod(file, c)).toEqual([]);
  });

  it.each(casesIn('benchmark'))('지수 비교 %s', (file, c) => {
    expect(checkBenchmark(file, c)).toEqual([]);
  });
});
