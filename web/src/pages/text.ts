import type { Holding, InvestmentAccount } from '../model';
import { ACCOUNT_KIND_LABEL, quantityUnit } from '../model';
import { formatShortDate } from '../core/dates';
import { decimal } from '../core/format';
import type { ShortSell } from '../core/portfolio';

/** "일반(위탁) · 1234-5678 · 한투 연결" */
export function accountDescription(account: InvestmentAccount, linked: boolean, withKind = false): string {
  return [withKind ? ACCOUNT_KIND_LABEL[account.kind] : '', account.number, linked ? '한투 연결' : '']
    .filter((s) => s.trim() !== '')
    .join(' · ');
}

/** 계산되지 않는 매도 한 줄: "10.14 삼성전자 매도 2주 (그 시점 보유 0주)" (폰과 같은 문구). */
export function shortSellLabel(s: ShortSell, holding: Holding | undefined): string {
  const unit = quantityUnit(holding?.assetType);
  return `${formatShortDate(s.record.date)} ${holding?.name ?? '종목'} 매도 ${decimal(s.record.quantity)}${unit} (그 시점 보유 ${decimal(s.held)}${unit})`;
}
