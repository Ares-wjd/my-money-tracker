// core TradeImportTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { IMPORT_HEADERS, analyzeImport, buildImportRecords, emptyGrid, guessMarket, isHeaderRow, parseDate, parseNumber, parseSide, pasteInto, saveCount } from './tradeImport';
import { holding, record } from './testData';

const samsung = holding({ id: 'h1', accountId: 'a', name: '삼성전자', code: '005930', market: 'KR' });
const fund = holding({ id: 'h2', accountId: 'a', name: 'TDF 2045', code: '', market: 'KR', assetType: 'FUND' });
const otherVoo = holding({ id: 'h3', accountId: 'b', name: 'VOO', code: 'VOO', market: 'AMEX', assetType: 'ETF' });
const holdings = [samsung, fund];
const all = [...holdings, otherVoo];
const buy = record({ id: 'r1', accountId: 'a', type: 'BUY', date: '2026-10-01', holdingId: 'h1', quantity: 10, price: 70000 });

const grid = (...rows: string[]) => rows.map((r) => r.split('\t'));

describe('표로 일괄 입력 (TradeImportTest)', () => {
  it('칸 읽기', () => {
    expect(parseDate('2026-10-12')).toBe('2026-10-12');
    expect(parseDate('2026. 10. 2')).toBe('2026-10-02');
    expect(parseDate('2026.10.02')).toBe('2026-10-02');
    expect(parseDate('2026/1/5')).toBe('2026-01-05');
    expect(parseDate('20261012')).toBe('2026-10-12');
    expect(parseDate('2026-13-01')).toBeNull();
    expect(parseSide('현금매수')).toBe('BUY');
    expect(parseSide('매도')).toBe('SELL');
    expect(parseSide('배당')).toBeNull();
    expect(parseNumber('10,160원')).toBe(10160);
    expect(parseNumber('$12.50')).toBe(12.5);
    expect(parseNumber('')).toBeNull();
  });

  it('고른 칸부터 채우고 줄이 늘어난다', () => {
    let g = emptyGrid(2);
    g = pasteInto(g, 0, 0, '날짜\t매매구분\n2026-10-01\t매수\n2026-10-02\t매도\n2026-10-03\t매수\n');
    expect(g.length).toBe(3);
    expect(g[1][0]).toBe('2026-10-02');
    g = pasteInto(g, 0, 2, '삼성전자\t005930\t70,000\t10');
    expect(g[0][3]).toBe('005930');
    expect(g[0][5]).toBe('10');
    g = pasteInto(g, 2, 5, '1\t0\t넘침');
    expect(g[2][6]).toBe('0');
    expect(g[2].length).toBe(7);
    g = pasteInto(g, 3, 0, '2026-10-04\t매수\r\n2026-10-05\t매수');
    expect(g.length).toBe(5);
    expect(g[4][0]).toBe('2026-10-05');
  });

  it('제목 줄 인식', () => {
    expect(isHeaderRow([...IMPORT_HEADERS])).toBe(true);
    expect(isHeaderRow(['일자', '구분', '종목명'])).toBe(true);
    expect(isHeaderRow(['2026-10-01', '매수', '삼성전자'])).toBe(false);
  });

  it('기존 종목과 새 종목', () => {
    const a = analyzeImport(
      grid(
        '2026-10-02\t매수\t삼성전자\t005930\t71,000\t5\t150',
        '2026-10-02\t매수\tTDF 2045\t\t1,000\t3\t',
        '2026-10-03\t매수\tVanguard S&P500\tvoo\t500.5\t2\t1.2',
        '2026-10-03\t매수\tKODEX 200\t069500\t35,000\t4\t',
        '2026-10-04\t매수\t금\tM04020000\t150,000\t10\t',
        '\t\t\t\t\t\t',
      ),
      'a', holdings, all, [buy],
    );
    const rows = a.rows;
    expect(rows[0].status).toBe('OK');
    expect(rows[0].holding?.id).toBe('h1');
    expect(rows[0].cost).toBe(150);
    expect(rows[1].holding?.id).toBe('h2');
    expect(rows[1].cost).toBe(0);
    expect(rows[2].newKey).toBe('VOO');
    expect(rows[5].status).toBe('EMPTY');
    expect(saveCount(a)).toBe(5);
    const voo = a.newHoldings.find((h) => h.key === 'VOO')!;
    expect(voo.market).toBe('AMEX');
    expect(voo.assetType).toBe('ETF');
    const kodex = a.newHoldings.find((h) => h.key === '069500')!;
    expect(kodex.market).toBe('KR');
    expect(kodex.assetType).toBe('ETF');
    expect(a.newHoldings.find((h) => h.key === 'M04020000')!.assetType).toBe('GOLD');
  });

  it('해외 티커 추정', () => {
    expect(guessMarket('AAPL', [])).toBe('NASDAQ');
    expect(guessMarket('0091P0', [])).toBe('KR');
    expect(guessMarket('', [])).toBe('KR');
  });

  it('오류·중복·매도 수량 초과', () => {
    const a = analyzeImport(
      grid(
        '10/01\t매수\t삼성전자\t005930\t70000\t1\t',
        '2026-10-01\t배당\t삼성전자\t005930\t70000\t1\t',
        '2026-10-01\t매수\t\t\t70000\t1\t',
        '2026-10-01\t매수\t삼성전자\t005930\t70000\t0\t',
        '2026-10-01\t매수\t삼성전자\t005930\t70,000\t10\t',
        '2026-10-05\t매도\t삼성전자\t005930\t80000\t11\t',
        '2026-10-05\t매도\t삼성전자\t005930\t80000\t10\t',
      ),
      'a', holdings, all, [buy],
    );
    const r = a.rows;
    expect(r[0].error).toBe('날짜를 읽지 못함');
    expect(r[1].error).toBe('매매구분은 매수·매도');
    expect(r[2].error).toBe('종목명이나 종목코드 필요');
    expect(r[3].error).toBe('수량 확인');
    expect(r[4].status).toBe('DUPLICATE');
    expect(r[5].status).toBe('ERROR');
    expect(r[6].status).toBe('ERROR');
  });

  it('같은 표에서 산 뒤 판 기록', () => {
    const a = analyzeImport(
      grid('2026-10-02\t매수\t애플\tAAPL\t200\t3\t', '2026-10-03\t매도\t애플\tAAPL\t210\t2\t'),
      'a', holdings, all, [],
    );
    expect(saveCount(a)).toBe(2);
    const records = buildImportRecords(a.rows, 'a', (key) => (key === 'AAPL' ? 'new1' : undefined));
    expect(records.map(([, r]) => r.holdingId)).toEqual(['new1', 'new1']);
    expect(records[1][1].type).toBe('SELL');
    expect(records[0][1].id).toBe('');
  });
});
