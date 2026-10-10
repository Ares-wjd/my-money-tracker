import type { InvestmentAccount } from '../model';
import { ACCOUNT_KIND_LABEL } from '../model';

/** "일반(위탁) · 1234-5678 · 한투 연결" */
export function accountDescription(account: InvestmentAccount, linked: boolean, withKind = false): string {
  return [withKind ? ACCOUNT_KIND_LABEL[account.kind] : '', account.number, linked ? '한투 연결' : '']
    .filter((s) => s.trim() !== '')
    .join(' · ');
}
