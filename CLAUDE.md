# 작업 지침

## 대화
- 항상 **한국어**로 답한다.
- 사용자가 직접 해야 하는 일(콘솔 설정 등)은 하나씩 단계별로 안내한다.

## 진행 방식
- 기능·코드 작업은 **계획을 먼저 보여주고, 승인받은 뒤** 진행한다.
- 기능을 바꿀 때는 명세서 `docs/FEATURES.md` 를 먼저 고친 뒤 구현한다.
- push 후에는 GitHub Actions 결과를 확인하고, 실패하면 고쳐서 다시 push 한다.

## 보안
- 공개 저장소다. 서명 키, google-services.json, API 키, 계좌번호 등 비밀 값은 저장소에 넣지 않는다.
- 한국투자증권 API 는 조회 전용만 쓴다 (주문·정정·취소 API 는 넣지 않는다).

## 구성
- 안드로이드 앱: `app/` (UI·데이터), `core/` (순수 Kotlin 계산 로직 + 테스트)
- PC 웹: `web/` (React + TypeScript + Vite, GitHub Pages 배포)
- Firestore 보안 규칙: `firestore.rules` (게시는 사용자가 Firebase 콘솔에서 직접)
