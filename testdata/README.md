# 공통 테스트 케이스

폰(core, Kotlin)과 PC(web, TypeScript)가 **같은 입력에 같은 숫자**를 내는지 검사하는 케이스 파일.
core 테스트(`core/src/test/.../SharedCasesTest.kt`)와 웹 테스트(`web/src/core/sharedCases.test.ts`)가 이 폴더의 파일을 모두 읽어 검사한다.
여기 파일이 바뀌면 Android CI 와 Web 워크플로가 모두 돈다. 숫자는 소수점 오차 1e-6 까지 같으면 통과.

## portfolio/*.json — 계좌 계산

입력은 **Firestore 에 저장되는 문서 모양 그대로** (문서 id → 필드).

| 키 | 내용 |
|---|---|
| `name` | 시나리오 설명 |
| `today` | 오늘 날짜 (`YYYY-MM-DD`) |
| `manualUsdKrw` | 설정의 직접 입력 환율 (없으면 null) |
| `accounts` / `holdings` / `records` | `users/{uid}/accounts`·`holdings`·`records` 문서 |
| `market` | `users/{uid}/marketData` 문서 (`price_<키>`, `fx_USD`, `fx_USD_checked`) |
| `expect` | 기대 결과 (있는 항목만 검사) |

`expect` 항목:

| 키 | 검사 |
|---|---|
| `summary` | 전체 `investedKrw` · `valueKrw` · `profitKrw` · `returnRate` · `returnRateExDividends` · `dividendsKrw` |
| `accounts` | 계좌 id → 위 값 + `cashKrw` · `cashUsd` · `noRateReason`(`NO_DEPOSIT`/`BALANCED`/`NEGATIVE`/null) |
| `positions` | 종목 id → `quantity` · `averagePrice` · `realizedProfit` · `dividends` |
| `cumulativeInvested` | 계좌 id → { 기록 id → 그 기록까지 누적 투자금 } |
| `shortSells` | 계산되지 않는 매도 `[{ "id", "held" }]` (계산 순서대로) |
| `todayChange` | 오늘 변동 (`null` 이면 없음) `totalKrw` · `rate` · `domesticKrw` · `foreignKrw` · `fxKrw` · `fxFrom` · `fxTo` · `accounts`(계좌 id → 가격 변동, 시세 없음은 null) |
| `dataCheck` | 데이터 점검 `issues`(`[{ key, kind, detail }]`, 화면 순서대로) · `ignored`(무시한 키) · `alertCount`(홈 알림 개수) |

## period/*.json — 기간 수익률

| 키 | 내용 |
|---|---|
| `series` | 일별 `[날짜, 평가금, 투자금]` |
| `today` | 오늘 |
| `expect.ytd` | 올해 `profitKrw` · `rate` · `netFlowKrw` |
| `expect.long` | `annual`(1년 이상 연평균) · `period`(1년 미만 기간 수익률) · `days` |
| `expect.months` | `{ count, items: [{ from, profitKrw, rate }] }` (최근 count 달) |
| `expect.years` | `[{ from, profitKrw }]` |
| `expect.summaryText` | 홈 한 줄 글 |

## 케이스 추가하기

1. `portfolio/` 또는 `period/` 에 json 파일을 하나 더 만든다 (파일 이름 = 시나리오, 테스트가 폴더의 파일을 모두 읽는다).
2. 기대 결과는 손으로 계산해 적고, 계산 근거는 `name` 에 짧게 남긴다.
3. 폰 `./gradlew :core:test` 와 웹 `npm test` 둘 다 통과해야 한다. 한쪽만 틀리면 그쪽 계산이 다른 것이다.
