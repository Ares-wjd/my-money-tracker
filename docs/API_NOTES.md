# 외부 API 메모

기능 구현 시 참고할 외부 API 명세 요약. (원문 명세가 바뀌면 이 문서도 갱신)

## 한국수출입은행 현재환율 API

- 출처: 한국수출입은행 Open API "현재환율 API" 개발명세 (2026-04-28 수정본 기준)
- 요청 URL: `https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON`
  - 기존 도메인 `www.koreaexim.go.kr` 은 2026-04-30 부로 중단됨. 반드시 `oapi.` 도메인 사용.
- 예시: `...exchangeJSON?authkey=인증키&searchdate=20180102&data=AP01`
- 일일 호출 한도: **1,000회** (인증키 단위). 초과 시 `result: 4`, 데이터 미제공.
- 데이터: 일환율, **영업일 11시 전후** 갱신. 비영업일이거나 당일 11시 이전에 당일 데이터를 요청하면 null(빈 배열) 반환.

### 요청 변수
| 변수 | 타입 | 설명 |
| --- | --- | --- |
| `authkey` | String (필수) | 발급받은 인증키 |
| `searchdate` | String | 조회 날짜. `2015-01-01` 또는 `20150101`. 기본값 오늘 |
| `data` | String (필수) | `AP01` 환율, `AP02` 대출금리, `AP03` 국제금리 |

### 응답 (배열, 통화별 1개 객체)
| 필드 | 설명 |
| --- | --- |
| `result` | 1 성공, 2 DATA 코드 오류, 3 인증코드 오류, 4 일일 제한 초과 |
| `cur_unit` | 통화 코드 (예: `USD`, `JPY(100)`) |
| `cur_nm` | 국가/통화명 |
| `ttb` / `tts` | 전신환 받으실 때 / 보내실 때 |
| `deal_bas_r` | **매매기준율** ← 평가금 환산에 사용 |
| `bkpr` | 장부가격 |
| `yy_efee_r` / `ten_dd_efee_r` | 년환가료율 / 10일환가료율 |
| `kftc_deal_bas_r` / `kftc_bkpr` | 서울외국환중개 매매기준율 / 장부가격 |

- 숫자 필드도 문자열이며 천 단위 쉼표가 들어 있다 (예: `"1,393.5"`). 쉼표 제거 후 파싱.

### 앱 구현 시 주의
- 한 번 호출로 그날의 모든 통화가 오므로, **날짜당 1회**만 호출하고 결과(USD 매매기준율)를 영구 캐시한다. 과거 환율은 바뀌지 않는다.
- 빈 배열이면 비영업일/11시 이전으로 보고 **직전 영업일**로 거슬러 올라가 조회한다 (최대 10일).
- `result: 3`: 인증키 오류. 개인정보 보유기간(2년) 만료로 키가 파기됐을 수 있다.
  파기 전 등록 이메일로 '재동의' 안내가 오며, 재동의하면 2년 연장된다. 파기된 키는 재사용 불가 → 새로 발급.
  앱에서는 "환율 API 키가 만료되었거나 잘못되었습니다. 재발급 후 설정에서 다시 입력하세요" 로 안내한다.
- `result: 4`: 오늘 호출 한도 초과. 캐시된 값을 쓰고 다음 날 다시 시도.

## 한국투자증권 Open API (조회 전용)

- 출처: KIS Developers 문서·공식 예제(koreainvestment/open-trading-api) 기준. **실전 계정으로 실제 호출해 검증할 것.**
- 기본 URL: `https://openapi.koreainvestment.com:9443`
- 공통 헤더: `authorization: Bearer {토큰}`, `appkey`, `appsecret`, `tr_id`, `custtype: P`, 연속조회 시 `tr_cont: N`
  - 응답 헤더 `tr_cont` 가 `F`/`M` 이면 다음 페이지가 있다 (`CTX_AREA_FK/NK` 값을 그대로 넘긴다).
- 응답 `rt_cd` 가 `"0"` 이 아니면 오류. `msg1` 을 사용자에게 보여준다. `EGW00123` 은 토큰 만료.
- `EGW00201` 은 초당 거래건수 초과 (한도는 App Key 단위). 앱은 모든 호출을 한 줄로 세워 250ms 간격을 두고,
  이 오류가 나면 1.2초·2.4초·… 기다렸다가 최대 4번 다시 시도한다.
