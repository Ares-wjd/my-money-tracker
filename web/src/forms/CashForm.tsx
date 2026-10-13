import { useState } from 'react';
import { DateField, FormActions, Modal, NumberField, TextField, WonField } from '../components/form';
import { formatDate, today } from '../core/dates';
import { manwon, parseDecimal, signedWon, usd, won } from '../core/format';
import { cashDetail } from '../core/cash';
import type { AccountSummary } from '../core/portfolio';
import { useAppData, useData } from '../data/DataContext';
import { cashRecordId, roundCash } from '../data/serialize';
import { saveRecord } from '../data/writes';
import { marketCurrency, type Currency } from '../model';

/**
 * 증권사 앱에 보이는 예수금을 직접 입력한다. 같은 날 다시 입력하면 그날 값을 덮어쓴다 (폰과 같은 문서 ID).
 * 입력한 값은 그날의 기준점이 되고 그 사이는 기록으로 계산한다 (혼합 방식). 그날의 계산값과 차이를 보여준다.
 */
export function CashForm({ summary, onClose }: { summary: AccountSummary; onClose: () => void }) {
  const { uid } = useData();
  const data = useAppData();
  const showUsd = summary.cash.usd !== 0 || summary.holdings.some((h) => marketCurrency(h.holding.market) === 'USD');
  const [krwText, setKrwText] = useState(String(Math.round(summary.cash.krw)));
  const [usdText, setUsdText] = useState(summary.cash.usd === 0 ? '0' : summary.cash.usd.toFixed(2));
  const [date, setDate] = useState(today());
  const [memo, setMemo] = useState('');
  const krw = parseDecimal(krwText);
  const usdValue = showUsd ? parseDecimal(usdText) : null;
  const accountId = summary.account.id;
  // 그날의 계산값 (그날 입력한 값은 빼고 계산)
  const computed = (currency: Currency) =>
    cashDetail(
      accountId, currency,
      data.records.filter((r) => r.accountId === accountId || r.toAccountId === accountId),
      data.holdings.filter((h) => h.accountId === accountId),
      date, false, date,
    ).amount;
  const signedUsd = (n: number) => (n > 0 ? `+${usd(n)}` : usd(n));

  const save = async () => {
    const entries: [Currency, number][] = [];
    if (krw !== null) entries.push(['KRW', krw]);
    if (usdValue !== null) entries.push(['USD', usdValue]);
    for (const [currency, value] of entries) {
      await saveRecord(uid, {
        id: cashRecordId(accountId, date, currency), accountId, type: 'CASH_BALANCE', date, currency,
        amount: roundCash(currency, value), krwAmount: 0, toAccountId: null, holdingId: null, quantity: 0, price: 0,
        fee: 0, tax: 0, cost: null, initial: false, externalId: null, memo: memo.trim(), createdAt: Date.now(),
      });
    }
  };

  return (
    <Modal title="예수금 입력" onClose={onClose}>
      <p className="muted small">증권사 앱에 보이는 예수금을 그대로 입력하세요. 입력한 값이 그날의 기준이 되고, 그 사이 날짜는 매매·입출금·배당·환전 기록으로 계산합니다. 가끔 입력하면 기록되지 않은 이자·수수료만큼의 차이가 맞춰집니다.</p>
      <WonField label="원화 예수금" value={krwText} onChange={setKrwText} allowNegative preview={manwon} />
      {showUsd && <NumberField label="달러 예수금" value={usdText} onChange={setUsdText} allowNegative preview={usd} />}
      <DateField label="기준일" value={date} onChange={setDate} />
      <div className="sub-card">
        <div className="row"><span>{formatDate(date)} 계산값 (원화)</span><span>{won(computed('KRW'))}</span></div>
        {krw !== null && <div className="row"><span>차이</span><strong>{signedWon(krw - computed('KRW'))}</strong></div>}
        {showUsd && <div className="row"><span>{formatDate(date)} 계산값 (달러)</span><span>{usd(computed('USD'))}</span></div>}
        {showUsd && usdValue !== null && <div className="row"><span>차이</span><strong>{signedUsd(usdValue - computed('USD'))}</strong></div>}
        <p className="muted small">차이는 이자·수수료 등 기록되지 않은 금액입니다. 입력하면 그날부터 입력값 기준으로 다시 계산합니다.</p>
      </div>
      <TextField label="메모 (선택)" value={memo} onChange={setMemo} maxLength={100} />
      {summary.cashDate && date < summary.cashDate && (
        <p className="warning">기준일이 마지막 입력일({formatDate(summary.cashDate)})보다 이전이라 그 사이 날짜의 예수금만 바뀝니다 (지금 예수금은 마지막 입력 기준).</p>
      )}
      <FormActions problem={krw === null && usdValue === null ? '예수금을 입력하세요.' : null} onSave={save} onDone={onClose} />
    </Modal>
  );
}
