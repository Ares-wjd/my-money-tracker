import { useState } from 'react';
import { FormActions, NumberField } from '../components/form';
import { Card, Row } from '../components/ui';
import { formatDate } from '../core/dates';
import { decimal, parseDecimal } from '../core/format';
import { useAppData, useData } from '../data/DataContext';
import { saveManualUsdKrw } from '../data/writes';
import { ThemeSwitch } from '../theme';

export function SettingsPage() {
  const { uid } = useData();
  const data = useAppData();
  const [rateText, setRateText] = useState(data.settings.manualUsdKrw === null ? '' : String(data.settings.manualUsdKrw));
  const [saved, setSaved] = useState(false);
  const rate = parseDecimal(rateText);
  const problem = rateText.trim() !== '' && (rate === null || rate <= 0) ? '환율을 확인하세요.' : null;

  return (
    <div className="stack">
      <h1 className="page-title">설정</h1>
      <Card title="직접 입력 환율">
        <p className="muted small">폰 앱이 저장한 환율(수출입은행)이 없을 때 계산에 쓰는 원/달러 환율입니다. 비우고 저장하면 지웁니다.</p>
        <Row
          label="지금 계산에 쓰는 환율"
          value={data.usdKrw === null ? '없음' : `${decimal(data.usdKrw, 2)}원` + (data.usdKrwDate ? ` (${formatDate(data.usdKrwDate)}, 자동)` : ' (직접 입력)')}
        />
        <NumberField label="직접 입력 환율 (원/달러)" value={rateText} onChange={(v) => { setRateText(v); setSaved(false); }} preview={(n) => `1달러 = ${decimal(n, 2)}원`} />
        <FormActions problem={problem} onSave={() => saveManualUsdKrw(uid, rateText.trim() === '' ? null : rate)} onDone={() => setSaved(true)} />
        {saved && <p className="muted small">저장했습니다.</p>}
      </Card>
      <Card title="화면 모드">
        <p className="muted small">자동은 PC 설정(밝게/어둡게)을 따릅니다. 고른 값은 이 브라우저에 기억합니다.</p>
        <ThemeSwitch />
      </Card>
      <Card title="폰 앱에서만 하는 것">
        <p className="muted small">한국투자증권 연결·불러오기, API 키 관리, 시세·환율 자동 조회, 앱 업데이트, 탈퇴는 폰 앱에서 합니다 (API 키는 폰에만 저장).</p>
      </Card>
    </div>
  );
}
