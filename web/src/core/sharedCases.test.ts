// 공통 테스트 케이스 (testdata 폴더의 json, core SharedCasesTest.kt 와 같은 파일): 같은 입력에 폰과 PC 가 같은 숫자를 내는지.
// 입력은 Firestore 문서 모양 그대로, 기대 결과는 있는 항목만 검사한다. 형식은 testdata/README.md.
import { describe, expect, it } from 'vitest';
import type { Holding, InvestmentAccount, Record } from '../model';
import { toAccount, toHolding, toMarketSnapshot, toRecord } from '../data/mappers';
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
    const t = todayChange(summary, snapshot, today);
    const e = ex.todayChange;
    if (e === null) {
      if (t !== null) check.problems.push(`${file} · todayChange: 기대 null, 실제 ${t.totalKrw}`);
    } else if (t === null) {
      check.problems.push(`${file} · todayChange: 기대 값, 실제 null`);
    } else {
      check.num('todayChange.totalKrw', e.totalKrw, t.totalKrw);
      for (const key of ['rate', 'domesticKrw', 'foreignKrw', 'fxKrw', 'fxFrom', 'fxTo'] as const) {
        if (key in e) check.num(`todayChange.${key}`, e[key], t[key]);
      }
      if (e.accounts) {
        check.text('todayChange.accounts 계좌', sortedKeys(Object.keys(e.accounts)), sortedKeys(t.accounts.map((a) => a.account.id)));
        for (const [id, value] of Object.entries(e.accounts)) check.num(`todayChange.accounts.${id}`, value, t.accounts.find((a) => a.account.id === id)?.changeKrw);
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

describe('공통 테스트 케이스 (testdata)', () => {
  it('케이스 파일이 있다', () => {
    expect(casesIn('portfolio').length).toBeGreaterThan(0);
    expect(casesIn('period').length).toBeGreaterThan(0);
  });

  it.each(casesIn('portfolio'))('계좌 계산 %s', (file, c) => {
    expect(checkPortfolio(file, c)).toEqual([]);
  });

  it.each(casesIn('period'))('기간 수익률 %s', (file, c) => {
    expect(checkPeriod(file, c)).toEqual([]);
  });
});
