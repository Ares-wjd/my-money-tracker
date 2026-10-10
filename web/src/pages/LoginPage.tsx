import { useState } from 'react';
import { useAuth } from '../auth';
import { Logo } from '../Logo';

export function LoginPage() {
  const { signIn } = useAuth();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onSignIn = async () => {
    setBusy(true);
    setError(null);
    try {
      await signIn();
    } catch (e) {
      const code = (e as { code?: string }).code ?? '';
      if (code !== 'auth/popup-closed-by-user' && code !== 'auth/cancelled-popup-request') {
        setError(code === 'auth/unauthorized-domain'
          ? '이 주소가 Firebase 로그인 허용 도메인에 없습니다. Firebase 콘솔 → Authentication → 설정 → 승인된 도메인에 추가하세요.'
          : `로그인 실패: ${code || (e as Error).message}`);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="center">
      <Logo size={88} />
      <h1>Money Tracker</h1>
      <p className="muted">폰 앱과 같은 구글 계정으로 로그인하세요.</p>
      <button className="primary" onClick={onSignIn} disabled={busy}>
        {busy ? '로그인 중…' : 'Google 계정으로 로그인'}
      </button>
      {error && <p className="error">{error}</p>}
    </main>
  );
}
