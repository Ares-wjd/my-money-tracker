// core 의 CashFlowImport · DividendImport 와 같은 규칙: 표로 일괄 입력 — 입출금·배당.
import { marketCurrency, type Holding, type Record } from '../model';
import { computeExchange } from './exchangeInput';
import { decimal } from './format';
import { parseDate, parseNumber, type Grid, type ImportStatus } from './tradeImport';

// ---------------------------------------------------------------- 입출금: 날짜 / 입출금 / 금액 (원화)

export const CASH_HEADERS = ['날짜', '입출금', '금액'] as const;
export const CASH_COLUMNS = 3;

export interface CashFlowRow {
  index: number;
  status: ImportStatus;
  date: string | null;
  type: 'DEPOSIT' | 'WITHDRAW' | null;
  /** 원화 금액 (양수) */
  amount: number;
  error: string | null;
}

export function parseCashSide(text: string): 'DEPOSIT' | 'WITHDRAW' | null {
  const t = text.trim();
  if (t.includes('입금')) return 'DEPOSIT';
  if (t.includes('출금')) return 'WITHDRAW';
  return null;
}

/** 표를 읽는다. 금액 부호로 구분 (양수 = 입금, 음수 = 출금), 입출금 칸은 비워도 되고 적었으면 부호와 맞아야 한다. */
export function analyzeCashFlows(grid: Grid, accountId: string, records: readonly Record[]): CashFlowRow[] {
  return grid.map((raw, index): CashFlowRow => {
    const cells = Array.from({ length: CASH_COLUMNS }, (_, i) => (raw[i] ?? '').trim());
    const blank = (status: ImportStatus, error: string | null = null): CashFlowRow => ({ index, status, date: null, type: null, amount: 0, error });
    if (cells.every((c) => c === '')) return blank('EMPTY');
    const date = parseDate(cells[0]);
    const side = parseCashSide(cells[1]);
    const signed = parseNumber(cells[2]);
    const error =
      date === null ? '날짜를 읽지 못함'
        : cells[1] !== '' && side === null ? '입출금은 입금·출금 (비워도 됨)'
          : signed === null || signed === 0 ? '금액 확인 (입금 +, 출금 −)'
            : side === 'DEPOSIT' && signed < 0 ? '입금인데 금액이 음수'
              : side === 'WITHDRAW' && signed > 0 ? '출금은 금액을 음수로 (예: -300000)'
                : null;
    if (error !== null) return blank('ERROR', error);
    const type = signed! > 0 ? 'DEPOSIT' : 'WITHDRAW';
    const amount = Math.abs(signed!);
    // 중복 의심: 같은 날·같은 구분·같은 금액의 원화 입출금이 이미 있음
    const duplicate = records.some((r) => r.accountId === accountId && r.type === type && r.date === date && r.currency === 'KRW' && Math.abs(r.amount - amount) < 0.5);
    return { index, status: duplicate ? 'DUPLICATE' : 'OK', date, type, amount, error: null };
  });
}

/** 저장할 줄 → 입출금 기록 (원화, 투자금 = 금액). */
export function buildCashFlowRecords(rows: readonly CashFlowRow[], accountId: string): Record[] {
  return rows.filter((r) => r.status === 'OK').map((r) => ({
    id: '', accountId, type: r.type!, date: r.date!, currency: 'KRW', amount: r.amount, krwAmount: r.amount, toAccountId: null,
    holdingId: null, quantity: 0, price: 0, fee: 0, tax: 0, cost: null, initial: false, externalId: null, memo: '', createdAt: 0,
  }));
}

// ---------------------------------------------------------------- 배당: 날짜 / 종목코드 / 종목명 / 원화배당금 / 외화배당금

export const DIVIDEND_HEADERS = ['날짜', '종목코드', '종목명', '원화배당금', '외화배당금'] as const;
export const DIVIDEND_COLUMNS = 5;

export interface DividendRow {
  index: number;
  status: ImportStatus;
  date: string | null;
  holding: Holding | null;
  /** 종목 통화 금액 */
  amount: number;
  /** 저장은 하지만 확인할 것 (종목명이 다름 등) */
  warning: string | null;
  error: string | null;
}

