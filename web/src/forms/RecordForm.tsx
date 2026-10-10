import { useState } from 'react';
import { Choice, DateField, FormActions, Modal, NumberField, TextField, numberText } from '../components/form';
import { Row } from '../components/ui';
import { formatDate, today } from '../core/dates';
import { amount as formatAmount, amountPreview, decimal, manwon, parseDecimal, usd, won } from '../core/format';
import { buildRecord, inputFromRecord, recordProblem, type RecordInput } from '../core/recordForm';
import { useAppData, useData } from '../data/DataContext';
import { deleteRecord, saveRecord } from '../data/writes';
import { CURRENCIES, CURRENCY_LABEL, RECORD_TYPE_LABEL, marketCurrency, type Currency, type Record, type RecordType } from '../model';
import { recordAmountText, recordDetailText, recordTitle } from '../records/recordText';

interface Props {
  accountId: string;
  type: RecordType;
  existing: Record | null;
  presetHoldingId?: string | null;
  onClose: () => void;
}

export function RecordForm(props: Props) {
  if (props.existing && props.existing.externalId !== null) return <ImportedRecordForm record={props.existing} onClose={props.onClose} />;
  return <EditableRecordForm {...props} />;
}

function EditableRecordForm({ accountId, type, existing, presetHoldingId, onClose }: Props) {
  const { uid } = useData();
  const data = useAppData();
  const initial: RecordInput = existing
    ? inputFromRecord(existing, data.usdKrw)
    : {
        type, date: today(), currency: 'KRW', amount: null, krw: null, rate: data.usdKrw, toAccountId: null,
        holdingId: presetHoldingId ?? null, quantity: null, price: null, fee: 0, tax: 0, memo: '',
      };
  const recordType = existing?.type ?? type;
  const [date, setDate] = useState(initial.date);
  const [currency, setCurrency] = useState<Currency>(initial.currency);
  const [amountText, setAmountText] = useState(numberText(initial.amount));
  const [krwText, setKrwText] = useState(numberText(initial.krw));
  const [rateText, setRateText] = useState(numberText(initial.rate, 4));
  const [toAccountId, setToAccountId] = useState<string | null>(initial.toAccountId);
  const [holdingId, setHoldingId] = useState<string | null>(initial.holdingId);
  const [quantityText, setQuantityText] = useState(numberText(initial.quantity));
  const [priceText, setPriceText] = useState(numberText(initial.price));
  const [feeText, setFeeText] = useState(numberText(initial.fee));
  const [taxText, setTaxText] = useState(numberText(initial.tax));
  const [memo, setMemo] = useState(initial.memo);

  const holdings = data.holdings.filter((h) => h.accountId === accountId);
  const holding = holdings.find((h) => h.id === holdingId);
  const holdingCurrency: Currency = holding ? marketCurrency(holding.market) : 'KRW';
  const input: RecordInput = {
    type: recordType, date, currency, toAccountId, holdingId, memo,
    amount: parseDecimal(amountText), krw: parseDecimal(krwText), rate: parseDecimal(rateText),
    quantity: parseDecimal(quantityText), price: parseDecimal(priceText),
    fee: parseDecimal(feeText) ?? 0, tax: parseDecimal(taxText) ?? 0,
  };
  const problem = recordProblem(input, holdings, data.records, existing?.id ?? null);
  const others = data.accounts.filter((a) => a.id !== accountId);

  return (
    <Modal title={RECORD_TYPE_LABEL[recordType] + (existing ? ' 수정' : ' 기록')} onClose={onClose}>
      <p className="muted small">계좌: {data.lookup.account(accountId)?.name ?? ''}</p>
      <DateField label="날짜" value={date} onChange={setDate} />

      {(recordType === 'DEPOSIT' || recordType === 'WITHDRAW' || recordType === 'TRANSFER' || recordType === 'CASH_ADJUST' || recordType === 'CASH_BALANCE') && (
        <>
          {recordType === 'TRANSFER' &&
            (others.length === 0 ? (
              <p className="warning">이체할 다른 계좌가 없습니다. 계좌를 먼저 추가하세요.</p>
            ) : (
              <Choice label="받는 계좌" options={others.map((a) => a.id)} value={toAccountId} text={(id) => data.lookup.account(id)?.name ?? ''} onChange={setToAccountId} />
            ))}
          <Choice label="통화" options={CURRENCIES} value={currency} text={(c) => CURRENCY_LABEL[c]} onChange={setCurrency} />
          <NumberField
            label={recordType === 'CASH_ADJUST' ? '조정 금액 (+ 증가 / − 감소)' : recordType === 'CASH_BALANCE' ? '그날의 예수금' : '금액'}
            value={amountText}
            onChange={setAmountText}
            allowDecimal={currency === 'USD'}
            allowNegative={recordType === 'CASH_ADJUST' || recordType === 'CASH_BALANCE'}
            preview={(n) => amountPreview(currency, n)}
          />
          {currency === 'USD' && recordType !== 'CASH_ADJUST' && recordType !== 'CASH_BALANCE' && (
            <>
              <NumberField label="적용 환율 (원/달러, 투자금 원화 환산용)" value={rateText} onChange={setRateText} preview={(n) => `1달러 = ${decimal(n, 2)}원`} />
              {input.amount !== null && input.rate !== null && <Row label="원화 환산 투자금" value={won(input.amount * input.rate)} />}
            </>
          )}
        </>
      )}

      {recordType === 'EXCHANGE' && (
        <>
          <Choice label="방향" options={CURRENCIES} value={currency} text={(c) => (c === 'KRW' ? '원화 → 달러' : '달러 → 원화')} onChange={setCurrency} />
          <NumberField label="원화 금액" value={krwText} onChange={setKrwText} allowDecimal={false} preview={manwon} />
          <NumberField label="달러 금액" value={amountText} onChange={setAmountText} preview={usd} />
          {input.krw !== null && input.amount !== null && input.amount > 0 && <Row label="적용 환율" value={`${decimal(input.krw / input.amount, 2)}원`} />}
        </>
      )}

      {(recordType === 'BUY' || recordType === 'SELL' || recordType === 'DIVIDEND') && (
        <>
          {holdings.length > 0 && (
            <Choice label="종목" options={holdings.map((h) => h.id)} value={holdingId} text={(id) => data.lookup.holding(id)?.name ?? ''} onChange={setHoldingId} />
          )}
          {recordType === 'DIVIDEND' ? (
            <NumberField label={`배당금 (세후 실수령액, ${CURRENCY_LABEL[holdingCurrency]})`} value={amountText} onChange={setAmountText} preview={(n) => amountPreview(holdingCurrency, n)} />
          ) : (
            <>
              <NumberField label="수량" value={quantityText} onChange={setQuantityText} />
              <NumberField label={`단가 (${CURRENCY_LABEL[holdingCurrency]})`} value={priceText} onChange={setPriceText} preview={(n) => amountPreview(holdingCurrency, n)} />
              <div className="field-row">
                <NumberField label="수수료" value={feeText} onChange={setFeeText} preview={(n) => amountPreview(holdingCurrency, n)} />
                <NumberField label="세금" value={taxText} onChange={setTaxText} preview={(n) => amountPreview(holdingCurrency, n)} />
              </div>
              {input.quantity !== null && input.price !== null && (
                <Row
                  label={recordType === 'BUY' ? '총 매수 금액 (수수료·세금 포함)' : '정산 금액 (수수료·세금 차감)'}
                  value={formatAmount(holdingCurrency, input.quantity * input.price + (recordType === 'BUY' ? 1 : -1) * (input.fee + input.tax))}
                />
              )}
            </>
          )}
        </>
      )}

      <TextField label="메모 (선택)" value={memo} onChange={setMemo} maxLength={100} />
      <FormActions
        problem={problem}
        onSave={() => saveRecord(uid, buildRecord(input, accountId, existing))}
        onDone={onClose}
        onDelete={existing ? () => deleteRecord(uid, existing.id) : undefined}
        deleteLabel="기록 삭제"
        deleteWarning="이 기록을 삭제할까요? 투자금·평가금·수익률 계산이 다시 이루어집니다."
      />
    </Modal>
  );
}

/** 한투에서 불러온 기록: 내용은 고칠 수 없고 메모만 고칠 수 있다. */
function ImportedRecordForm({ record, onClose }: { record: Record; onClose: () => void }) {
  const { uid } = useData();
  const data = useAppData();
  const [memo, setMemo] = useState(record.memo);
  return (
    <Modal title="한투에서 불러온 기록" onClose={onClose}>
      <p className="muted small">한투에서 불러온 기록이라 내용은 고칠 수 없고 메모만 고칠 수 있습니다. 잘못되었다면 한투 앱의 내역을 확인하세요.</p>
      <Row label="날짜" value={formatDate(record.date)} />
      <Row label="내용" value={recordTitle(record, data.lookup, record.accountId)} />
      {recordDetailText(record, data.lookup) && <Row label="상세" value={recordDetailText(record, data.lookup)} />}
      <Row label="금액" value={recordAmountText(record, data.lookup, record.accountId)} />
      <TextField label="메모" value={memo} onChange={setMemo} maxLength={100} />
      <FormActions saveLabel="메모 저장" onSave={() => saveRecord(uid, { ...record, memo: memo.trim() })} onDone={onClose} />
    </Modal>
  );
}
