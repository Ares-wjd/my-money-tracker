import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

/** 화면 모드 (v2.18): 밝게 / 어둡게 중 고른다 (브라우저에 기억). 고르기 전(auto)에는 PC 설정을 따른다. */
export type ThemeMode = 'auto' | 'light' | 'dark';
export const THEME_MODES: readonly ('light' | 'dark')[] = ['light', 'dark'];
export const THEME_LABEL: { [K in ThemeMode]: string } = { auto: '자동', light: '밝게', dark: '어둡게' };

/** 고르기 전이면 PC 설정에 따른 지금 모드. */
function systemDark(): boolean {
  try {
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  } catch {
    return false;
  }
}

const KEY = 'theme';

function readMode(): ThemeMode {
  try {
    const v = localStorage.getItem(KEY);
    return v === 'light' || v === 'dark' ? v : 'auto';
  } catch {
    return 'auto';
  }
}

/** index.html 의 시작 스크립트와 같은 규칙: 자동이면 data-theme 을 지워 CSS 가 PC 설정을 따르게 한다. */
function apply(mode: ThemeMode) {
  if (mode === 'auto') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', mode);
}

const ThemeContext = createContext<{ mode: ThemeMode; setMode: (m: ThemeMode) => void } | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<ThemeMode>(readMode);
  useEffect(() => apply(mode), [mode]);
  const setMode = (m: ThemeMode) => {
    setModeState(m);
    try {
      localStorage.setItem(KEY, m);
    } catch {
      // 저장할 수 없는 환경이면 이번 화면에서만 적용한다.
    }
  };
  return <ThemeContext.Provider value={{ mode, setMode }}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const value = useContext(ThemeContext);
  if (!value) throw new Error('ThemeProvider 안에서만 쓸 수 있습니다.');
  return value;
}

/** 밝게 / 어둡게 선택 버튼 (고르기 전에는 PC 설정에 맞는 쪽이 켜져 보인다). */
export function ThemeSwitch() {
  const { mode, setMode } = useTheme();
  const shown = mode === 'auto' ? (systemDark() ? 'dark' : 'light') : mode;
  return (
    <div className="segmented theme-switch" role="radiogroup" aria-label="화면 모드">
      {THEME_MODES.map((m) => (
        <button key={m} role="radio" aria-checked={shown === m} className={shown === m ? 'on' : ''} onClick={() => setMode(m)}>
          {THEME_LABEL[m]}
        </button>
      ))}
    </div>
  );
}