const normalize = (name: string) => name.replace(/ /g, '').toUpperCase();

/**
 * 표를 읽는다. 종목은 이 계좌의 종목코드로만 찾고 (종목명은 확인용 — 다르면 경고만),
 * 배당은 종목 통화로 저장하므로 국내 종목은 원화배당금, 해외 종목은 외화배당금 칸을 쓴다.
 */
export function analyzeDividends(grid: Grid, accountHoldings: readonly Holding[], records: readonly Record[]): DividendRow[] {
  return grid.map((raw, index): DividendRow => {
    const cells = Array.from({ length: DIVIDEND_COLUMNS }, (_, i) => (raw[i] ?? '').trim());
    const blank = (status: ImportStatus, error: string | null = null): DividendRow => ({ index, status, date: null, holding: null, amount: 0, warning: null, error });
    if (cells.every((c) => c === '')) return blank('EMPTY');
    const date = parseDate(cells[0]);
    const code = cells[1];
    const holding = code === '' ? undefined : accountHoldings.find((h) => h.code.toUpperCase() === code.toUpperCase());
    const foreign = holding !== undefined && marketCurrency(holding.market) === 'USD';
    const column = foreign ? 4 : 3;
    const other = foreign ? 3 : 4;
    const amount = parseNumber(cells[column]);
    const error =
      date === null ? '날짜를 읽지 못함'
        : code === '' ? '종목코드 필요'
          : holding === undefined ? '이 계좌에 없는 종목코드'
            : cells[column] === '' ? (foreign ? '해외 종목은 외화배당금을 넣으세요' : '국내 종목은 원화배당금을 넣으세요')
              : amount === null || amount <= 0 ? '배당금 확인'
                : null;
    if (error !== null) return blank('ERROR', error);
    const h = holding!;
    const name = cells[2];
    const warning = [
      name !== '' && normalize(name) !== normalize(h.name) ? `확인: 앱 종목명은 '${h.name}'` : null,
      cells[other] !== '' ? (foreign ? '원화배당금 칸은 쓰지 않음' : '외화배당금 칸은 쓰지 않음') : null,
    ].filter(Boolean).join(' · ') || null;
    // 중복 의심: 같은 날·같은 종목·같은 금액의 배당이 이미 있음
    const duplicate = records.some((r) => r.type === 'DIVIDEND' && r.holdingId === h.id && r.date === date && Math.abs(r.amount - amount!) < 1e-6);
    return { index, status: duplicate ? 'DUPLICATE' : 'OK', date, holding: h, amount: amount!, warning, error: null };
  });
}

/** 저장할 줄 → 배당 기록 (종목 통화, 세후). */
export function buildDividendRecords(rows: readonly DividendRow[], accountId: string): Record[] {
  return rows.filter((r) => r.status === 'OK').map((r) => ({
    id: '', accountId, type: 'DIVIDEND', date: r.date!, currency: marketCurrency(r.holding!.market), amount: r.amount, krwAmount: 0,
    toAccountId: null, holdingId: r.holding!.id, quantity: 0, price: 0, fee: 0, tax: 0, cost: null, initial: false, externalId: null,
    memo: '', createdAt: 0,
  }));
}

// ---------------------------------------------------------------- 환전: 날짜 / 달러→원화(체크박스) / 외화 / 환율 / 원화

export const EXCHANGE_HEADERS = ['날짜', '달러→원화', '외화', '환율', '원화'] as const;
export const EXCHANGE_COLUMNS = 5;
/** 체크박스 칸 (붙여 넣은 글도 읽는다: TRUE·1·Y·O·V·✓·달러→원화·매도 = 체크). */
export const EXCHANGE_CHECK = 1;
/** 체크박스 칸에 저장하는 글. */
export const EXCHANGE_CHECKED = '1';

export interface ExchangeRow {
  index: number;
  status: ImportStatus;
  date: string | null;
  /** 달러 → 원화 (달러 매도) 인지 */
  toKrw: boolean;
  usd: number;
  krw: number;
  /** 저장은 하지만 확인할 것 */
  warning: string | null;
  error: string | null;
}

