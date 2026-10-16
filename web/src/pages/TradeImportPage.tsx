import { useMemo, useState, type ClipboardEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Choice } from '../components/form';
import { Card, NotFound, Warning } from '../components/ui';
import {
  IMPORT_COLUMNS, IMPORT_HEADERS, IMPORT_MAX_ROWS, analyzeImport, buildImportRecords, emptyGrid, pasteInto, saveCount,
  type Grid, type ImportRow, type ImportStatus, type NewHolding,
} from '../core/tradeImport';
import {
  CASH_COLUMNS, CASH_HEADERS, DIVIDEND_COLUMNS, DIVIDEND_HEADERS, EXCHANGE_CHECK, EXCHANGE_CHECKED, EXCHANGE_COLUMNS, EXCHANGE_HEADERS,
  analyzeCashFlows, analyzeDividends, analyzeExchanges, buildCashFlowRecords, buildDividendRecords, buildExchangeRecords, parseCheck,
} from '../core/recordImport';
import { useAppData, useData } from '../data/DataContext';
import { shortSellLabel } from './text';
import { importTrades } from '../data/writes';
import { ASSET_TYPES, ASSET_TYPE_LABEL, MARKETS, MARKET_LABEL, marketCurrency, quantityUnit, type Holding } from '../model';
import { amount as formatAmount, decimal, usd, won } from '../core/format';

/** 비었음 · 오류 · 중복 줄의 글 (표 종류와 상관없이 같다). */
function commonText(status: ImportStatus, error: string | null): string {
  if (status === 'ERROR') return error ?? '';
  if (status === 'DUPLICATE') return '중복 의심 (저장 안 함)';
  return '';
}

function statusText(row: ImportRow, unit: string): string {
  if (row.status !== 'OK') return commonText(row.status, row.error);
  return `${row.holding ? '기존 종목' : '새 종목'} · ${row.type === 'SELL' ? '매도' : '매수'} ${decimal(row.quantity)}${unit}`;
}

/** 표 종류: 입출금 · 매매 · 배당. 표마다 칸이 다르다. */
type ImportKind = 'CASH' | 'TRADE' | 'DIVIDEND' | 'EXCHANGE';
const KINDS: readonly ImportKind[] = ['CASH', 'TRADE', 'DIVIDEND', 'EXCHANGE'];
const KIND_LABEL: Record<ImportKind, string> = { CASH: '입출금', TRADE: '매매', DIVIDEND: '배당', EXCHANGE: '환전' };
const KIND_HEADERS: Record<ImportKind, readonly string[]> = { CASH: CASH_HEADERS, TRADE: IMPORT_HEADERS, DIVIDEND: DIVIDEND_HEADERS, EXCHANGE: EXCHANGE_HEADERS };
const KIND_COLUMNS: Record<ImportKind, number> = { CASH: CASH_COLUMNS, TRADE: IMPORT_COLUMNS, DIVIDEND: DIVIDEND_COLUMNS, EXCHANGE: EXCHANGE_COLUMNS };
/** 체크박스로 보여줄 칸 (환전의 달러→원화). */
const KIND_CHECK: Record<ImportKind, number | null> = { CASH: null, TRADE: null, DIVIDEND: null, EXCHANGE: EXCHANGE_CHECK };
const KIND_HELP: Record<ImportKind, string> = {
  CASH: '금액은 입금 +, 출금 −(예: -300000)로 쓰고, 입출금 칸은 비워도 됩니다. 원화 입출금만 넣습니다 (이체는 기록 추가에서 직접).',
  TRADE: '수량은 매수 +, 매도 −(예: -3)로 쓰고, 매매구분은 비워도 됩니다.',
  DIVIDEND: '종목은 이 계좌의 종목코드로 찾습니다 (종목명은 확인용 — 다르면 경고만). 국내 종목은 원화배당금, 해외 종목은 외화배당금 칸에 세후 금액을 넣으세요.',
  EXCHANGE: '달러→원화를 체크하지 않으면 원화→달러(달러 매수)입니다. 외화·환율·원화 중 두 칸만 넣으면 나머지는 계산합니다 (양수로).',
};

/** 줄 하나의 인식 결과 (표 종류와 상관없이 화면에 쓰는 모양). */
interface RowView {
  status: ImportStatus;
  text: string;
  warning: boolean;
}

/**
 * 표로 일괄 입력 (입출금 · 매매 · 배당): 빈 표에 엑셀·구글시트에서 복사한 칸을 붙여 넣으면, 고른 칸부터 오른쪽·아래로 채운다.
 * 줄마다 인식 결과를 보여주고, [저장]을 누르면 기록을 한꺼번에 저장한다 (매매는 새 종목도 함께). 한투 연결 계좌는 매매를 불러오므로 매매 표를 뺀다.
 */
