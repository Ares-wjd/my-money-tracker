// 폰 앱 하단 메뉴와 같은 선 아이콘.
const common = { width: 22, height: 22, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true } as const;

export const HomeIcon = () => (
  <svg {...common}><path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" /></svg>
);
export const AccountIcon = () => (
  <svg {...common}><rect x="3" y="6" width="18" height="13" rx="2" /><path d="M3 10h18" /></svg>
);
export const GoalIcon = () => (
  <svg {...common}><circle cx="12" cy="12" r="8" /><circle cx="12" cy="12" r="3" /></svg>
);
export const SettingsIcon = () => (
  <svg {...common}><circle cx="12" cy="12" r="3" /><path d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1" /></svg>
);