const UNCHECKED_WORDS = new Set(['FALSE', '0', 'N', 'X', '원화→달러', '매수', '달러매수']);
const CHECKED_WORDS = new Set([EXCHANGE_CHECKED, 'TRUE', 'Y', 'O', 'V', '✓', '✔', '☑', '체크', '달러→원화', '매도', '달러매도']);

/** 체크박스 칸: 체크면 true, 비었거나 해제면 false, 알 수 없는 글이면 null. */
export function parseCheck(text: string): boolean | null {
  const t = text.trim().replace(/ /g, '').toUpperCase();
  if (t === '' || UNCHECKED_WORDS.has(t)) return false;
  if (CHECKED_WORDS.has(t)) return true;
  return null;
}

/**
 * 표를 읽는다 (core ExchangeImport 와 같은 규칙). 체크하지 않으면 원화 → 달러, 체크하면 달러 → 원화. 금액·환율은 양수.
 * 외화·환율·원화 중 두 칸만 있어도 나머지를 계산하고, 세 칸 모두 있으면 환율이 원화 ÷ 외화와 0.5% 넘게 다를 때 경고만 한다.
 */
export function analyzeExchanges(grid: Grid, accountId: string, records: readonly Record[]): ExchangeRow[] {
  return grid.map((raw, index): ExchangeRow => {
    const cells = Array.from({ length: EXCHANGE_COLUMNS }, (_, i) => (raw[i] ?? '').trim());
    const blank = (status: ImportStatus, error: string | null = null): ExchangeRow => ({ index, status, date: null, toKrw: false, usd: 0, krw: 0, warning: null, error });
    if (cells.every((c, i) => i === EXCHANGE_CHECK || c === '')) return blank('EMPTY');
    const date = parseDate(cells[0]);
    const toKrw = parseCheck(cells[EXCHANGE_CHECK]);
    const read = (i: number) => (cells[i] === '' ? null : parseNumber(cells[i]));
    const [usdIn, rateIn, krwIn] = [read(2), read(3), read(4)];
    const unreadable = [2, 3, 4].some((i) => cells[i] !== '' && parseNumber(cells[i]) === null);
    const values = [usdIn, rateIn, krwIn].filter((v): v is number => v !== null);
    const error =
      date === null ? '날짜를 읽지 못함'
        : toKrw === null ? '방향 칸 확인 (체크 = 달러→원화)'
          : unreadable ? '외화·환율·원화 숫자 확인'
            : values.some((v) => v <= 0) ? '외화·환율·원화는 0보다 크게'
              : values.length < 2 ? '외화·환율·원화 중 두 칸 필요'
                : null;
    if (error !== null) return blank('ERROR', error);
    const usd = usdIn ?? computeExchange('USD', krwIn, null, rateIn)!;
    const krw = krwIn ?? computeExchange('KRW', null, usdIn, rateIn)!;
    const warning = usdIn !== null && krwIn !== null && rateIn !== null && Math.abs(krw / usd - rateIn) / rateIn > 0.005
      ? `확인: 원화÷외화 = ${decimal(krw / usd, 2)}`
      : null;
    const currency = toKrw ? 'USD' : 'KRW';
    // 중복 의심: 같은 날·같은 방향·같은 금액의 환전이 이미 있음
    const duplicate = records.some((r) => r.accountId === accountId && r.type === 'EXCHANGE' && r.date === date && r.currency === currency &&
      Math.abs(r.krwAmount - krw) < 0.5 && Math.abs(r.amount - usd) < 0.005);
    return { index, status: duplicate ? 'DUPLICATE' : 'OK', date, toKrw: toKrw!, usd, krw, warning, error: null };
  });
}

/** 저장할 줄 → 환전 기록 (currency = 바꾸기 전 통화, amount = 달러, krwAmount = 원화). */
export function buildExchangeRecords(rows: readonly ExchangeRow[], accountId: string): Record[] {
  return rows.filter((r) => r.status === 'OK').map((r) => ({
    id: '', accountId, type: 'EXCHANGE', date: r.date!, currency: r.toKrw ? 'USD' : 'KRW', amount: r.usd, krwAmount: r.krw, toAccountId: null,
    holdingId: null, quantity: 0, price: 0, fee: 0, tax: 0, cost: null, initial: false, externalId: null, memo: '', createdAt: 0,
  }));
}
