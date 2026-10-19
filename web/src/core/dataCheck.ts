// 데이터 점검 (v2.9, core DataCheck 와 같은 규칙·같은 설명 글): 계산이 틀어지거나 숫자가 비었을 수 있는 곳을 모은다.
// 무시한 항목은 지우지 않고 화면에 흐리게 남긴다 (alertCount 에서만 뺀다).
import type { Holding, InvestmentAccount, MarketSnapshot, Record } from '../model';
import { RECORD_TYPE_LABEL, marketCurrency, quantityUnit } from '../model';
import { amount, decimal, usd, won } from './format';
import { daysBetween, quotable, tradeUsdKrw } from './market';
import { compareRecords, krwHoldingView, shortSells, type PortfolioSummary } from './portfolio';

/** 단계: 계산이 틀어짐 / 비었거나 오래됨 / 확인해 보세요. */
export type CheckLevel = 'ERROR' | 'MISSING' | 'REVIEW';
export const CHECK_LEVELS: readonly CheckLevel[] = ['ERROR', 'MISSING', 'REVIEW'];
export const CHECK_LEVEL_LABEL: { [K in CheckLevel]: string } = { ERROR: '계산이 틀어짐', MISSING: '비었거나 오래됨', REVIEW: '확인해 보세요' };

/** 누르면 갈 곳. */
export type CheckTarget = 'RECORD' | 'ACCOUNT' | 'HOLDING' | 'SETTINGS';

export type CheckKind =
  | 'SHORT_SELL' | 'NEGATIVE_CASH' | 'USD_FLOW_NO_KRW' | 'ORPHAN_RECORD'
  | 'NO_PRICE' | 'STALE_PRICE' | 'NO_FX' | 'NO_TRADE_FX'
  | 'DUPLICATE' | 'EMPTY_HOLDING';

export interface CheckKindInfo {
  level: CheckLevel;
  target: CheckTarget;
  title: string;
  /** 무시하면 생기는 문제 (확인창 글). */
  ignoreEffect: string;
}

/** 종류별 정보 (순서 = 화면 순서, core Kind 순서와 같음). */
export const CHECK_KINDS: { [K in CheckKind]: CheckKindInfo } = {
  SHORT_SELL: { level: 'ERROR', target: 'RECORD', title: '계산되지 않는 매도', ignoreEffect: '이 매도는 계속 계산에서 빠집니다. 보유 수량·평균단가·실현손익이 실제와 다르게 보입니다.' },
  NEGATIVE_CASH: { level: 'ERROR', target: 'ACCOUNT', title: '음수 예수금', ignoreEffect: '음수 예수금이 그대로 평가금·수익금에 들어갑니다. 입금 기록이 빠진 경우라면 투자금·수익률도 실제와 다릅니다.' },
  USD_FLOW_NO_KRW: { level: 'ERROR', target: 'RECORD', title: '원화 금액이 빠진 달러 입출금', ignoreEffect: '이 기록이 투자금에 0원으로 들어가 투자금·수익금·수익률이 실제와 다르게 보입니다.' },
  ORPHAN_RECORD: { level: 'ERROR', target: 'RECORD', title: '주인 없는 기록', ignoreEffect: '지운 계좌·종목을 가리키는 기록이라 일부 계산(예수금 등)에만 섞이거나 빠져 숫자가 맞지 않을 수 있습니다.' },
  NO_PRICE: { level: 'MISSING', target: 'HOLDING', title: '현재가 없음', ignoreEffect: '이 종목이 평가금에서 빠져 평가금·수익률이 실제보다 낮게 보입니다.' },
  STALE_PRICE: { level: 'MISSING', target: 'HOLDING', title: '오래된 현재가', ignoreEffect: '오래된 가격으로 평가금·수익률을 계산합니다.' },
  NO_FX: { level: 'MISSING', target: 'SETTINGS', title: '환율 없음', ignoreEffect: '달러 예수금·해외 종목이 원화 합계에서 빠져 평가금·수익률이 실제보다 낮게 보입니다.' },
  NO_TRADE_FX: { level: 'MISSING', target: 'HOLDING', title: '매매일 환율 없음', ignoreEffect: '원화 보기에서 환차익을 반영하지 못하고 오늘 환율로 단순 환산합니다.' },
  DUPLICATE: { level: 'REVIEW', target: 'RECORD', title: '중복 의심', ignoreEffect: '같은 기록이 두 번 들어간 것이라면 수량·투자금·예수금이 두 배로 계산됩니다.' },
  EMPTY_HOLDING: { level: 'REVIEW', target: 'HOLDING', title: '기록 없는 종목', ignoreEffect: '계산에는 영향이 없습니다. 0주 종목으로 목록에 남습니다.' },
};
const KIND_ORDER = Object.keys(CHECK_KINDS) as CheckKind[];

