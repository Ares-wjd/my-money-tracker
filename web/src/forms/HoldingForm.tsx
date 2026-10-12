import { useState } from 'react';
import { Choice, DateField, FormActions, Modal, NumberField, TextField } from '../components/form';
import { today } from '../core/dates';
import { amountPreview, parseDecimal } from '../core/format';
import { useAppData, useData } from '../data/DataContext';
import { deleteHolding, saveHolding, saveRecord } from '../data/writes';
import { ASSET_TYPES, ASSET_TYPE_LABEL, CURRENCY_LABEL, GOLD_CODE, MARKETS, MARKET_LABEL, marketCurrency, type AssetType, type Holding, type Market } from '../model';

export function HoldingForm({ accountId, existing, onClose, onDeleted }: { accountId: string; existing: Holding | null; onClose: () => void; onDeleted?: () => void }) {
  const { uid } = useData();
  const data = useAppData();
  // 금현물 통장에는 금만 넣는다
  const goldAccount = data.lookup.account(accountId)?.kind === 'GOLD';
  const [name, setName] = useState(existing?.name ?? (goldAccount ? 'KRX 금현물' : ''));
  const [code, setCode] = useState(existing?.code ?? (goldAccount ? GOLD_CODE : ''));
  const [market, setMarket] = useState<Market>(existing?.market ?? 'KR');
  const [assetType, setAssetType] = useState<AssetType>(goldAccount ? 'GOLD' : existing?.assetType ?? 'STOCK');
  const gold = assetType === 'GOLD';
  const chooseType = (t: AssetType) => {
    setAssetType(t);
    // 금현물은 국내(원) 고정, 코드가 비어 있으면 KRX 금 1kg 코드
    if (t === 'GOLD') {
      setMarket('KR');
      if (code.trim() === '') setCode(GOLD_CODE);
    }
  };
  const [hasInitial, setHasInitial] = useState(false);
  const [quantityText, setQuantityText] = useState('');
  const [averageText, setAverageText] = useState('');
  const [initialDate, setInitialDate] = useState(today());

  const currency = marketCurrency(gold ? 'KR' : market);
  const quantity = parseDecimal(quantityText);
  const average = parseDecimal(averageText);
  const problem =
    name.trim() === '' ? '종목 이름을 입력하세요.'
      : hasInitial && !(quantity !== null && quantity > 0 && average !== null && average >= 0) ? '보유 수량과 평균단가를 입력하세요.'
        : null;

  const save = async () => {
    const id = await saveHolding(uid, {
      id: existing?.id ?? '',
      accountId,
      name: name.trim(),
      code: code.trim(),
      market: gold ? 'KR' : market,
      assetType,
      manualPrice: existing?.manualPrice ?? null,
      manualPriceDate: existing?.manualPriceDate ?? null,
      createdAt: existing?.createdAt ?? 0,
    });
    if (!existing && hasInitial && quantity !== null && average !== null) {
      await saveRecord(uid, {
        id: '', accountId, type: 'BUY', date: initialDate, currency: 'KRW', amount: 0, krwAmount: 0, toAccountId: null,
        holdingId: id, quantity, price: average, fee: 0, tax: 0, initial: true, externalId: null, memo: '', createdAt: 0,
      });
    }
  };

  return (
    <Modal title={existing ? '종목 편집' : '종목 추가'} onClose={onClose}>
      <TextField label="종목 이름" placeholder="예: 삼성전자, Apple" value={name} onChange={setName} maxLength={50} />
      <TextField label="종목 코드" hint="국내 6자리 / 해외 티커. 펀드·채권은 비워도 됩니다." value={code} onChange={(v) => setCode(v.toUpperCase().trim())} maxLength={20} />
      {gold ? (
        <p className="muted small">금현물: KRX 금 99.99 1kg (종목코드 {GOLD_CODE}, 미니금 100g 은 M04020100). 국내(원) 시장, 수량은 g, 가격은 1g당.</p>
      ) : (
        <Choice label="시장" options={MARKETS} value={market} text={(m) => `${MARKET_LABEL[m]} (${CURRENCY_LABEL[marketCurrency(m)]})`} onChange={setMarket} />
      )}
      {!goldAccount && <Choice label="자산 유형" options={ASSET_TYPES} value={assetType} text={(t) => ASSET_TYPE_LABEL[t]} onChange={chooseType} />}
      {!existing && (
        <div className="sub-card">
          <label className="check">
            <input type="checkbox" checked={hasInitial} onChange={(e) => setHasInitial(e.target.checked)} />
            이미 보유 중인 종목입니다 (초기 보유분 입력)
          </label>
          {hasInitial && (
            <>
              <p className="muted small">현재 수량과 평균단가를 기준일의 매수 기록으로 남깁니다. 기존 투자금은 입금 기록으로, 예수금은 계좌 화면의 예수금 입력으로 넣어 주세요.</p>
              <NumberField label={gold ? '보유 수량 (g)' : '보유 수량'} value={quantityText} onChange={setQuantityText} />
              <NumberField label={`평균단가 (${CURRENCY_LABEL[currency]}${gold ? ', 1g당' : ''})`} value={averageText} onChange={setAverageText} preview={(n) => amountPreview(currency, n)} />
              <DateField label="기준일" value={initialDate} onChange={setInitialDate} />
            </>
          )}
        </div>
      )}
      <FormActions
        problem={problem}
        onSave={save}
        onDone={onClose}
        onDelete={existing ? () => deleteHolding(uid, existing.id, data.records).then(() => onDeleted?.()) : undefined}
        deleteLabel="종목 삭제"
        deleteWarning="이 종목의 매수·매도·배당 기록이 모두 함께 삭제됩니다. 되돌릴 수 없습니다."
      />
    </Modal>
  );
}

/** 현재가 직접 입력 (펀드·채권 등). 비우고 저장하면 직접 입력 가격을 지운다. */
export function PriceForm({ holding, onClose }: { holding: Holding; onClose: () => void }) {
  const { uid } = useData();
  const currency = marketCurrency(holding.market);
  const [priceText, setPriceText] = useState(holding.manualPrice === null ? '' : String(holding.manualPrice));
  const [date, setDate] = useState(holding.manualPriceDate ?? today());
  const price = parseDecimal(priceText);
  const problem = priceText.trim() !== '' && (price === null || price < 0) ? '가격을 확인하세요.' : null;

  return (
    <Modal title="현재가 직접 입력" onClose={onClose}>
      <p className="muted small">자동 시세가 없는 종목(펀드·채권 등)이나 시세가 틀릴 때 씁니다. 자동 시세와 직접 입력 중 날짜가 더 최근인 값을 씁니다. 비우고 저장하면 직접 입력 가격을 지웁니다.</p>
      <NumberField label={`현재가 (${CURRENCY_LABEL[currency]}${holding.assetType === 'GOLD' ? ', 1g당' : ''})`} value={priceText} onChange={setPriceText} preview={(n) => amountPreview(currency, n)} />
      <DateField label="기준일" value={date} onChange={setDate} />
      <FormActions
        problem={problem}
        onSave={() =>
          saveHolding(uid, { ...holding, manualPrice: priceText.trim() === '' ? null : price, manualPriceDate: priceText.trim() === '' ? null : date })
        }
        onDone={onClose}
      />
    </Modal>
  );
}
