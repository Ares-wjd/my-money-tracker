// core RecordImportTest 와 같은 케이스.
import { describe, expect, it } from 'vitest';
import { CASH_COLUMNS, analyzeCashFlows, analyzeDividends, analyzeExchanges, buildCashFlowRecords, buildDividendRecords, buildExchangeRecords } from './recordImport';
import { emptyGrid, pasteInto } from './tradeImport';
import { holding, record } from './testData';

const grid = (...rows: string[]) => rows.map((r) => r.split('\t'));

describe('표로 일괄 입력 — 입출금·배당 (RecordImportTest)', () => {
  it('입출금: 금액 부호로 구분, 입출금 칸과 맞는지', () => {
    const existing = record({ id: 'r', accountId: 'a', type: 'DEPOSIT', date: '2026-10-01', amount: 500_000, krwAmount: 500_000 });
    const rows = analyzeCashFlows(
      grid(
        '2026-10-02\t\t1,000,000',
        '2026-10-03\t\t-300,000',
        '2026-10-03\t입금\t-300,000',
        '2026-10-03\t출금\t300,000',
        '2026-10-03\t이체\t300,000',
        '2026-10-03\t입금\t0',
        '2026-10-01\t입금\t500,000',
        '\t\t',
      ),
      'a', [existing],
    );
    expect([rows[0].type, rows[0].amount]).toEqual(['DEPOSIT', 1_000_000]);
    expect([rows[1].type, rows[1].amount]).toEqual(['WITHDRAW', 300_000]);
    expect(rows[2].error).toBe('입금인데 금액이 음수');
    expect(rows[3].error).toBe('출금은 금액을 음수로 (예: -300000)');
    expect(rows[4].error).toBe('입출금은 입금·출금 (비워도 됨)');
    expect(rows[5].error).toBe('금액 확인 (입금 +, 출금 −)');
    expect(rows[6].status).toBe('DUPLICATE');
    expect(rows[7].status).toBe('EMPTY');
    const records = buildCashFlowRecords(rows, 'a');
    expect(records.length).toBe(2);
    expect(records[0].krwAmount).toBe(1_000_000);
    expect(records[1].currency).toBe('KRW');
    const g = pasteInto(emptyGrid(1, CASH_COLUMNS), 0, 0, '날짜\t입출금\t금액\n2026-10-02\t입금\t100', CASH_COLUMNS);
    expect(g[0]).toEqual(['2026-10-02', '입금', '100']);
  });

  it('배당: 종목코드로 찾고 종목명은 경고만, 종목 통화 칸', () => {
    const tiger = holding({ id: 't', accountId: 'a', name: 'TIGER 미국S&P500', code: '360750', market: 'KR' });
    const voo = holding({ id: 'v', accountId: 'a', name: 'VOO', code: 'VOO', market: 'AMEX' });
    const existing = record({ id: 'r', accountId: 'a', type: 'DIVIDEND', date: '2026-09-01', holdingId: 't', amount: 1_000 });
    const rows = analyzeDividends(
      grid(
        '2026-10-02\t360750\tTIGER 미국 S&P500\t12,345\t',
        '2026-10-02\tvoo\t뱅가드\t\t1.84',
        '2026-10-02\t360750\t\t\t3',
        '2026-10-02\tVOO\tVOO\t2,500\t',
        '2026-10-02\t005930\t삼성전자\t1000\t',
        '2026-10-02\t\t삼성전자\t1000\t',
        '2026-10-02\t360750\t\t-5\t',
        '2026-09-01\t360750\t\t1,000\t',
        '2026-10-03\tVOO\t\t2,500\t2.1',
      ),
      [tiger, voo], [existing],
    );
    expect(rows[0].status).toBe('OK');
    expect(rows[0].amount).toBe(12_345);
    expect(rows[0].warning).toBeNull();
    expect(rows[1].amount).toBe(1.84);
    expect(rows[1].warning).toBe("확인: 앱 종목명은 'VOO'");
    expect(rows[2].error).toBe('국내 종목은 원화배당금을 넣으세요');
    expect(rows[3].error).toBe('해외 종목은 외화배당금을 넣으세요');
    expect(rows[4].error).toBe('이 계좌에 없는 종목코드');
    expect(rows[5].error).toBe('종목코드 필요');
    expect(rows[6].error).toBe('배당금 확인');
    expect(rows[7].status).toBe('DUPLICATE');
    expect(rows[8].warning).toBe('원화배당금 칸은 쓰지 않음');
    const records = buildDividendRecords(rows, 'a');
    expect(records.map((r) => r.holdingId)).toEqual(['t', 'v', 'v']);
    expect(records[1].currency).toBe('USD');
  });

  it('환전: 체크박스 방향, 세 칸 중 두 칸 (exchangeCheckboxAndTwoOfThree)', () => {
    const existing = record({ id: 'r', accountId: 'a', type: 'EXCHANGE', date: '2026-10-01', currency: 'KRW', amount: 1_000, krwAmount: 1_394_500 });
    const rows = analyzeExchanges(
      [
        '2026-10-02\t\t1,000\t1,394.5\t',
        '2026-10-03\t1\t\t1,390\t1,000,000',
        '2026-10-04\tTRUE\t500\t\t700,000',
        '2026-10-05\t\t1000\t1400\t1,500,000',
        '2026-10-01\t\t1,000\t\t1,394,500',
        '2026-10-06\t\t1,000\t\t',
        '2026-10-06\t뭐지\t1,000\t1,390\t',
        '2026-10-06\t\t-1,000\t1,390\t',
        '\t1\t\t\t',
      ].map((r) => r.split('\t')),
      'a', [existing],
    );
    expect(rows[0].status).toBe('OK');
    expect(rows[0].toKrw).toBe(false);
    expect(rows[0].krw).toBeCloseTo(1_394_500, 9);
    expect(rows[1].toKrw).toBe(true);
    expect(rows[1].usd).toBeCloseTo(719.42, 9);
    expect(rows[2].toKrw).toBe(true);
    expect(rows[2].krw / rows[2].usd).toBeCloseTo(1_400, 9);
    expect(rows[3].status).toBe('OK');
    expect(rows[3].warning).toBe('확인: 원화÷외화 = 1,500');
    expect(rows[4].status).toBe('DUPLICATE');
    expect(rows[5].error).toBe('외화·환율·원화 중 두 칸 필요');
    expect(rows[6].error).toBe('방향 칸 확인 (체크 = 달러→원화)');
    expect(rows[7].error).toBe('외화·환율·원화는 0보다 크게');
    expect(rows[8].status).toBe('EMPTY');
    const built = buildExchangeRecords(rows, 'a');
    expect(built.length).toBe(4);
    expect(built[1].currency).toBe('USD');
    expect(built[1].krwAmount).toBeCloseTo(1_000_000, 9);
    expect(rows[0].warning).toBeNull();
  });
});
