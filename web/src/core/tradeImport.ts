// core 의 TradeImport 와 같은 규칙: 표로 일괄 입력 (매수·매도).
// 칸: 날짜 / 매매구분 / 종목명 / 종목코드 / 평단가 / 수량 / 제비용합
import { GOLD_CODE, type AssetType, type Holding, type Market, type Record } from '../model';
import { decimal } from './format';
import { quantityAt } from './portfolio';

export const IMPORT_HEADERS = ['날짜', '매매구분', '종목명', '종목코드', '평단가', '수량', '제비용합'] as const;
export const IMPORT_COLUMNS = 7;
export const IMPORT_MAX_ROWS = 300;
export const COL = { DATE: 0, SIDE: 1, NAME: 2, CODE: 3, PRICE: 4, QUANTITY: 5, COST: 6 } as const;

const ETF_BRANDS = ['KODEX', 'TIGER', 'ACE', 'SOL', 'RISE', 'KBSTAR', 'HANARO', 'ARIRANG', 'KOSEF', 'PLUS', 'TIMEFOLIO', '1Q', 'KIWOOM'];
const HEADER_WORDS = [...IMPORT_HEADERS, '일자', '거래일', '구분', '종목', '코드', '단가', '가격', '매매가', '비용', '수수료'];

export type Grid = string[][];

/** 빈 표 (줄 수만큼 빈 칸). */
export const emptyGrid = (rows = 10): Grid => Array.from({ length: rows }, () => Array<string>(IMPORT_COLUMNS).fill(''));

/** 붙여 넣은 글 → 줄·칸 (탭·줄바꿈으로 구분, 마지막 빈 줄은 버림, 칸을 감싼 따옴표는 뗀다). */
export function splitClipboard(text: string): Grid {
  const lines = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
  while (lines.length > 0 && lines[lines.length - 1].trim() === '') lines.pop();
  return lines.map((line) =>
    line.split('\t').map((cell) => {
      const t = cell.trim();
      return t.length >= 2 && t.startsWith('"') && t.endsWith('"') ? t.slice(1, -1).replace(/""/g, '"').trim() : t;
    }),
  );
}

/** 제목 줄인지 (채워진 칸이 모두 제목 글자). */
export function isHeaderRow(cells: readonly string[]): boolean {
  const filled = cells.map((c) => c.trim()).filter((c) => c !== '');
  return filled.length > 0
    && filled.every((cell) => HEADER_WORDS.some((w) => cell.replace(/ /g, '').includes(w)))
    && !filled.some((c) => parseNumber(c) !== null || parseDate(c) !== null);
}

/** [row], [col] 칸부터 붙여 넣는다: 오른쪽·아래로 채우고, 줄이 모자라면 늘리고(최대 300), 오른쪽으로 넘치는 칸은 버린다. 제목 줄은 건너뛴다. */
export function pasteInto(grid: Grid, row: number, col: number, text: string): Grid {
  const pasted = splitClipboard(text).filter((cells) => !isHeaderRow(cells));
  if (pasted.length === 0) return grid;
  const result = grid.map((r) => [...r]);
  pasted.forEach((cells, i) => {
    const r = row + i;
    if (r >= IMPORT_MAX_ROWS) return;
    while (result.length <= r) result.push(Array<string>(IMPORT_COLUMNS).fill(''));
    cells.forEach((cell, j) => {
      const c = col + j;
      if (c < IMPORT_COLUMNS) result[r][c] = cell;
    });
  });
  return result;
}

// ---------------------------------------------------------------- 칸 읽기

const DATE_PATTERN = /^(\d{4})\s*[-./년]\s*(\d{1,2})\s*[-./월]\s*(\d{1,2})\s*[.일]?$/;
const COMPACT_DATE = /^(\d{4})(\d{2})(\d{2})$/;