/**
 * 점검 항목 하나. key 는 무시 목록에 저장하는 값 (종류:대상). 누르면 종류의 target 에 따라
 * recordIds 의 첫 기록 · accountId 계좌 · holdingId 종목 · 설정으로 간다.
 */
export interface CheckIssue {
  key: string;
  kind: CheckKind;
  detail: string;
  accountId: string | null;
  holdingId: string | null;
  recordIds: string[];
}

/** 자동 시세 종목은 7일, 직접 입력 종목은 30일 넘게 지난 가격을 오래된 것으로 본다. */
export const STALE_QUOTED_DAYS = 7;
export const STALE_MANUAL_DAYS = 30;

/** 설명 글의 날짜: "2026.10.5". */
export const checkDateText = (date: string): string => `${Number(date.slice(0, 4))}.${Number(date.slice(5, 7))}.${Number(date.slice(8, 10))}`;

const issue = (key: string, kind: CheckKind, detail: string, ids: Partial<Pick<CheckIssue, 'accountId' | 'holdingId' | 'recordIds'>> = {}): CheckIssue => ({
  key, kind, detail, accountId: ids.accountId ?? null, holdingId: ids.holdingId ?? null, recordIds: ids.recordIds ?? [],
});
const join = (parts: readonly (string | null | undefined)[]) => parts.filter((p): p is string => p !== null && p !== undefined).join(' · ');

/** 한투에서 불러온 분할 체결: 모두 체결번호가 있고 서로 다르면 중복이 아니다. */
function splitFills(group: readonly Record[]): boolean {
  const ids = group.map((r) => r.externalId);
  return ids.every((id) => id !== null) && new Set(ids).size === ids.length;
}