- **주문·정정·취소 API 는 사용하지 않는다.**

| 용도 | 경로 | tr_id | 주요 파라미터 → 사용하는 응답 |
| --- | --- | --- | --- |
| 접근 토큰 | `POST /oauth2/tokenP` | - | `grant_type=client_credentials, appkey, appsecret` → `access_token`, `expires_in` (하루 1회 발급 원칙, 기기에 캐시) |
| 국내 현재가 | `/uapi/domestic-stock/v1/quotations/inquire-price` | FHKST01010100 | `FID_COND_MRKT_DIV_CODE=J, FID_INPUT_ISCD` → `output.stck_prpr` |
| 국내 일봉 | `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice` | FHKST03010100 | `FID_INPUT_DATE_1/2, FID_PERIOD_DIV_CODE=D, FID_ORG_ADJ_PRC=0` → `output2[].stck_bsop_date, stck_clpr` (최대 100건) |
| 해외 현재가 | `/uapi/overseas-price/v1/quotations/price` | HHDFS00000300 | `EXCD(NAS/NYS/AMS), SYMB` → `output.last` |
| 해외 일봉 | `/uapi/overseas-price/v1/quotations/dailyprice` | HHDFS76240000 | `EXCD, SYMB, GUBN=0, BYMD, MODP=1` → `output2[].xymd, clos` (BYMD 이전 100건) |
| 국내 잔고 | `/uapi/domestic-stock/v1/trading/inquire-balance` | TTTC8434R | `CANO, ACNT_PRDT_CD, INQR_DVSN=02 ...` → `output1[].pdno, prdt_name, hldg_qty, pchs_avg_pric`, `output2[0].prvs_rcdl_excc_amt`(D+2 예수금) |
| 국내 체결 | `/uapi/domestic-stock/v1/trading/inquire-daily-ccld` | TTTC0081R (3개월 이내) / CTSC9215R (이전) | `INQR_STRT_DT, INQR_END_DT, CCLD_DVSN=01, EXCG_ID_DVSN_CD=ALL` → `output1[].ord_dt, odno, sll_buy_dvsn_cd(01 매도/02 매수), pdno, tot_ccld_qty, avg_prvs` |
| 해외 체결기준 현재잔고 | `/uapi/overseas-stock/v1/trading/inquire-present-balance` | CTRP6504R | `WCRC_FRCR_DVSN_CD=02, NATN_CD=840, TR_MKET_CD=00, INQR_DVSN_CD=00(전체: 일반+미니스탁)` → `output1[].pdno, prdt_name, ccld_qty_smtl1, avg_unpr3, ovrs_now_pric1, ovrs_excg_cd`, `output2[].crcy_cd, frcr_dncl_amt_2`(외화예수금) |
| (사용 안 함) 해외 잔고 | `/uapi/overseas-stock/v1/trading/inquire-balance` | TTTS3012R | **미니스탁(소수점)이 빠져 있어** 위 현재잔고 API 로 대체 |
| 해외 체결 | `/uapi/overseas-stock/v1/trading/inquire-ccnl` | TTTS3035R | `ORD_STRT_DT, ORD_END_DT, CCLD_NCCS_DVSN=01` → `output[].ord_dt, odno, sll_buy_dvsn_cd, pdno, ft_ccld_qty, ft_ccld_unpr3, ovrs_excg_cd` |

### 앱 구현 메모
- 체결 내역에는 수수료·세금이 따로 없어 0 으로 저장하고, 대신 원화 예수금을 한투 D+2 예수금에 맞추는 "예수금 조정" 으로 차이를 반영한다.
- 불러온 체결의 문서 ID 는 `kis_{주문일}_{주문번호}_{종목코드}` 로 정해 같은 체결이 두 번 저장되지 않게 한다.
- 미니스탁(소수점) 매매는 체결 내역 API 에 나오지 않는다. 불러올 때마다 잔고 수량과 비교해 차이를 오늘 날짜의
  "수량 맞춤" 매수(한투 평균단가에 맞춘 단가)·매도(현재가) 기록으로 채운다. 문서 ID `kis_adj_{날짜}_{종목ID}`.
- 달러 예수금도 현재잔고 API 의 외화예수금(`frcr_dncl_amt_2`)에 맞춘다.
