import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Modal } from '../components/form';
import { Card } from '../components/ui';
import { CHECK_KINDS, CHECK_LEVELS, CHECK_LEVEL_LABEL, type CheckIssue, type CheckLevel } from '../core/dataCheck';
import { useAppData, useData } from '../data/DataContext';
import { setCheckIgnored } from '../data/writes';
import { RecordForm } from '../forms/RecordForm';
import type { Record } from '../model';

const LEVEL_ICON: { [K in CheckLevel]: string } = { ERROR: '🔴', MISSING: '🟠', REVIEW: '⚪' };

/** 데이터 점검: 계산이 틀어지거나 숫자가 비었을 수 있는 곳. 누르면 고칠 곳으로, 무시한 항목은 흐리게 남는다. */
export function CheckPage() {
  const { uid } = useData();
  const data = useAppData();
  const navigate = useNavigate();
  const [record, setRecord] = useState<Record | null>(null);
  const [confirming, setConfirming] = useState<CheckIssue | null>(null);
  const [error, setError] = useState<string | null>(null);

  const open = (issue: CheckIssue, recordId?: string) => {
    switch (CHECK_KINDS[issue.kind].target) {
      case 'RECORD': {
        const r = data.records.find((x) => x.id === (recordId ?? issue.recordIds[0]));
        if (r) setRecord(r);
        break;
      }
      case 'ACCOUNT':
        if (issue.accountId) navigate(`/accounts/${issue.accountId}`);
        break;
      case 'HOLDING':
        if (issue.holdingId) navigate(`/holdings/${issue.holdingId}`);
        break;
      case 'SETTINGS':
        navigate('/settings');
        break;
    }
  };

  const setIgnored = async (issue: CheckIssue, ignored: boolean) => {
    setError(null);
    try {
      await setCheckIgnored(uid, issue.key, ignored);
      setConfirming(null);
    } catch (e) {
      setError(`저장 실패: ${(e as Error).message} (Firebase 콘솔에 새 보안 규칙을 게시했는지 확인하세요)`);
    }
  };

  return (
    <div className="stack">
      <h1 className="page-title">데이터 점검</h1>
      <p className="muted small">
        계산이 틀어지거나 숫자가 비었을 수 있는 곳입니다. 줄을 누르면 고칠 곳으로 갑니다.
        문제가 없는 경우라면 [무시]하세요. 무시한 항목은 흐리게 남고 [다시 보기]로 되돌릴 수 있습니다.
      </p>
      {error && !confirming && <p className="error">{error}</p>}

      {data.checks.length === 0 && (
        <Card>
          <p>점검할 것이 없습니다.</p>
          <Link to="/">홈으로</Link>
        </Card>
      )}

      {CHECK_LEVELS.map((level) => {
        const items = data.checks.filter((i) => CHECK_KINDS[i.kind].level === level);
        if (items.length === 0) return null;
        const ignored = items.filter((i) => data.ignoredChecks.has(i.key)).length;
        return (
          <Card key={level} title={`${LEVEL_ICON[level]} ${CHECK_LEVEL_LABEL[level]} ${items.length - ignored}건` + (ignored > 0 ? ` · 무시 ${ignored}건` : '')}>
            {items.map((issue) => {
              const isIgnored = data.ignoredChecks.has(issue.key);
              return (
                <div key={issue.key} className={isIgnored ? 'dc-item ignored' : 'dc-item'}>
                  <div className="dc-row">
                    <button className="dc-main" onClick={() => open(issue)}>
                      <span className="list-title">{CHECK_KINDS[issue.kind].title}{isIgnored && <span className="dc-tag">무시함</span>}</span>
                      <span className="muted small">{issue.detail}</span>
                    </button>
                    <button className="dc-ignore" onClick={() => (isIgnored ? setIgnored(issue, false) : setConfirming(issue))}>
                      {isIgnored ? '다시 보기' : '무시'}
                    </button>
                  </div>
                  {issue.recordIds.length > 1 && (
                    <div className="dc-links">
                      {issue.recordIds.map((id, n) => (
                        <button key={id} className="dc-link" onClick={() => open(issue, id)}>기록 {n + 1} 열기</button>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </Card>
        );
      })}

      {confirming && (
        <Modal title="이 항목을 무시할까요?" onClose={() => { setConfirming(null); setError(null); }}>
          <p><strong>{CHECK_KINDS[confirming.kind].title}</strong> · {confirming.detail}</p>
          <p className="warning">{CHECK_KINDS[confirming.kind].ignoreEffect}</p>
          <p className="muted small">무시한 항목은 이 화면에 흐리게 남고 [다시 보기]로 되돌릴 수 있습니다. 홈 알림 개수에서는 빠집니다.</p>
          {error && <p className="error">{error}</p>}
          <div className="button-row">
            <button onClick={() => { setConfirming(null); setError(null); }}>취소</button>
            <button className="danger" onClick={() => setIgnored(confirming, true)}>무시</button>
          </div>
        </Modal>
      )}
      {record && <RecordForm accountId={record.accountId} type={record.type} existing={record} onClose={() => setRecord(null)} />}
    </div>
  );
}
