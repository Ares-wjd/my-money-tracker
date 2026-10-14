// core 의 ExchangeInput 과 같은 규칙: 원화 금액·달러 금액·환율 중 마지막으로 입력한 두 칸으로 나머지 한 칸을 계산한다.

export type ExchangeField = 'KRW' | 'USD' | 'RATE';
const FIELDS: readonly ExchangeField[] = ['KRW', 'USD', 'RATE'];

/** field 를 입력했을 때의 입력 순서 (가장 최근이 뒤, 칸마다 한 번). */
export const touchExchange = (edited: readonly ExchangeField[], field: ExchangeField): ExchangeField[] => [...edited.filter((f) => f !== field), field];

/** 자동 계산할 칸: 최근에 입력한 두 칸이 아닌 칸. 두 칸을 입력하기 전이면 null. */
export function exchangeAutoField(edited: readonly ExchangeField[]): ExchangeField | null {
  if (edited.length < 2) return null;
  const recent = edited.slice(-2);
  return FIELDS.find((f) => !recent.includes(f)) ?? null;
}

/** 자동 칸의 값: 환율 = 원화 ÷ 달러, 달러 = 원화 ÷ 환율 (센트 반올림), 원화 = 달러 × 환율 (원 반올림). 계산할 수 없으면 null. */
export function computeExchange(auto: ExchangeField, krw: number | null, usd: number | null, rate: number | null): number | null {
  if (auto === 'RATE') return krw !== null && krw > 0 && usd !== null && usd > 0 ? krw / usd : null;
  if (auto === 'USD') return krw !== null && krw > 0 && rate !== null && rate > 0 ? Math.round((krw / rate) * 100) / 100 : null;
  return usd !== null && usd > 0 && rate !== null && rate > 0 ? Math.round(usd * rate) : null;
}
