import { useState } from 'react';
import { DateField, FormActions, Modal, NumberField, TextField } from '../components/form';
import { formatDate, today } from '../core/dates';
import { manwon, parseDecimal, usd } from '../core/format';
import type { AccountSummary } from '../core/portfolio';
import { useData } from '../data/DataContext';
import { cashRecordId, roundCash } from '../data/serialize';
import { saveRecord } from '../data/writes';
import { marketCurrency, type Currency } from '../model';

/** 증권사 앱에 보이는 예수금을 직접 입력한다. 같은 날 다시 입력하면 그날 값을 덮어쓴다 (폰과 같은 문서 ID). */
export function CashForm({ summary, onClose }: { summary: AccountSummary; onClose: () => void }) {
  const { uid } = useData();
  const showUsd = summary.cash.usd !== 0 || summary.holdings.some((h) => marketCurrency(h.holding.market) === 'USD');
  const [krwText, setKrwText] = useState(String(Math.round(summary.cash.krw)));
  const [usdText, setUsdText] = useState(summary.cash.usd === 0 ? '0' : summary.cash.usd.toFixed(2));
  const [date, setDate] = useState(today());
  const [memo, setMemo] = useState('');
  const krw = parseDecimal(krwText);
  const usdValue = showUsd ? parseDecimal(usdText) : null;
  const accountId = summary.account.id;

  const save = async () => {
    const entries: [Currency, number][] = [];
    if (krw !== null) entries.push(['KRW', krw]);
    if (usdValue !== null) entries.push(['USD', usdValue]);
    for (const [currency, value] of entries) {
      await saveRecord(uid, {
        id: cashRecordId(accountId, date, currency), accountId, type: 'CASH_BALANCE', date, currency,
        amount: roundCash(currency, value), krwAmount: 0, toAccountId: null, holdingId: null, quantity: 0, price: 0,
        fee: 0, tax: 0, initial: false, externalId: null, memo: memo.trim(), createdAt: Date.now(),
      });
    }
  };

  return (
    <Modal title="예수금 입력" onClose={onClose}>
      <p className="muted small">증권사 앱에 보이는 예수금을 그대로 입력하세요. 매매·입출금으로 계산하지 않고, 입력한 값이 이 계좌의 예수금이 됩니다.</p>
      <NumberField label="원화 예수금" value={krwText} onChange={setKrwText} allowDecimal={false} allowNegative preview={manwon} />
      {showUsd && <NumberField label="달러 예수금" value={usdText} onChange={setUsdText} allowNegative preview={usd} />}
      <DateField label="기준일" value={date} onChange={setDate} />
      <TextField label="메모 (선택)" value={memo} onChange={setMemo} maxLength={100} />
      {summary.cashDate && date < summary.cashDate && (
        <p className="warning">기준일이 마지막 입력일({formatDate(summary.cashDate)})보다 이전이라 지금 예수금은 바뀌지 않습니다.</p>
      )}
      <FormActions problem={krw === null && usdValue === null ? '예수금을 입력하세요.' : null} onSave={save} onDone={onClose} />
    </Modal>
  );
}
