// core 의 OverseasTax 와 같은 규칙: 해외 주식 양도소득 (기본공제 연 250만원) 추정.
// 원화 평균단가는 이동평균법(매수일 환율), 매도는 매도일 환율. 실제 신고는 결제일 환율·결제일 귀속이고 매수 수수료도 빼므로
// 차이가 날 수 있어 계산 한도는 공제보다 10만원 낮게 둔다.
import { marketCurrency, totalCost, type Holding, type Record } from '../model';
import { keyOf } from './allocation';
import { compareRecords } from './portfolio';

export const TAX_DEDUCTION_KRW = 2_500_000;
export const TAX_LIMIT_KRW = 2_400_000;

interface Running {
  quantity: number;
  averageKrw: number;
}

/** 종목 하나의 기록을 따라가며 원화 이동평균을 계산한다. 환율은 fxAt, 없으면 fallbackFx (추정). 둘 다 없으면 건너뛴다. */
function walk(
  holding: Holding,
  records: readonly Record[],
  fxAt: (date: string) => number | null,
  fallbackFx: number | null,
  onEstimate: () => void,
  onSell: (r: Record, gain: number) => void = () => {},
): Running {
  const run: Running = { quantity: 0, averageKrw: 0 };
  for (const r of records.filter((x) => x.holdingId === holding.id).sort(compareRecords)) {
    if (r.type !== 'BUY' && r.type !== 'SELL') continue;
    let fx = fxAt(r.date);
    if (fx === null && fallbackFx !== null) {
      fx = fallbackFx;
      onEstimate();
    }
    if (fx === null) continue;
    if (r.type === 'BUY') {
      const q = run.quantity + r.quantity;
      if (q > 0) run.averageKrw = (run.quantity * run.averageKrw + r.quantity * r.price * fx) / q;
      run.quantity = q;
    } else {
      const sold = Math.min(r.quantity, run.quantity);
      onSell(r, sold * (r.price * fx - run.averageKrw) - totalCost(r) * fx);
      run.quantity -= sold;
      if (run.quantity <= 1e-9) {
        run.quantity = 0;
        run.averageKrw = 0;
      }
    }
  }
  return run;
}

const isUsd = (h: Holding) => marketCurrency(h.market) === 'USD';

/** year 에 매도한 해외(달러) 종목의 원화 실현손익 합 (모든 계좌). estimated: 환율이 없는 날을 추정했는지. */
export function realizedInYear(
  holdings: readonly Holding[],
  records: readonly Record[],
  year: number,
  fxAt: (date: string) => number | null,
  fallbackFx: number | null,
): { realizedKrw: number; estimated: boolean } {
  let total = 0;
  let estimated = false;
  const prefix = `${year}-`;
  for (const h of holdings.filter(isUsd)) {
    walk(h, records, fxAt, fallbackFx, () => { estimated = true; }, (r, gain) => {
      if (r.date.startsWith(prefix)) total += gain;
    });
  }
  return { realizedKrw: total, estimated };
}

/** 종목 키 → 원화 평균단가 (1주, 같은 종목은 수량 가중 평균). 해외(달러) 종목만. */
export function averageCostKrwByKey(
  holdings: readonly Holding[],
  records: readonly Record[],
  fxAt: (date: string) => number | null,
  fallbackFx: number | null,
): Map<string, number> {
  const sums = new Map<string, { q: number; cost: number }>();
  for (const h of holdings.filter(isUsd)) {
    const run = walk(h, records, fxAt, fallbackFx, () => {});
    if (run.quantity <= 0) continue;
    const key = keyOf(h);
    const s = sums.get(key) ?? { q: 0, cost: 0 };
    sums.set(key, { q: s.q + run.quantity, cost: s.cost + run.quantity * run.averageKrw });
  }
  return new Map([...sums].map(([k, s]) => [k, s.cost / s.q]));
}
