import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { DisplayCurrency } from '../core/portfolio';
import { decimal } from '../core/format';

/** 수익은 빨강, 손실은 파랑 (국내 증권 앱 관례). */
export function profitClass(value: number | null | undefined): string {
  if (value === null || value === undefined || value === 0) return '';
  return value > 0 ? 'up' : 'down';
}

export function Card({ title, action, children, className = '' }: { title?: ReactNode; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || action) && (
        <div className="card-head">
          {title && <h2>{title}</h2>}
          {action}
        </div>
      )}
      {children}
    </section>
  );
}

export function Stat({ label, value, valueClass = '' }: { label: string; value: ReactNode; valueClass?: string }) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <span className={`stat-value ${valueClass}`}>{value}</span>
    </div>
  );
}

export function Row({ label, value, valueClass = '' }: { label: ReactNode; value: ReactNode; valueClass?: string }) {
  return (
    <div className="row">
      <span className="muted">{label}</span>
      <span className={valueClass}>{value}</span>
    </div>
  );
}

export function ListLink(props: {
  to: string;
  title: ReactNode;
  subtitle?: ReactNode;
  value?: ReactNode;
  subValue?: ReactNode;
  subValueClass?: string;
  leading?: ReactNode;
}) {
  return (
    <Link to={props.to} className="list-row">
      {props.leading}
      <span className="list-main">
        <span className="list-title">{props.title}</span>
        {props.subtitle && <span className="list-sub">{props.subtitle}</span>}
      </span>
      <span className="list-end">
        {props.value !== undefined && <span className="list-value">{props.value}</span>}
        {props.subValue && <span className={`list-sub ${props.subValueClass ?? ''}`}>{props.subValue}</span>}
      </span>
    </Link>
  );
}

export function Warning({ children }: { children: ReactNode }) {
  return <p className="warning">⚠ {children}</p>;
}

export function Badge({ children, dark = false }: { children: ReactNode; dark?: boolean }) {
  return <span className={dark ? 'badge badge-dark' : 'badge'}>{children}</span>;
}

/** 종목 티커 배지 (코드가 없으면 이름 앞 글자). */
export function TickerBadge({ code, name }: { code: string; name: string }) {
  const text = (code.trim() || name.trim()).slice(0, 6).toUpperCase() || '?';
  return <Badge dark>{text}</Badge>;
}

/** 해외 종목 금액 보기 전환 (외화 / 원화). */
export function CurrencyToggle({ mode, usdKrw, onChange }: { mode: DisplayCurrency; usdKrw: number | null; onChange: (m: DisplayCurrency) => void }) {
  return (
    <div className="toggle-row">
      <div className="segmented" role="radiogroup" aria-label="해외 종목 금액 보기">
        {(['FOREIGN', 'KRW'] as const).map((m) => (
          <button key={m} role="radio" aria-checked={mode === m} className={mode === m ? 'on' : ''} onClick={() => onChange(m)}>
            {m === 'FOREIGN' ? '외화' : '원화'}
          </button>
        ))}
      </div>
      {mode === 'KRW' && (
        <span className="muted small">{usdKrw !== null ? `1달러 ${decimal(usdKrw, 2)}원으로 환산` : '환율이 없어 환산할 수 없습니다'}</span>
      )}
    </div>
  );
}

export function NotFound({ what }: { what: string }) {
  return (
    <Card>
      <p className="muted">{what}을(를) 찾을 수 없습니다. 삭제되었을 수 있습니다.</p>
      <Link to="/">홈으로</Link>
    </Card>
  );
}
