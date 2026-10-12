import { useMemo, useState, type ClipboardEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Choice } from '../components/form';
import { Card, NotFound, Warning } from '../components/ui';
import {
  IMPORT_COLUMNS, IMPORT_HEADERS, IMPORT_MAX_ROWS, analyzeImport, buildImportRecords, emptyGrid, pasteInto, saveCount,
  type Grid, type ImportRow, type NewHolding,
} from '../core/tradeImport';
import { useAppData, useData } from '../data/DataContext';
import { importTrades } from '../data/writes';
import { ASSET_TYPES, ASSET_TYPE_LABEL, MARKETS, MARKET_LABEL, type Holding } from '../model';

function statusText(row: ImportRow | undefined): string {
  if (!row || row.status === 'EMPTY') return '';
  if (row.status === 'ERROR') return row.error ?? '';
  if (row.status === 'DUPLICATE') return '중복 의심 (저장 안 함)';
  return `${row.holding ? '기존 종목' : '새 종목'} · ${row.type === 'SELL' ? '매도' : '매수'}`;
}

/**
 * 표로 일괄 입력 (매수·매도): 빈 표에 엑셀·구글시트에서 복사한 칸을 붙여 넣으면, 고른 칸부터 오른쪽·아래로 채운다.
 * 줄마다 인식 결과를 보여주고, [저장]을 누르면 새 종목과 기록을 한꺼번에 저장한다.
 */
export function TradeImportPage() {
  const { accountId = '' } = useParams();
  const data = useAppData();
  const { uid } = useData();
  const navigate = useNavigate();
  const [grid, setGrid] = useState<Grid>(() => emptyGrid());
  const [overrides, setOverrides] = useState<Map<string, Pick<NewHolding, 'market' | 'assetType'>>>(new Map());
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const account = data.lookup.account(accountId);
  const accountHoldings = useMemo(() => data.holdings.filter((h) => h.accountId === accountId), [data.holdings, accountId]);
  const accountRecords = useMemo(() => data.records.filter((r) => r.accountId === accountId), [data.records, accountId]);
  const analysis = useMemo(
    () => analyzeImport(grid, accountId, accountHoldings, data.holdings, accountRecords, overrides),
    [grid, accountId, accountHoldings, data.holdings, accountRecords, overrides],
  );
  if (!account) return <NotFound what="계좌" />;
  if (data.linked.has(accountId)) {
    return (
      <Card>
        <p className="muted">한투 연결 계좌는 매수·매도를 불러오므로 일괄 입력을 쓰지 않습니다.</p>
        <Link to={`/accounts/${accountId}`}>계좌로 돌아가기</Link>
      </Card>
    );
  }

  const hasContent = grid.some((row) => row.some((c) => c.trim() !== ''));
  const errors = analysis.rows.filter((r) => r.status === 'ERROR');
  const count = saveCount(analysis);
  const duplicates = analysis.rows.filter((r) => r.status === 'DUPLICATE').length;

  const setCell = (r: number, c: number, value: string) =>
    setGrid(grid.map((row, i) => (i === r ? row.map((v, j) => (j === c ? value : v)) : row)));
  const onPaste = (r: number, c: number) => (e: ClipboardEvent<HTMLInputElement>) => {
    const text = e.clipboardData.getData('text/plain');
    // 칸 하나짜리 글은 그대로 입력칸에 붙인다
    if (!text.includes('\t') && !text.replace(/\r?\n$/, '').includes('\n')) return;
    e.preventDefault();
    setGrid(pasteInto(grid, r, c, text));
  };
  const setOverride = (n: NewHolding, change: Partial<Pick<NewHolding, 'market' | 'assetType'>>) => {
    const next = new Map(overrides);
    next.set(n.key, { market: n.market, assetType: n.assetType, ...change });
    setOverrides(next);
  };
  const leave = () => {
    if (!hasContent || window.confirm('저장하지 않은 표는 사라집니다. 나갈까요?')) navigate(`/accounts/${accountId}`);
  };
  const save = async () => {
    setSaving(true);
    setSaveError(null);
    try {
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
          <h1 className="page-title">표로 일괄 입력 (매수·매도)</h1>
          <p className="muted small">
            엑셀·구글시트에서 복사한 칸을, 채우기 시작할 칸을 누르고 붙여 넣으세요(Ctrl+V). 고른 칸부터 오른쪽·아래로 채워지고 줄은 자동으로 늘어납니다.
            열이나 줄을 나눠 여러 번 붙여 넣어도 됩니다.
          </p>
        </div>
      </div>

      <Card>
        <div className="table-wrap">
          <table className="table import-table">
            <thead>
              <tr>
                <th className="num">#</th>
                {IMPORT_HEADERS.map((h) => <th key={h}>{h}</th>)}
                <th>인식 결과</th>
                <th aria-label="줄 빼기" />
              </tr>
            </thead>
            <tbody>
              {grid.map((row, r) => {
                const result = analysis.rows[r];
                return (
                  <tr key={r} className={result?.status === 'ERROR' ? 'import-error' : result?.status === 'DUPLICATE' ? 'import-duplicate' : ''}>
                    <td className="num muted small">{r + 1}</td>
                    {Array.from({ length: IMPORT_COLUMNS }, (_, c) => (
                      <td key={c} className={`import-cell col-${c}`}>
                        <input
                          aria-label={`${r + 1}번 줄 ${IMPORT_HEADERS[c]}`}
                          value={row[c] ?? ''}
                          onChange={(e) => setCell(r, c, e.target.value)}
                          onPaste={onPaste(r, c)}
                        />
                      </td>
                    ))}
                    <td className="import-status small">{statusText(result)}</td>
                    <td>
                      <button
                        className="icon-button"
                        aria-label={`${r + 1}번 줄 빼기`}
                        onClick={() => setGrid(grid.length > 1 ? grid.filter((_, i) => i !== r) : emptyGrid(1))}
                      >✕</button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div className="button-row">
          <button onClick={() => setGrid([...grid, ...emptyGrid(5)])} disabled={grid.length >= IMPORT_MAX_ROWS}>+ 5줄 추가</button>
          <button
            onClick={() => {
              if (window.confirm('입력한 칸을 모두 지울까요?')) {
                setGrid(emptyGrid());
                setOverrides(new Map());
              }
            }}
            disabled={!hasContent}
          >표 비우기</button>
        </div>
      </Card>

      {analysis.newHoldings.length > 0 && (
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
            저장할 기록 {count}건
            {analysis.newHoldings.length > 0 && ` · 새 종목 ${analysis.newHoldings.length}개`}
            {duplicates > 0 && ` · 중복 의심 ${duplicates}건 제외`}
          </strong>
        </p>
        {errors.length > 0 && (
          <Warning>
            오류가 있는 줄: {errors.slice(0, 10).map((r) => `${r.index + 1}번`).join(', ')}
            {errors.length > 10 && ` 외 ${errors.length - 10}줄`} — 고치거나 줄을 빼세요.
          </Warning>
        )}
        {errors.length === 0 && count === 0 && <Warning>저장할 줄이 없습니다. 표에 붙여 넣거나 입력하세요.</Warning>}
        {saveError && <Warning>{saveError}</Warning>}
        <div className="button-row">
          <button onClick={leave}>취소</button>
          <button className="primary" onClick={save} disabled={saving || errors.length > 0 || count === 0}>{saving ? '저장 중…' : '저장'}</button>
        </div>
      </Card>
    </div>
  );
}
