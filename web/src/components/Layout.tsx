import type { User } from 'firebase/auth';
import type { ReactNode } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth';
import { useData } from '../data/DataContext';
import { Logo } from '../Logo';
import { ThemeSwitch } from '../theme';
import { AccountIcon, GoalIcon, HomeIcon, SettingsIcon } from './icons';

const NAV: { to: string; label: string; end: boolean; icon: ReactNode }[] = [
  { to: '/', label: '홈', end: true, icon: <HomeIcon /> },
  { to: '/accounts', label: '계좌', end: false, icon: <AccountIcon /> },
  { to: '/goals', label: '목표', end: false, icon: <GoalIcon /> },
  { to: '/settings', label: '설정', end: false, icon: <SettingsIcon /> },
];

export function Layout({ user }: { user: User }) {
  const { signOut } = useAuth();
  const { data, errors } = useData();
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <Logo size={32} />
          <strong>Money Tracker</strong>
        </div>
        <nav aria-label="메뉴">
          {NAV.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => (isActive ? 'nav on' : 'nav')}>
              {item.icon}
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">
          <ThemeSwitch />
          <span className="muted small email">{user.email}</span>
          <button onClick={() => signOut()}>로그아웃</button>
        </div>
      </aside>
      <main className="content">
        <div className="content-inner">
          {errors.map((e) => (
            <p key={e} className="error">{e}</p>
          ))}
          {data === null ? <LoadingCards /> : <Outlet />}
        </div>
      </main>
    </div>
  );
}

/** 데이터를 받는 동안 보여주는 빈 카드. */
function LoadingCards() {
  return (
    <div className="stack" aria-busy="true" aria-label="데이터를 불러오는 중">
      <div className="card skeleton" style={{ height: 180 }} />
      <div className="card skeleton" style={{ height: 120 }} />
      <div className="card skeleton" style={{ height: 240 }} />
    </div>
  );
}
