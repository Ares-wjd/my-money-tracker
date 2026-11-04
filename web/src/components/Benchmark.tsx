// 지수 비교 (v2.11, 폰 BenchmarkViews 와 같은 화면): 그래프 아래 지수 칩과 "S&P500에 넣었다면 …" 줄.
import { useState } from 'react';
import { BENCHMARK_INDICES, BENCHMARK_LABEL, simulate, todayDiff, valuesAt, type BenchmarkIndex } from '../core/benchmark';
import { shortWon, won } from '../core/format';
import type { FlowPoint } from '../core/periodReturns';
import type { MarketSnapshot } from '../model';
import { profitClass } from './ui';

export interface BenchmarkLine {
  index: BenchmarkIndex;
  values: (number | null)[];
  today: { valueKrw: number; diffKrw: number } | null;
}

export const signedShort = (v: number) => (v > 0.5 ? '+' : '') + shortWon(v);

/** 고른 지수들의 선 (지수 시세가 없는 지수는 빠진다). */
export function benchmarkLines(
  series: readonly FlowPoint[] | null,
  dates: readonly string[],
  chosen: ReadonlySet<BenchmarkIndex>,
  snapshot: MarketSnapshot,
  today: string,
): BenchmarkLine[] {
  if (!series || series.length === 0 || dates.length < 2) return [];
  return BENCHMARK_INDICES.filter((i) => chosen.has(i)).flatMap((index) => {
    const sim = simulate(series, index, snapshot, today);
    return sim ? [{ index, values: valuesAt(sim, dates), today: todayDiff(series, sim) }] : [];
  });
}

/** 기기에 기억하는 값 (브라우저 저장소를 못 쓰면 기본값). */
function readStored(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
function writeStored(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // 저장 못 해도 화면은 그대로
  }
}
const isIndex = (v: string): v is BenchmarkIndex => (BENCHMARK_INDICES as readonly string[]).includes(v);

/** 홈 그래프에 겹쳐 볼 지수 (기본 없음, 기기에 기억). */
export function useChartBenchmarks(): [Set<BenchmarkIndex>, (index: BenchmarkIndex) => void] {
  const [chosen, setChosen] = useState<Set<BenchmarkIndex>>(() => new Set((readStored('chartBenchmarks') ?? '').split(',').filter(isIndex)));
  const toggle = (index: BenchmarkIndex) => {
    const next = new Set(chosen);
    if (next.has(index)) next.delete(index);
    else next.add(index);
    setChosen(next);
    writeStored('chartBenchmarks', [...next].join(','));
  };
  return [chosen, toggle];
}

/** 기간별 수익 월별 막대 위 지수 (기본 S&P500, null = 끄기). */
export function usePeriodBenchmark(): [BenchmarkIndex | null, (index: BenchmarkIndex | null) => void] {
  const [index, setIndex] = useState<BenchmarkIndex | null>(() => {
    const v = readStored('periodBenchmark');
    return v === null ? 'SPX' : isIndex(v) ? v : null;
  });
  return [
    index,
    (i) => {
      setIndex(i);
      writeStored('periodBenchmark', i ?? '');
    },
  ];
}

/** 칩 안의 선 모양 견본. */
export function DashSample({ index }: { index: BenchmarkIndex }) {
  return (
    <svg className="dash" width="16" height="6" aria-hidden="true">
      <line x1="1" y1="3" x2="15" y2="3" className={`line-${index}`} />
    </svg>
  );
}

/** 그래프 아래 "지수와 비교": 칩, 오늘 기준 지수마다 한 줄. */
export function BenchmarkSection({
  chosen,
  lines,
  computing,
  onToggle,
}: {
  chosen: ReadonlySet<BenchmarkIndex>;
  lines: readonly BenchmarkLine[];
  computing: boolean;
  onToggle: (index: BenchmarkIndex) => void;
}) {
  const missing = [...chosen].some((i) => !lines.some((l) => l.index === i));
  return (
    <div className="bench">
      <span className="small"><strong>지수와 비교</strong> <span className="muted">· 같은 날 같은 돈을 넣었다면</span></span>
      <div className="choices" role="group" aria-label="비교할 지수">
        {BENCHMARK_INDICES.map((i) => (
          <button key={i} aria-pressed={chosen.has(i)} className={chosen.has(i) ? 'choice on' : 'choice'} onClick={() => onToggle(i)}>
            <DashSample index={i} />
            {BENCHMARK_LABEL[i]}
          </button>
        ))}
      </div>
      {BENCHMARK_INDICES.filter((i) => chosen.has(i)).map((i) => {
        const t = lines.find((l) => l.index === i)?.today ?? null;
        return (
          <div key={i} className="bench-row">
            <span className="muted">{BENCHMARK_LABEL[i]}에 넣었다면</span>
            {t ? (
              <span>
                <span className="strong">{won(t.valueKrw)}</span>{' '}
                <span className={`strong ${profitClass(t.diffKrw)}`}>내가 {signedShort(t.diffKrw)}</span>
              </span>
            ) : (
              <span className="muted small">{computing ? '계산 중' : '지수 시세 없음'}</span>
            )}
          </div>
        );
      })}
      {chosen.size > 0 && (
        <span className="muted small">
          {missing && !computing ? '지수 시세는 한투 연결이 있는 폰 앱이 받아 둡니다 · ' : ''}S&amp;P500·나스닥은 그날 환율로 원화 환산 · 지수는 배당 제외
        </span>
      )}
    </div>
  );
}
