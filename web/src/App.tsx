import { HashRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth';
import { firebase } from './firebase';
import { LoginPage } from './pages/LoginPage';
import { StatusPage } from './pages/StatusPage';

function Gate() {
  const { user } = useAuth();
  if (!firebase) {
    return (
      <main className="center">
        <h1>Money Tracker</h1>
        <p className="error">Firebase 웹 설정이 없습니다. GitHub Actions 변수 FIREBASE_WEB_CONFIG 를 등록한 뒤 다시 배포하세요.</p>
      </main>
    );
  }
  if (user === undefined) return <main className="center muted">불러오는 중…</main>;
  if (user === null) return <LoginPage />;
  return (
    // GitHub Pages 는 경로 재작성을 지원하지 않아 #/ 주소를 쓴다.
    <HashRouter>
      <Routes>
        <Route path="*" element={<StatusPage user={user} />} />
      </Routes>
    </HashRouter>
  );
}

export function App() {
  return (
    <AuthProvider>
      <Gate />
    </AuthProvider>
  );
}
