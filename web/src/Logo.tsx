/** 앱 로고 (B · 계단 막대). 폰 앱의 AppLogo 와 같은 도형. 다크 모드에서는 바탕을 밝혀 배경과 구분한다. */
export function Logo({ size }: { size: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 108 108" role="img" aria-label="Money Tracker 로고">
      <rect width="108" height="108" rx="28" style={{ fill: 'var(--logo-bg, #16201C)' }} />
      <rect x="27" y="58" width="14" height="24" rx="5" fill="#FFFFFF" />
      <rect x="47" y="44" width="14" height="38" rx="5" fill="#FFFFFF" />
      <rect x="67" y="28" width="14" height="54" rx="5" fill="#34B38A" />
      <circle cx="74" cy="18" r="5" fill="#F2B544" />
    </svg>
  );
}
