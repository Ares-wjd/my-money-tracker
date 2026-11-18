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

## benchmark/*.json — 지수 비교 (v2.11)

| 키 | 내용 |
|---|---|
| `series` | 일별 `[날짜, 평가금, 투자금]` (period 와 같음) |
| `today` | 오늘 |
| `market` | `marketData` 문서 (`price_IDX_SPX` · `price_IDX_COMP` · `price_IDX_KOSPI`, `fx_USD`, `fx_USD_checked`) |
| `expect.indices` | 지수(`SPX`/`COMP`/`KOSPI`) → `values`(날짜마다 가상 평가금) · `ytd`(올해 수익률) · `todayDiff`(내 평가금 − 가상 평가금) · `covers`, 시세가 없으면 `null` |
| `expect.mineYtd` | 내 올해 수익률 |
| `expect.compareText` | 수익률 비교 한 줄 (SPX · COMP · KOSPI 순서로 비교) |

## dividends/*.json — 배당 (v2.13)

입력은 portfolio 와 같은 Firestore 문서 모양 (`market` 에 `dividends` 문서 = 한투 배당 일정 `{ schedule: { 시세 키: [{ record, pay, perShare, currency }] } }`).

| 키 | 검사 |
|---|---|
| `expect.received` | 받은 배당 `[날짜, 종목 id, 원화]` (날짜순) |
| `expect.planned` | 예정 배당 `[CONFIRMED/ESTIMATED, 달, 날짜(모르면 null), 종목 id, 원화]` (달 → 날짜순) |
| `expect.home` | 홈 카드 `thisYear` · `lastYearSamePeriod` · `nextMonth` · `nextMonthPlanned` · `lastYearNextMonth` · `months`(달 → [받음, 확정, 추정]) · `lastYearMonths` |
| `expect.years` | 연도별 `[해, 받음, 확정, 추정]` |
| `expect.yields` | 종목별 `[종목 id, 최근 12개월 원화, 현재가 기준, 내 평단 기준]` · `totalOnPrice` · `totalOnCost` |

## 케이스 추가하기

1. `portfolio/` · `period/` · `benchmark/` 등에 json 파일을 하나 더 만든다 (파일 이름 = 시나리오, 테스트가 폴더의 파일을 모두 읽는다).
2. 기대 결과는 손으로 계산해 적고, 계산 근거는 `name` 에 짧게 남긴다.
3. 폰 `./gradlew :core:test` 와 웹 `npm test` 둘 다 통과해야 한다. 한쪽만 틀리면 그쪽 계산이 다른 것이다.