export function TradeImportPage() {
  const { accountId = '' } = useParams();
  const data = useAppData();
  const { uid } = useData();
  const navigate = useNavigate();
  const [kindChoice, setKind] = useState<ImportKind>('TRADE');
  const [grids, setGrids] = useState<Record<ImportKind, Grid>>(() => ({
    CASH: emptyGrid(10, CASH_COLUMNS), TRADE: emptyGrid(), DIVIDEND: emptyGrid(10, DIVIDEND_COLUMNS), EXCHANGE: emptyGrid(10, EXCHANGE_COLUMNS),
  }));
  const [overrides, setOverrides] = useState<Map<string, Pick<NewHolding, 'market' | 'assetType'>>>(new Map());
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [addRowsText, setAddRowsText] = useState('');

  const account = data.lookup.account(accountId);
  const accountHoldings = useMemo(() => data.holdings.filter((h) => h.accountId === accountId), [data.holdings, accountId]);
  const accountRecords = useMemo(() => data.records.filter((r) => r.accountId === accountId), [data.records, accountId]);
  // 한투 연결 계좌는 매매를 불러오므로 매매 표를 뺀다 (중복 방지)
  const linked = data.linked.has(accountId);
  // 한투 연결 계좌는 매매를 불러오고 환전 기록을 쓰지 않는다
  const kinds = KINDS.filter((k) => !linked || (k !== 'TRADE' && k !== 'EXCHANGE'));
  const kind = kinds.includes(kindChoice) ? kindChoice : kinds[0];
  const grid = grids[kind];
  const columns = KIND_COLUMNS[kind];
  const headers = KIND_HEADERS[kind];
  const setGrid = (next: Grid) => setGrids((all) => ({ ...all, [kind]: next }));

  const analysis = useMemo(
    () => analyzeImport(grids.TRADE, accountId, accountHoldings, data.holdings, accountRecords, overrides),
    [grids.TRADE, accountId, accountHoldings, data.holdings, accountRecords, overrides],
  );
  const cashRows = useMemo(() => analyzeCashFlows(grids.CASH, accountId, accountRecords), [grids.CASH, accountId, accountRecords]);
  const dividendRows = useMemo(() => analyzeDividends(grids.DIVIDEND, accountHoldings, accountRecords), [grids.DIVIDEND, accountHoldings, accountRecords]);
  const exchangeRows = useMemo(() => analyzeExchanges(grids.EXCHANGE, accountId, accountRecords), [grids.EXCHANGE, accountId, accountRecords]);
  if (!account) return <NotFound what="계좌" />;

  // 줄마다 인식 결과
  const views: (RowView | undefined)[] =
    kind === 'TRADE'
      ? analysis.rows.map((row) => {
        // 수량 단위 (금현물은 g)
        const unit = quantityUnit(row.holding?.assetType ?? analysis.newHoldings.find((n) => n.key === row.newKey)?.assetType);
        const warning = row.status === 'OK' ? row.warning ?? null : null;
        return { status: row.status, text: statusText(row, unit) + (warning ? `\n${warning}` : ''), warning: warning !== null };
      })
      : kind === 'CASH'
        ? cashRows.map((row) => ({
          status: row.status,
          text: row.status === 'OK' ? `${row.type === 'DEPOSIT' ? '입금' : '출금'} ${won(row.amount)}` : commonText(row.status, row.error),
          warning: false,
        }))
        : kind === 'EXCHANGE'
          ? exchangeRows.map((row) => ({
            status: row.status,
            text: row.status === 'OK'
              ? `${row.toKrw ? '달러→원화' : '원화→달러'} · ${usd(row.usd)} · ${won(row.krw)} (환율 ${decimal(row.krw / row.usd, 2)})${row.warning ? `\n${row.warning}` : ''}`
              : commonText(row.status, row.error),
            warning: row.status === 'OK' && row.warning !== null,
          }))
          : dividendRows.map((row) => ({
          status: row.status,
          text: row.status === 'OK'
            ? `${row.holding!.name} · 배당 ${formatAmount(marketCurrency(row.holding!.market), row.amount)}${row.warning ? `\n${row.warning}` : ''}`
            : commonText(row.status, row.error),
          warning: row.status === 'OK' && row.warning !== null,
        }));

  const anyContent = KINDS.some((k) => grids[k].some((row) => row.some((c) => c.trim() !== '')));
  const hasContent = grid.some((row) => row.some((c) => c.trim() !== ''));
  const errors = views.flatMap((v, i) => (v?.status === 'ERROR' ? [i] : []));
  const count = kind === 'TRADE' ? saveCount(analysis) : views.filter((v) => v?.status === 'OK').length;
  const duplicates = views.filter((v) => v?.status === 'DUPLICATE').length;

  const setCell = (r: number, c: number, value: string) =>
    setGrid(grid.map((row, i) => (i === r ? row.map((v, j) => (j === c ? value : v)) : row)));
  const onPaste = (r: number, c: number) => (e: ClipboardEvent<HTMLInputElement>) => {
    const text = e.clipboardData.getData('text/plain');
    // 칸 하나짜리 글은 그대로 입력칸에 붙인다
    if (!text.includes('\t') && !text.replace(/\r?\n$/, '').includes('\n')) return;
    e.preventDefault();
    setGrid(pasteInto(grid, r, c, text, columns));
  };
  const setOverride = (n: NewHolding, change: Partial<Pick<NewHolding, 'market' | 'assetType'>>) => {
    const next = new Map(overrides);
    next.set(n.key, { market: n.market, assetType: n.assetType, ...change });
    setOverrides(next);
  };
  const leave = () => {
    if (!anyContent || window.confirm('저장하지 않은 표는 사라집니다. 나갈까요?')) navigate(`/accounts/${accountId}`);
  };
  const save = async () => {
    setSaving(true);
    setSaveError(null);
    try {
      if (kind !== 'TRADE') {
        const records = kind === 'CASH' ? buildCashFlowRecords(cashRows, accountId)
          : kind === 'EXCHANGE' ? buildExchangeRecords(exchangeRows, accountId)
            : buildDividendRecords(dividendRows, accountId);
        await importTrades(uid, new Map(), records);
        navigate(`/accounts/${accountId}`);
        return;
      }
      // 새 종목 기록은 holdingId 에 키를 넣어 두고, 저장할 때 새 종목 id 로 바꾼다
      const records = buildImportRecords(analysis.rows, accountId, (key) => key).map(([, r]) => r);
      const used = new Set(records.map((r) => r.holdingId));
      const newHoldings = new Map<string, Holding>();
      for (const n of analysis.newHoldings) {
        if (!used.has(n.key)) continue;
        newHoldings.set(n.key, {
          id: '', accountId, name: n.name, code: n.code, market: n.assetType === 'GOLD' ? 'KR' : n.market, assetType: n.assetType,
          manualPrice: null, manualPriceDate: null, createdAt: 0,
        });
      }
      await importTrades(uid, newHoldings, records);
      navigate(`/accounts/${accountId}`);
    } catch {
      setSaveError('저장하지 못했습니다. 잠시 후 다시 시도하세요.');
      setSaving(false);
    }
  };

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <p className="muted small">
            <button className="text-button inline" onClick={leave}>{account.name}</button>
          </p>
          <h1 className="page-title">표로 일괄 입력</h1>
          <p className="muted small">
            엑셀·구글시트에서 복사한 칸을, 채우기 시작할 칸을 누르고 붙여 넣으세요(Ctrl+V). 고른 칸부터 오른쪽·아래로 채워지고 줄은 자동으로 늘어납니다.
            열이나 줄을 나눠 여러 번 붙여 넣어도 됩니다.
          </p>
        </div>
      </div>

      <Card>
        <div className="segmented" role="radiogroup" aria-label="표 종류">
          {kinds.map((k) => (
            <button key={k} role="radio" aria-checked={kind === k} className={kind === k ? 'on' : ''} onClick={() => setKind(k)}>
              {KIND_LABEL[k]}
            </button>
          ))}
        </div>
        <p className="muted small">
          {KIND_HELP[kind]}
          {linked && ' 한투 연결 계좌는 매매를 불러오므로 매매 표가 없습니다.'}
        </p>
        <div className="table-wrap">
          <table className={`table import-table import-${kind.toLowerCase()}`}>
            <thead>
              <tr>
                <th className="num">#</th>
                {headers.map((h) => <th key={h}>{h}</th>)}
                <th>인식 결과</th>
                <th aria-label="줄 빼기" />
              </tr>
            </thead>
            <tbody>
              {grid.map((row, r) => {
                const view = views[r];
                const rowClass = view?.status === 'ERROR' ? 'import-error' : view?.status === 'DUPLICATE' ? 'import-duplicate' : view?.warning ? 'import-warning' : '';
                return (
                  <tr key={r} className={rowClass}>
                    <td className="num muted small">{r + 1}</td>
                    {Array.from({ length: columns }, (_, c) => (
                      <td key={c} className={`import-cell col-${c}`}>
                        {c === KIND_CHECK[kind] ? (
                          // 체크박스 칸 (붙여 넣은 글이 체크로 읽히면 체크 표시)
                          <input
                            type="checkbox"
                            className="import-check"
                            aria-label={`${r + 1}번 줄 ${headers[c]}`}
                            checked={parseCheck(row[c] ?? '') === true}
                            onChange={(e) => setCell(r, c, e.target.checked ? EXCHANGE_CHECKED : '')}
                          />
                        ) : (
                          <input
                            aria-label={`${r + 1}번 줄 ${headers[c]}`}
                            value={row[c] ?? ''}
                            onChange={(e) => setCell(r, c, e.target.value)}
                            onPaste={onPaste(r, c)}
                          />
                        )}
                      </td>
                    ))}
                    <td className="import-status small">{view?.text ?? ''}</td>
                    <td>
                      <button
                        className="icon-button"
                        aria-label={`${r + 1}번 줄 빼기`}
                        onClick={() => setGrid(grid.length > 1 ? grid.filter((_, i) => i !== r) : emptyGrid(1, columns))}
                      >✕</button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div className="button-row">
          <button onClick={() => setGrid([...grid, ...emptyGrid(Math.min(5, IMPORT_MAX_ROWS - grid.length), columns)])} disabled={grid.length >= IMPORT_MAX_ROWS}>+ 5줄 추가</button>
          {/* 원하는 만큼 줄 추가 (표 전체 최대 300줄) */}
          <span className="add-rows">
            <input
              inputMode="numeric"
              aria-label="추가할 줄 수"
              placeholder="줄 수"
              value={addRowsText}
              onChange={(e) => setAddRowsText(e.target.value.replace(/[^0-9]/g, '').slice(0, 3))}
            />
            <button
              onClick={() => {
                setGrid([...grid, ...emptyGrid(Math.min(Number(addRowsText), IMPORT_MAX_ROWS - grid.length), columns)]);
                setAddRowsText('');
              }}
              disabled={!(Number(addRowsText) > 0) || grid.length >= IMPORT_MAX_ROWS}
            >줄 추가</button>
          </span>
          <button
            onClick={() => {
              if (window.confirm(`이 표(${KIND_LABEL[kind]})에 입력한 칸을 모두 지울까요?`)) {
                setGrid(emptyGrid(10, columns));
                if (kind === 'TRADE') setOverrides(new Map());
              }
            }}
            disabled={!hasContent}
          >표 비우기</button>
        </div>
      </Card>

      {kind === 'TRADE' && analysis.newHoldings.length > 0 && (
        <Card title={`새로 만들 종목 ${analysis.newHoldings.length}개`}>
          <p className="muted small">이 계좌에 없는 종목은 저장할 때 함께 만듭니다. 시장·자산 유형을 확인하세요.</p>
          {analysis.newHoldings.map((n) => (
            <div key={n.key} className="sub-card">
              <strong>{n.name}{n.code && n.code !== n.name ? ` (${n.code})` : ''}</strong>
              {n.assetType !== 'GOLD' && (
                <Choice label="시장" options={MARKETS} value={n.market} text={(m) => MARKET_LABEL[m]} onChange={(m) => setOverride(n, { market: m })} />
              )}
              <Choice
                label="자산 유형"
                options={ASSET_TYPES}
                value={n.assetType}
                text={(t) => ASSET_TYPE_LABEL[t]}
                onChange={(t) => setOverride(n, { assetType: t, market: t === 'GOLD' ? 'KR' : n.market })}
              />
            </div>
          ))}
        </Card>
      )}

      <Card>
        <p>
          <strong>
            저장할 {KIND_LABEL[kind]} 기록 {count}건
            {kind === 'TRADE' && analysis.newHoldings.length > 0 && ` · 새 종목 ${analysis.newHoldings.length}개`}
            {duplicates > 0 && ` · 중복 의심 ${duplicates}건 제외`}
          </strong>
        </p>
        {errors.length > 0 && (
          <Warning>
            오류가 있는 줄: {errors.slice(0, 10).map((i) => `${i + 1}번`).join(', ')}
            {errors.length > 10 && ` 외 ${errors.length - 10}줄`} — 고치거나 줄을 빼세요.
          </Warning>
        )}
        {errors.length === 0 && count === 0 && <Warning>저장할 줄이 없습니다. 표에 붙여 넣거나 입력하세요.</Warning>}
        {kind === 'TRADE' && views.some((v) => v?.warning) && (
          <Warning>보유 수량이 모자란 매도 줄이 있습니다. 저장은 되지만 그 매도는 계산되지 않습니다 — 같은 날 매수 줄을 위로 옮기세요.</Warning>
        )}
        {kind === 'TRADE' && (analysis.brokenSells?.length ?? 0) > 0 && (
          <Warning>
            저장하면 아래 기존 매도가 보유 수량이 모자라 계산되지 않습니다:{' '}
            {analysis.brokenSells!.map((s) => shortSellLabel(s, data.holdings.find((h) => h.id === s.record.holdingId))).join(', ')}
          </Warning>
        )}
        {saveError && <Warning>{saveError}</Warning>}
        <div className="button-row">
          <button onClick={leave}>취소</button>
          <button className="primary" onClick={save} disabled={saving || errors.length > 0 || count === 0}>{saving ? '저장 중…' : '저장'}</button>
        </div>
      </Card>
    </div>
  );
}