/** 점검 결과 (단계 → 종류 → 설명 글 → 키 순서). */
export function dataCheck(
  accounts: readonly InvestmentAccount[],
  holdings: readonly Holding[],
  records: readonly Record[],
  summary: PortfolioSummary,
  market: MarketSnapshot,
  today: string,
): CheckIssue[] {
  const accountName = new Map(accounts.map((a) => [a.id, a.name]));
  const holdingById = new Map(holdings.map((h) => [h.id, h]));
  const sorted = [...records].sort(compareRecords);
  const out: CheckIssue[] = [];

  for (const s of shortSells(records)) {
    const r = s.record;
    const h = r.holdingId === null ? undefined : holdingById.get(r.holdingId);
    const unit = quantityUnit(h?.assetType);
    out.push(issue(`SHORT_SELL:${r.id}`, 'SHORT_SELL',
      join([accountName.get(r.accountId), h?.name, `${checkDateText(r.date)} 매도 ${decimal(r.quantity)}${unit} (그때 보유 ${decimal(s.held)}${unit})`]),
      { accountId: r.accountId, holdingId: r.holdingId, recordIds: [r.id] }));
  }

  for (const a of summary.accounts) {
    if (a.brokerLinked) continue;
    if (a.cash.krw < -0.5) out.push(issue(`NEGATIVE_CASH:${a.account.id}:KRW`, 'NEGATIVE_CASH', `${a.account.name} · 원화 ${won(a.cash.krw)}`, { accountId: a.account.id }));
    if (a.cash.usd < -0.005) out.push(issue(`NEGATIVE_CASH:${a.account.id}:USD`, 'NEGATIVE_CASH', `${a.account.name} · 달러 ${usd(a.cash.usd)}`, { accountId: a.account.id }));
  }

  for (const r of sorted) {
    const flow = r.type === 'DEPOSIT' || r.type === 'WITHDRAW' || r.type === 'TRANSFER';
    if (flow && r.currency === 'USD' && r.amount > 0 && r.krwAmount === 0) {
      out.push(issue(`USD_FLOW_NO_KRW:${r.id}`, 'USD_FLOW_NO_KRW',
        join([accountName.get(r.accountId), `${checkDateText(r.date)} ${RECORD_TYPE_LABEL[r.type]} ${usd(r.amount)}`]),
        { accountId: r.accountId, recordIds: [r.id] }));
    }
    const reason = !accountName.has(r.accountId) ? '계좌가 없음'
      : r.holdingId !== null && !holdingById.has(r.holdingId) ? '종목이 없음'
      : r.type === 'TRANSFER' && r.toAccountId !== null && !accountName.has(r.toAccountId) ? '받는 계좌가 없음'
      : null;
    if (reason !== null) {
      out.push(issue(`ORPHAN_RECORD:${r.id}`, 'ORPHAN_RECORD',
        `${accountName.get(r.accountId) ?? '지운 계좌'} · ${checkDateText(r.date)} ${RECORD_TYPE_LABEL[r.type]} · ${reason}`,
        { accountId: accountName.has(r.accountId) ? r.accountId : null, recordIds: [r.id] }));
    }
  }

  const usdKrw = summary.usdKrw;
  for (const a of summary.accounts) {
    for (const v of a.holdings) {
      if (v.position.quantity <= 0) continue;
      const h = v.holding;
      const where = `${a.account.name} · ${h.name}`;
      const price = v.price;
      if (price === null) {
        out.push(issue(`NO_PRICE:${h.id}`, 'NO_PRICE', where, { accountId: a.account.id, holdingId: h.id }));
      } else if (price.date !== null) {
        const days = daysBetween(price.date, today);
        const limit = quotable(h) ? STALE_QUOTED_DAYS : STALE_MANUAL_DAYS;
        if (days > limit) {
          out.push(issue(`STALE_PRICE:${h.id}:${price.date}`, 'STALE_PRICE', `${where} · ${checkDateText(price.date)} 가격 (${days}일 전)`, { accountId: a.account.id, holdingId: h.id }));
        }
      }
      if (marketCurrency(h.market) === 'USD' && usdKrw !== null && krwHoldingView(v, records, usdKrw, (d) => tradeUsdKrw(market, d, today)) === null) {
        out.push(issue(`NO_TRADE_FX:${h.id}`, 'NO_TRADE_FX', where, { accountId: a.account.id, holdingId: h.id }));
      }
    }
  }
  if (summary.missingFx) out.push(issue('NO_FX', 'NO_FX', '달러 금액이 있는데 쓸 환율이 없습니다'));

  const groups = new Map<string, Record[]>();
  for (const r of sorted) {
    if (r.type === 'CASH_BALANCE') continue;
    const k = JSON.stringify([r.accountId, r.type, r.date, r.holdingId, r.toAccountId, r.currency, r.quantity, r.price, r.amount]);
    groups.set(k, [...(groups.get(k) ?? []), r]);
  }
  for (const group of groups.values()) {
    if (group.length < 2 || splitFills(group)) continue;
    const r = group[0];
    const h = r.holdingId === null ? undefined : holdingById.get(r.holdingId);
    const what = r.type === 'BUY' || r.type === 'SELL'
      ? `${h?.name ?? '종목'} ${decimal(r.quantity)}${quantityUnit(h?.assetType)} × ${amount(r.currency, r.price)}`
      : [h?.name, amount(r.currency, r.amount)].filter((p) => p !== undefined).join(' ');
    out.push(issue(`DUPLICATE:${group.map((x) => x.id).sort().join(',')}`, 'DUPLICATE',
      join([accountName.get(r.accountId), `${checkDateText(r.date)} ${RECORD_TYPE_LABEL[r.type]}`, what, `${group.length}건`]),
      { accountId: r.accountId, holdingId: r.holdingId, recordIds: group.map((x) => x.id) }));
  }

  const used = new Set(records.flatMap((r) => (r.holdingId === null ? [] : [r.holdingId])));
  for (const h of holdings) {
    if (used.has(h.id)) continue;
    out.push(issue(`EMPTY_HOLDING:${h.id}`, 'EMPTY_HOLDING', join([accountName.get(h.accountId), h.name]), { accountId: h.accountId, holdingId: h.id }));
  }

  // core 와 같은 순서 (문자열은 UTF-16 코드 순서로 비교)
  const cmp = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
  return out.sort((a, b) => KIND_ORDER.indexOf(a.kind) - KIND_ORDER.indexOf(b.kind) || cmp(a.detail, b.detail) || cmp(a.key, b.key));
}

/** 홈 알림 개수: 무시하지 않은 🔴·🟠 (⚪ 는 세지 않는다). */
export const checkAlertCount = (issues: readonly CheckIssue[], ignored: ReadonlySet<string>): number =>
  issues.filter((i) => CHECK_KINDS[i.kind].level !== 'REVIEW' && !ignored.has(i.key)).length;
