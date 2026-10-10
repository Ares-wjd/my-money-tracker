import type { User } from 'firebase/auth';
import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth';
import { useData } from '../data/DataContext';
import { Logo } from '../Logo';

const NAV = [
  { to: '/', label: '홈', end: true },
  { to: '/accounts', label: '계좌', end: false },
  { to: '/goals', label: '목표', end: false },
  { to: '/settings', label: '설정', end: false },
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
        <nav>
          {NAV.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.end} className={({ isActive }) => (isActive ? 'nav on' : 'nav')}>
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">
          <span className="muted small">{user.email}</span>
          <button onClick={() => signOut()}>로그아웃</button>
        </div>
      </aside>
      <main className="content">
        {errors.map((e) => (
          <p key={e} className="error">{e}</p>
        ))}
        {data === null ? <p className="muted">데이터를 불러오는 중…</p> : <Outlet />}
      </main>
    </div>
  );
}
