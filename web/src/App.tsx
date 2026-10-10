import { HashRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth';
import { Layout } from './components/Layout';
import { NotFound } from './components/ui';
import { DataProvider } from './data/DataContext';
import { firebase } from './firebase';
import { AccountDetailPage } from './pages/AccountDetailPage';
import { AccountsPage } from './pages/AccountsPage';
import { GoalsPage } from './pages/GoalsPage';
import { HoldingDetailPage } from './pages/HoldingDetailPage';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';

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
    <DataProvider uid={user.uid}>
      <HashRouter>
        <Routes>
          <Route element={<Layout user={user} />}>
            <Route index element={<HomePage />} />
            <Route path="accounts" element={<AccountsPage />} />
            <Route path="accounts/:accountId" element={<AccountDetailPage />} />
            <Route path="holdings/:holdingId" element={<HoldingDetailPage />} />
            <Route path="goals" element={<GoalsPage />} />
            <Route path="*" element={<NotFound what="페이지" />} />
          </Route>
        </Routes>
      </HashRouter>
    </DataProvider>
  );
}

export function App() {
  return (
    <AuthProvider>
      <Gate />
    </AuthProvider>
  );
}