/** 날짜 → "YYYY-MM-DD" (없는 날짜면 null). */
export function parseDate(text: string): string | null {
  const t = text.trim();
  const m = DATE_PATTERN.exec(t) ?? COMPACT_DATE.exec(t);
  if (!m) return null;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const date = new Date(Date.UTC(y, mo - 1, d));
  if (date.getUTCFullYear() !== y || date.getUTCMonth() !== mo - 1 || date.getUTCDate() !== d) return null;
  return `${String(y).padStart(4, '0')}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

export function parseSide(text: string): 'BUY' | 'SELL' | null {
  const t = text.trim().toLowerCase();
  if (t.includes('매수') || t === 'buy' || t === 'b') return 'BUY';
  if (t.includes('매도') || t === 'sell' || t === 's') return 'SELL';
  return null;
}

/** 숫자 (쉼표·공백·원·$ 무시, 음수는 -3·−3·(3)). 빈 칸이면 null. */
export function parseNumber(text: string): number | null {
  let t = text.replace(/[, 원$₩]/g, '').replace(/−/g, '-').trim();
  if (t.length >= 3 && t.startsWith('(') && t.endsWith(')')) t = `-${t.slice(1, -1)}`;
  if (t === '' || !/^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(t)) return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
}

// ---------------------------------------------------------------- 분석

/** 새로 만들 종목 (같은 코드끼리, 코드가 없으면 같은 이름끼리 하나). */
export interface NewHolding {
  key: string;
  name: string;
  code: string;
  market: Market;
  assetType: AssetType;
}

export type ImportStatus = 'EMPTY' | 'OK' | 'DUPLICATE' | 'ERROR';

export interface ImportRow {
  index: number;
  status: ImportStatus;
  date: string | null;
  type: 'BUY' | 'SELL' | null;
  price: number;
  quantity: number;
  cost: number;
  /** 이 계좌의 기존 종목 */
  holding: Holding | null;
  /** 새 종목 키 */
  newKey: string | null;
  error: string | null;
}

export interface ImportAnalysis {
  rows: ImportRow[];
  newHoldings: NewHolding[];
}

export const saveCount = (a: ImportAnalysis): number => a.rows.filter((r) => r.status === 'OK').length;

const blankRow = (index: number, status: ImportStatus, error: string | null = null): ImportRow => ({
  index, status, date: null, type: null, price: 0, quantity: 0, cost: 0, holding: null, newKey: null, error,
});

export const newKeyOf = (name: string, code: string): string => code.trim().toUpperCase() || `이름:${name.trim()}`;

/** 새 종목의 시장 추정: 다른 계좌의 같은 코드 → 숫자로 시작하는 6자리·코드 없음·금 = 국내, 그 외 영문 티커 = 나스닥. */
export function guessMarket(code: string, all: readonly Holding[]): Market {
  const c = code.trim().toUpperCase();
  const same = c !== '' ? all.find((h) => h.code.toUpperCase() === c) : undefined;
  if (same) return same.market;
  return c === '' || c === GOLD_CODE || /^[0-9][0-9A-Z]{5}$/.test(c) ? 'KR' : 'NASDAQ';
}

/** 새 종목의 자산 유형 추정: 다른 계좌의 같은 코드 → 금 코드 = 금현물, ETF 브랜드 이름 = ETF, 나머지 주식. */
export function guessAssetType(name: string, code: string, all: readonly Holding[]): AssetType {
  const c = code.trim().toUpperCase();
  const same = c !== '' ? all.find((h) => h.code.toUpperCase() === c) : undefined;
  if (same) return same.assetType;
  const n = name.trim().toUpperCase();
  if (c === GOLD_CODE || n.includes('금현물')) return 'GOLD';
  if (n.includes('ETF') || ETF_BRANDS.some((b) => n.startsWith(b))) return 'ETF';
  return 'STOCK';
}

/** 이 계좌의 종목 찾기: 코드가 같은 종목 → (코드가 비었으면) 이름이 같은 종목. */
export function matchHolding(name: string, code: string, accountHoldings: readonly Holding[]): Holding | null {
  const c = code.trim().toUpperCase();
  if (c !== '') return accountHoldings.find((h) => h.code.toUpperCase() === c) ?? null;
  const n = name.trim().replace(/ /g, '').toUpperCase();
  return accountHoldings.find((h) => h.name.replace(/ /g, '').toUpperCase() === n) ?? null;
}

/**
 * 표를 읽는다. accountHoldings: 이 계좌의 종목, allHoldings: 모든 계좌의 종목(새 종목 추정용),
 * records: 이 계좌의 기록(중복·매도 수량 확인용), overrides: 표 아래에서 고친 새 종목(키 → 시장·유형).
 */
export function analyzeImport(
  grid: Grid,
  accountId: string,
  accountHoldings: readonly Holding[],
  allHoldings: readonly Holding[],
  records: readonly Record[],
  overrides: ReadonlyMap<string, Pick<NewHolding, 'market' | 'assetType'>> = new Map(),
): ImportAnalysis {
  const newHoldings = new Map<string, NewHolding>();
  const parsed = grid.map((raw, index): ImportRow => {
    const cells = Array.from({ length: IMPORT_COLUMNS }, (_, i) => (raw[i] ?? '').trim());
    if (cells.every((c) => c === '')) return blankRow(index, 'EMPTY');
    const date = parseDate(cells[COL.DATE]);
    // 수량 부호: 양수 = 매수, 음수 = 매도. 매매구분은 비워도 되고, 적었으면 부호와 맞아야 한다.
    const side = parseSide(cells[COL.SIDE]);
    const name = cells[COL.NAME];
    const code = cells[COL.CODE].toUpperCase();
    const price = parseNumber(cells[COL.PRICE]);
    const signed = parseNumber(cells[COL.QUANTITY]);
    const cost = cells[COL.COST] === '' ? 0 : parseNumber(cells[COL.COST]);
    const error =
      date === null ? '날짜를 읽지 못함'
        : cells[COL.SIDE] !== '' && side === null ? '매매구분은 매수·매도 (비워도 됨)'
          : name === '' && code === '' ? '종목명이나 종목코드 필요'
            : price === null || price < 0 ? '평단가 확인'
              : signed === null || signed === 0 ? '수량 확인 (매수 +, 매도 −)'
                : side === 'BUY' && signed < 0 ? '매수인데 수량이 음수'
                  : side === 'SELL' && signed > 0 ? '매도는 수량을 음수로 (예: -3)'
                    : cost === null || cost < 0 ? '제비용합 확인'
                      : null;
    if (error !== null) return blankRow(index, 'ERROR', error);
    const type = signed! > 0 ? 'BUY' : 'SELL';
    const quantity = Math.abs(signed!);
    const holding = matchHolding(name, code, accountHoldings);
    const key = holding === null ? newKeyOf(name, code) : null;
    if (key !== null && !newHoldings.has(key)) {
      const o = overrides.get(key);
      newHoldings.set(key, {
        key,
        name: name || code,
        code,
        market: o?.market ?? guessMarket(code, allHoldings),
        assetType: o?.assetType ?? guessAssetType(name, code, allHoldings),
      });
    }
    return { index, status: 'OK', date, type, price: price!, quantity, cost: cost!, holding, newKey: key, error: null };
  });

  // 중복 의심: 이미 있는 기록과 날짜·종목·구분·수량·단가가 같음
  const withDuplicates = parsed.map((row) => {
    if (row.status !== 'OK' || row.holding === null) return row;
    const h = row.holding;
    const duplicate = records.some((r) =>
      r.holdingId === h.id && r.type === row.type && r.date === row.date
      && Math.abs(r.quantity - row.quantity) < 1e-9 && Math.abs(r.price - row.price) < 1e-9);
    return duplicate ? { ...row, status: 'DUPLICATE' as const } : row;
  });

  // 매도 수량: 그날 보유 수량 (기존 기록 + 표의 다른 저장할 줄)
  const draft = buildImportRecords(withDuplicates, accountId, (key) => `new:${key}`).map(([i, r]) => ({ ...r, id: `draft:${i}` }));
  const allRecords = [...records, ...draft];
  const rows = withDuplicates.map((row) => {
    if (row.status !== 'OK' || row.type !== 'SELL') return row;
    const holdingId = row.holding?.id ?? `new:${row.newKey}`;
    const held = quantityAt(
      { id: holdingId, accountId, name: '', code: '', market: 'KR', assetType: 'STOCK', manualPrice: null, manualPriceDate: null, createdAt: 0 },
      allRecords,
      row.date!,
      `draft:${row.index}`,
    );
    return held + 1e-9 < row.quantity
      ? { ...row, status: 'ERROR' as const, error: `매도 수량이 그날 보유 수량(${decimal(held)})보다 많음` }
      : row;
  });
  return { rows, newHoldings: [...newHoldings.values()] };
}

/** 저장할 줄을 기록으로 만든다. holdingIdOf: 새 종목 키 → 저장한 종목 id. 돌려주는 값: [줄 번호, 기록] (기록 id 는 비움). */
export function buildImportRecords(rows: readonly ImportRow[], accountId: string, holdingIdOf: (key: string) => string | undefined): [number, Record][] {
  const out: [number, Record][] = [];
  for (const row of rows) {
    if (row.status !== 'OK') continue;
    const holdingId = row.holding?.id ?? (row.newKey !== null ? holdingIdOf(row.newKey) : undefined);
    if (holdingId === undefined) continue;
    out.push([row.index, {
      id: '', accountId, type: row.type!, date: row.date!, currency: 'KRW', amount: 0, krwAmount: 0, toAccountId: null,
      holdingId, quantity: row.quantity, price: row.price, fee: 0, tax: 0, cost: row.cost, initial: false, externalId: null, memo: '', createdAt: 0,
    }]);
  }
  return out;
}
