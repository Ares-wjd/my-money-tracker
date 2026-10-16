import { useState } from 'react';
import { Choice, DateField, FormActions, Modal, NumberField, TextField, WonField, numberText } from '../components/form';
import { Row } from '../components/ui';
import { formatDate, today } from '../core/dates';
import { amount as formatAmount, amountPreview, decimal, manwon, parseDecimal, usd, won } from '../core/format';
import { NO_COST, buildRecord, costBreakdown, costPartsLocked, effectiveCost, inputFromRecord, recordProblem, type RecordInput } from '../core/recordForm';
import { computeExchange, exchangeAutoField, touchExchange, type ExchangeField } from '../core/exchangeInput';
import { useAppData, useData } from '../data/DataContext';
import { deleteRecord, saveRecord } from '../data/writes';
import { CURRENCIES, CURRENCY_LABEL, RECORD_TYPE_LABEL, marketCurrency, type Currency, type Record, type RecordType } from '../model';
import { recordAmountText, recordDetailText, recordTitle } from '../records/recordText';
import { newlyShortSells, type ShortSell } from '../core/portfolio';
import { shortSellLabel } from '../pages/text';

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
        holdingId: presetHoldingId ?? null, quantity: null, price: null, cost: NO_COST, memo: '',
      };
  const recordType = existing?.type ?? type;
  const [date, setDate] = useState(initial.date);
  const [currency, setCurrency] = useState<Currency>(initial.currency);
  const [amountText, setAmountText] = useState(numberText(initial.amount));
  const [krwText, setKrwText] = useState(numberText(initial.krw));
  // 새 환전 기록은 환율을 비워 둔다 (세 칸 중 입력한 두 칸으로 계산하므로)
  const [rateText, setRateText] = useState(!existing && type === 'EXCHANGE' ? '' : numberText(initial.rate, 4));
  const [toAccountId, setToAccountId] = useState<string | null>(initial.toAccountId);
  const [holdingId, setHoldingId] = useState<string | null>(initial.holdingId);
  const [quantityText, setQuantityText] = useState(numberText(initial.quantity));
  const [priceText, setPriceText] = useState(numberText(initial.price));
  const [costText, setCostText] = useState(numberText(initial.cost.total));
  const [feeText, setFeeText] = useState(numberText(initial.cost.fee));
  const [taxText, setTaxText] = useState(numberText(initial.cost.tax));
  const [memo, setMemo] = useState(initial.memo);
  // 환전: 입력한 순서 (최근 두 칸으로 나머지 한 칸을 자동 계산). 기존 기록은 원화·달러를 입력한 것으로 본다 → 환율이 자동
  const [exchangeEdited, setExchangeEdited] = useState<ExchangeField[]>(existing?.type === 'EXCHANGE' ? ['KRW', 'USD'] : []);

  const holdings = data.holdings.filter((h) => h.accountId === accountId);
  const holding = holdings.find((h) => h.id === holdingId);
  const holdingCurrency: Currency = holding ? marketCurrency(holding.market) : 'KRW';
  const exAuto = recordType === 'EXCHANGE' ? exchangeAutoField(exchangeEdited) : null;
  const exComputed = exAuto === null ? null : computeExchange(exAuto, parseDecimal(krwText), parseDecimal(amountText), parseDecimal(rateText));
  const pick = (field: ExchangeField, text: string) => (exAuto === field ? exComputed : parseDecimal(text));
  // 자동 칸에 보여줄 값 (계산할 수 없으면 빈칸)
  const exchangeText = (field: ExchangeField, text: string, digits: number) =>
    exAuto === field ? (exComputed === null ? '' : String(Number(exComputed.toFixed(digits)))) : text;
  const exchangeLabel = (field: ExchangeField, label: string) => (exAuto === field ? `${label} · 자동 계산` : label);
  const input: RecordInput = {
    type: recordType, date, currency, toAccountId, holdingId, memo,
    amount: pick('USD', amountText), krw: pick('KRW', krwText), rate: pick('RATE', rateText),
    quantity: parseDecimal(quantityText), price: parseDecimal(priceText),
    cost: { total: parseDecimal(costText), fee: parseDecimal(feeText), tax: parseDecimal(taxText) },
  };
  const problem = recordProblem(input, holdings, data.records, existing?.id ?? null);
  // 이렇게 저장·삭제하면 다른 매도가 보유 수량이 모자라 계산되지 않게 되는지 (확인 창으로 알림)
  const breakText = (list: ShortSell[]) =>
    list.length === 0 ? null : `이렇게 하면 아래 매도가 보유 수량이 모자라 계산되지 않습니다.\n${list.map((s) => '· ' + shortSellLabel(s, data.lookup.holding(s.record.holdingId ?? ''))).join('\n')}`;
  const breakOnSave = problem === null ? breakText(newlyShortSells(data.records, existing, buildRecord(input, accountId, existing))) : null;
  const breakOnDelete = existing ? breakText(newlyShortSells(data.records, existing, null)) : null;
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
          {currency === 'KRW' ? (
            <WonField
              label={recordType === 'CASH_ADJUST' ? '조정 금액 (+ 증가 / − 감소)' : recordType === 'CASH_BALANCE' ? '그날의 예수금' : '금액'}
              value={amountText}
              onChange={setAmountText}
              allowNegative={recordType === 'CASH_ADJUST' || recordType === 'CASH_BALANCE'}
              preview={manwon}
              // 딱 떨어지는 금액을 적는 입금·출금·이체에만 빠른 입력 버튼
              quick={recordType === 'DEPOSIT' || recordType === 'WITHDRAW' || recordType === 'TRANSFER'}
            />
          ) : (
            <NumberField
              label={recordType === 'CASH_ADJUST' ? '조정 금액 (+ 증가 / − 감소)' : recordType === 'CASH_BALANCE' ? '그날의 예수금' : '금액'}
              value={amountText}
              onChange={setAmountText}
              allowNegative={recordType === 'CASH_ADJUST' || recordType === 'CASH_BALANCE'}
              preview={usd}
            />
          )}
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
          {/* 셋 중 마지막으로 입력한 두 칸으로 나머지 한 칸을 자동 계산 */}
          <WonField
            label={exchangeLabel('KRW', '원화 금액')}
            value={exchangeText('KRW', krwText, 0)}
            onChange={(v) => { setKrwText(v); setExchangeEdited(touchExchange(exchangeEdited, 'KRW')); }}
            preview={manwon}
          />
          <NumberField
            label={exchangeLabel('USD', '달러 금액')}
            value={exchangeText('USD', amountText, 2)}
            onChange={(v) => { setAmountText(v); setExchangeEdited(touchExchange(exchangeEdited, 'USD')); }}
            preview={usd}
          />
          <NumberField
            label={exchangeLabel('RATE', '환율 (1달러당 원)')}
            value={exchangeText('RATE', rateText, 2)}
            onChange={(v) => { setRateText(v); setExchangeEdited(touchExchange(exchangeEdited, 'RATE')); }}
            preview={(n) => `1달러 = ${decimal(n, 2)}원`}
          />
          {exAuto === null && <p className="muted small">원화 금액·달러 금액·환율 중 두 칸을 입력하면 나머지 한 칸은 자동으로 계산합니다.</p>}
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
              <NumberField label={holding?.assetType === 'GOLD' ? '수량 (g)' : '수량'} value={quantityText} onChange={setQuantityText} />
              <NumberField label={`단가 (${CURRENCY_LABEL[holdingCurrency]}${holding?.assetType === 'GOLD' ? ', 1g당' : ''})`} value={priceText} onChange={setPriceText} preview={(n) => amountPreview(holdingCurrency, n)} />
              <div className="sub-card">
                {/* 수수료·세금을 쓰면 제비용합은 자동 계산(잠김), 제비용합만 쓰면 수수료·세금이 잠긴다 */}
                <NumberField
                  label="제비용합"
                  value={costBreakdown(input.cost) ? numberText(effectiveCost(input.cost)) : costText}
                  onChange={setCostText}
                  disabled={costBreakdown(input.cost)}
                  preview={(n) => amountPreview(holdingCurrency, n)}
                />
                <div className="field-row">
                  <NumberField label="수수료" value={feeText} onChange={setFeeText} disabled={costPartsLocked(input.cost)} preview={(n) => amountPreview(holdingCurrency, n)} />
                  <NumberField label="세금" value={taxText} onChange={setTaxText} disabled={costPartsLocked(input.cost)} preview={(n) => amountPreview(holdingCurrency, n)} />
                </div>
                <p className="muted small">
                  {costBreakdown(input.cost)
                    ? '수수료·세금의 합으로 제비용합을 계산합니다.'
                    : costPartsLocked(input.cost)
                      ? '제비용합만 저장합니다. 나눠 쓰려면 제비용합을 비우세요.'
                      : '제비용합만 쓰거나, 수수료·세금을 나눠 쓰세요.'}
                </p>
              </div>
              {input.quantity !== null && input.price !== null && (
                <Row
                  label={recordType === 'BUY' ? '총 매수 금액 (제비용 포함)' : '정산 금액 (제비용 차감)'}
                  value={formatAmount(holdingCurrency, input.quantity * input.price + (recordType === 'BUY' ? 1 : -1) * effectiveCost(input.cost))}
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
        deleteWarning={'이 기록을 삭제할까요? 투자금·평가금·수익률 계산이 다시 이루어집니다.' + (breakOnDelete ? `\n\n${breakOnDelete}` : '')}
        saveWarning={problem === null ? breakOnSave : null}
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
