package com.mymoneytracker.app.data

import com.mymoneytracker.app.data.HttpJson.num
import com.mymoneytracker.app.data.HttpJson.objects
import com.mymoneytracker.app.data.HttpJson.str
import com.mymoneytracker.core.model.Market
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 종가 한 개. */
data class DailyClose(val date: LocalDate, val close: Double)

/** 현재가와 전일 종가 (오늘 수익률 계산용). */
data class Quote(val price: Double, val prevClose: Double?)

/** 한투 체결 내역 한 건. */
data class KisExecution(
    val date: LocalDate,
    val orderNo: String,
    val isBuy: Boolean,
    val code: String,
    val name: String,
    val quantity: Double,
    val price: Double,
    val market: Market,
)

/** 한투 잔고의 종목 한 개. */
data class KisHolding(
    val code: String,
    val name: String,
    val quantity: Double,
    val averagePrice: Double,
    val market: Market,
    val currentPrice: Double? = null,
)

data class KisOverseasBalance(
    /** 일반 해외주식 + 미니스탁(소수점) 보유 종목. */
    val holdings: List<KisHolding>,
    /** 달러 예수금. */
    val usdCash: Double?,
)

data class KisDomesticBalance(
    val holdings: List<KisHolding>,
    /** D+2 예수금 (결제까지 반영된 예수금). */
    val settledCash: Double?,
)

/**
 * 한국투자증권 Open API 클라이언트. **조회 API 만 사용한다** (주문·정정·취소 API 는 넣지 않는다).
 * 명세 요약은 docs/API_NOTES.md 참고.
 */
class KisClient(private val store: SecureStore) {

    private val tokenMutex = Mutex()
    private val callMutex = Mutex()

    // 초당 호출 한도 대응: 모든 호출을 한 줄로 세우고(callMutex, 순서대로), 호출 사이 간격을 둔다.
    // 한도에 걸리면 모든 호출을 잠시 멈추고 간격을 늘리며, 성공이 이어지면 다시 줄인다 (키마다 다른 허용 속도에 맞춤).
    private var callInterval = CALL_INTERVAL_MS
    private var lastCallAt = 0L
    private var pauseUntil = 0L
    private var successStreak = 0

    val hasCredentials: Boolean
        get() = !store.get(SecureStore.KIS_APP_KEY).isNullOrBlank() && !store.get(SecureStore.KIS_APP_SECRET).isNullOrBlank()

    /** 접근 토큰. 기기에 저장해 만료 1시간 전까지 재사용한다 (토큰 발급은 하루 1회가 원칙). */
    private suspend fun token(): String = tokenMutex.withLock {
        val cached = store.get(SecureStore.KIS_TOKEN)
        val expiresAt = store.get(SecureStore.KIS_TOKEN_EXPIRES_AT)?.toLongOrNull() ?: 0L
        if (!cached.isNullOrBlank() && System.currentTimeMillis() < expiresAt - 3_600_000) return@withLock cached

        val (appKey, appSecret) = credentials()
        val response = withContext(Dispatchers.IO) {
            HttpJson.post(
                "$BASE/oauth2/tokenP",
                JSONObject().put("grant_type", "client_credentials").put("appkey", appKey).put("appsecret", appSecret),
                emptyMap(),
            )
        }
        val json = runCatching { JSONObject(response.body) }.getOrNull()
        val token = json?.optString("access_token").orEmpty()
        if (json == null || response.code !in 200..299 || token.isBlank()) {
            val message = json?.optString("error_description")?.ifBlank { null }
                ?: json?.optString("msg1")?.ifBlank { null }
            throw ApiException(
                "한투 접근 토큰 발급 실패" + (message?.let { ": $it" } ?: " (HTTP ${response.code})") +
                    ". App Key·Secret 이 맞는지, KIS 서비스가 만료되지 않았는지 확인하세요.",
            )
        }
        val expiresIn = json.optLong("expires_in", 86_400L)
        store.put(SecureStore.KIS_TOKEN, token)
        store.put(SecureStore.KIS_TOKEN_EXPIRES_AT, (System.currentTimeMillis() + expiresIn * 1000).toString())
        token
    }

    fun clearToken() {
        store.put(SecureStore.KIS_TOKEN, null)
        store.put(SecureStore.KIS_TOKEN_EXPIRES_AT, null)
    }

    private fun credentials(): Pair<String, String> {
        val key = store.get(SecureStore.KIS_APP_KEY)
        val secret = store.get(SecureStore.KIS_APP_SECRET)
        if (key.isNullOrBlank() || secret.isNullOrBlank()) throw ApiException("설정에서 한국투자증권 App Key·Secret 을 입력하세요.")
        return key to secret
    }

    private data class KisResponse(val json: JSONObject, val hasMore: Boolean)

    /**
     * 조회 API 호출. rt_cd 가 0 이 아니면 한투가 보낸 메시지로 예외를 던진다.
     * 초당 호출 한도에 걸리면 모든 호출을 잠시 멈추고 간격을 늘린 뒤 최대 [MAX_RETRIES] 번 다시 시도한다.
     */
    private suspend fun call(path: String, trId: String, params: Map<String, String>, continued: Boolean = false): KisResponse {
        val (appKey, appSecret) = credentials()
        var attempt = 0
        while (true) {
            val accessToken = token()
            val result = callMutex.withLock {
                val wait = maxOf(lastCallAt + callInterval, pauseUntil) - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                val response = withContext(Dispatchers.IO) {
                    HttpJson.get(
                        BASE + path,
                        params,
                        mapOf(
                            "content-type" to "application/json; charset=utf-8",
                            "authorization" to "Bearer $accessToken",
                            "appkey" to appKey,
                            "appsecret" to appSecret,
                            "tr_id" to trId,
                            "tr_cont" to if (continued) "N" else "",
                            "custtype" to "P",
                        ),
                    )
                }
                lastCallAt = System.currentTimeMillis()
                val json = runCatching { JSONObject(response.body) }.getOrNull()
                    ?: throw ApiException("한투 응답을 읽지 못했습니다 (HTTP ${response.code}).")
                if (isRateLimited(json)) {
                    // 모든 호출을 잠시 멈추고 간격을 늘린다
                    pauseUntil = lastCallAt + RATE_LIMIT_PAUSE_MS
                    callInterval = minOf(callInterval * 2, MAX_CALL_INTERVAL_MS)
                    successStreak = 0
                } else if (++successStreak >= SPEED_UP_AFTER && callInterval > CALL_INTERVAL_MS) {
                    callInterval = maxOf(CALL_INTERVAL_MS, callInterval - SPEED_UP_STEP_MS)
                    successStreak = 0
                }
                json to response.headers["tr_cont"]
            }
            val (json, trCont) = result
            if (json.optString("rt_cd") == "0") return KisResponse(json, trCont in setOf("F", "M"))

            val msgCode = json.optString("msg_cd")
            val limited = isRateLimited(json)
            if (limited && attempt < MAX_RETRIES) {
                attempt++
                continue // 다음 호출은 멈춤 시간과 늘어난 간격만큼 기다린다
            }
            if (msgCode == "EGW00123") clearToken() // 토큰 만료
            val message = json.optString("msg1").ifBlank { "알 수 없는 오류" }
            throw ApiException(
                if (limited) RATE_LIMIT_MESSAGE else "한투 API 오류: $message",
            )
        }
    }

    // ---------------------------------------------------------------- 시세

    suspend fun domesticPrice(code: String): Double = domesticQuote(code).price

    /** 국내 현재가와 전일 종가(기준가). */
    suspend fun domesticQuote(code: String): Quote {
        val r = call(
            "/uapi/domestic-stock/v1/quotations/inquire-price",
            "FHKST01010100",
            mapOf("FID_COND_MRKT_DIV_CODE" to "J", "FID_INPUT_ISCD" to code),
        )
        val output = r.json.optJSONObject("output")
        val price = output?.num("stck_prpr") ?: throw ApiException("$code 현재가가 없습니다.")
        // 기준가(stck_sdpr) = 전일 종가. 없으면 현재가 − 전일 대비.
        val prevClose = output.num("stck_sdpr")?.takeIf { it > 0 }
            ?: output.num("prdy_vrss")?.let { price - it }?.takeIf { it > 0 }
        return Quote(price, prevClose)
    }

    /**
     * 국내 최근 일별 종가 (최근 30 영업일, 최신순). 기간별 조회·현재가 조회가 안 되는 종목(KRX 금현물 등)의 대신 쓰는 값.
     * 전일 종가는 두 번째 줄의 종가.
     */
    suspend fun domesticRecentDaily(code: String): List<DailyClose> {
        val r = call(
            "/uapi/domestic-stock/v1/quotations/inquire-daily-price",
            "FHKST01010400",
            mapOf(
                "FID_COND_MRKT_DIV_CODE" to "J",
                "FID_INPUT_ISCD" to code,
                "FID_PERIOD_DIV_CODE" to "D",
                "FID_ORG_ADJ_PRC" to "0",
            ),
        )
        return r.json.optJSONArray("output")?.objects().orEmpty().mapNotNull { row ->
            val date = parseYmd(row.str("stck_bsop_date")) ?: return@mapNotNull null
            val close = row.num("stck_clpr")?.takeIf { it > 0 } ?: return@mapNotNull null
            DailyClose(date, close)
        }.sortedByDescending { it.date }
    }

    /** 국내 일별 종가 (수정주가). 한 번에 최대 100 영업일. */
    suspend fun domesticDaily(code: String, from: LocalDate, to: LocalDate): List<DailyClose> {
        val r = call(
            "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice",
            "FHKST03010100",
            mapOf(
                "FID_COND_MRKT_DIV_CODE" to "J",
                "FID_INPUT_ISCD" to code,
                "FID_INPUT_DATE_1" to from.format(YMD),
                "FID_INPUT_DATE_2" to to.format(YMD),
                "FID_PERIOD_DIV_CODE" to "D",
                "FID_ORG_ADJ_PRC" to "0",
            ),
        )
        return r.json.optJSONArray("output2")?.objects().orEmpty().mapNotNull { row ->
            val date = parseYmd(row.str("stck_bsop_date")) ?: return@mapNotNull null
            val close = row.num("stck_clpr") ?: return@mapNotNull null
            DailyClose(date, close)
        }
    }

    /** 해외 현재가와 전일 종가(base). */
    suspend fun overseasQuote(market: Market, symbol: String): Quote {
        val r = call(
            "/uapi/overseas-price/v1/quotations/price",
            "HHDFS00000300",
            mapOf("AUTH" to "", "EXCD" to quoteExchange(market), "SYMB" to symbol),
        )
        val output = r.json.optJSONObject("output")
        val price = output?.num("last")?.takeIf { it > 0 } ?: throw ApiException("$symbol 현재가가 없습니다.")
        return Quote(price, output.num("base")?.takeIf { it > 0 })
    }

    /** 해외 일별 종가. [until] 이전 최대 100 영업일. */
    suspend fun overseasDaily(market: Market, symbol: String, until: LocalDate): List<DailyClose> {
        val r = call(
            "/uapi/overseas-price/v1/quotations/dailyprice",
            "HHDFS76240000",
            mapOf(
                "AUTH" to "",
                "EXCD" to quoteExchange(market),
                "SYMB" to symbol,
                "GUBN" to "0",
                "BYMD" to until.format(YMD),
                "MODP" to "1",
            ),
        )
        return r.json.optJSONArray("output2")?.objects().orEmpty().mapNotNull { row ->
            val date = parseYmd(row.str("xymd")) ?: return@mapNotNull null
            val close = row.num("clos")?.takeIf { it > 0 } ?: return@mapNotNull null
            DailyClose(date, close)
        }
    }

    /** 원/달러 일별 시세 결과: 현재가·전일 종가와 [from] ~ [until] 일별 종가 (최대 100개). */
    data class FxDaily(val current: Double?, val prevClose: Double?, val closes: List<DailyClose>)

    /** 원/달러 일별 시세 (해외 지수·환율 기간별 시세, 조회 전용). 환율 소스 확인용. */
    suspend fun usdKrwDaily(from: LocalDate, until: LocalDate): FxDaily {
        val r = call(
            "/uapi/overseas-price/v1/quotations/inquire-daily-chartprice",
            "FHKST03030100",
            mapOf(
                "FID_COND_MRKT_DIV_CODE" to "X",
                "FID_INPUT_ISCD" to "FX@KRW",
                "FID_INPUT_DATE_1" to from.format(YMD),
                "FID_INPUT_DATE_2" to until.format(YMD),
                "FID_PERIOD_DIV_CODE" to "D",
            ),
        )
        val output = r.json.optJSONObject("output1")
        val closes = r.json.optJSONArray("output2")?.objects().orEmpty().mapNotNull { row ->
            val date = parseYmd(row.str("stck_bsop_date")) ?: return@mapNotNull null
            val close = row.num("ovrs_nmix_prpr")?.takeIf { it > 0 } ?: return@mapNotNull null
            DailyClose(date, close)
        }
        return FxDaily(output?.num("ovrs_nmix_prpr")?.takeIf { it > 0 }, output?.num("ovrs_nmix_prdy_clpr")?.takeIf { it > 0 }, closes)
    }

    // ---------------------------------------------------------------- 계좌 조회 (M5)

    private fun account(): Pair<String, String> {
        val cano = store.get(SecureStore.KIS_ACCOUNT_NO)
        if (cano.isNullOrBlank()) throw ApiException("설정에서 연결 계좌번호를 입력하세요.")
        return cano to (store.get(SecureStore.KIS_ACCOUNT_PRODUCT)?.ifBlank { null } ?: "01")
    }

    suspend fun domesticBalance(): KisDomesticBalance {
        val (cano, product) = account()
        val holdings = mutableListOf<KisHolding>()
        var settledCash: Double? = null
        var fk = ""
        var nk = ""
        var continued = false
        repeat(MAX_PAGES) {
            val r = call(
                "/uapi/domestic-stock/v1/trading/inquire-balance",
                "TTTC8434R",
                mapOf(
                    "CANO" to cano, "ACNT_PRDT_CD" to product, "AFHR_FLPR_YN" to "N", "OFL_YN" to "",
                    "INQR_DVSN" to "02", "UNPR_DVSN" to "01", "FUND_STTL_ICLD_YN" to "N",
                    "FNCG_AMT_AUTO_RDPT_YN" to "N", "PRCS_DVSN" to "00",
                    "CTX_AREA_FK100" to fk, "CTX_AREA_NK100" to nk,
                ),
                continued,
            )
            r.json.optJSONArray("output1")?.objects().orEmpty().forEach { row ->
                val quantity = row.num("hldg_qty") ?: 0.0
                if (quantity > 0) {
                    holdings += KisHolding(
                        row.str("pdno"), row.str("prdt_name"), quantity, row.num("pchs_avg_pric") ?: 0.0,
                        Market.KR, row.num("prpr"),
                    )
                }
            }
            r.json.optJSONArray("output2")?.optJSONObject(0)?.let { settledCash = it.num("prvs_rcdl_excc_amt") }
            fk = r.json.str("ctx_area_fk100")
            nk = r.json.str("ctx_area_nk100")
            if (!r.hasMore || nk.isBlank()) return KisDomesticBalance(holdings, settledCash)
            continued = true
        }
        return KisDomesticBalance(holdings, settledCash)
    }

    /**
     * 해외 체결기준 현재잔고. 조회 구분 "00(전체)" 로 일반 해외주식과 미니스탁(소수점)을 함께 받는다.
     * (일반 해외 잔고 API 에는 미니스탁이 빠져 있다)
     */
    suspend fun overseasPresentBalance(): KisOverseasBalance {
        val (cano, product) = account()
        val r = call(
            "/uapi/overseas-stock/v1/trading/inquire-present-balance",
            "CTRP6504R",
            mapOf(
                "CANO" to cano, "ACNT_PRDT_CD" to product, "WCRC_FRCR_DVSN_CD" to "02",
                "NATN_CD" to "840", "TR_MKET_CD" to "00", "INQR_DVSN_CD" to "00",
            ),
        )
        val holdings = r.json.optJSONArray("output1")?.objects().orEmpty().mapNotNull { row ->
            val quantity = row.num("ccld_qty_smtl1")?.takeIf { it > 0 } ?: row.num("cblc_qty13")?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val code = row.str("pdno").ifBlank { return@mapNotNull null }
            KisHolding(
                code = code,
                name = row.str("prdt_name").ifBlank { code },
                quantity = quantity,
                averagePrice = row.num("avg_unpr3") ?: 0.0,
                market = tradingMarket(row.str("ovrs_excg_cd")),
                currentPrice = row.num("ovrs_now_pric1"),
            )
        }
            // 같은 종목이 일반·미니스탁으로 나뉘어 오면 합친다 (평균단가는 수량 가중 평균).
            .groupBy { it.code to it.market }
            .map { (_, rows) ->
                val total = rows.sumOf { it.quantity }
                rows.first().copy(
                    quantity = total,
                    averagePrice = if (total > 0) rows.sumOf { it.quantity * it.averagePrice } / total else 0.0,
                )
            }
        val usdCash = r.json.optJSONArray("output2")?.objects().orEmpty()
            .firstOrNull { it.str("crcy_cd") == "USD" }
            ?.num("frcr_dncl_amt_2")
        return KisOverseasBalance(holdings, usdCash)
    }

    /** 국내 체결 내역. 3개월 이내와 이전은 서로 다른 TR 을 쓰므로 달 단위로 나눠 조회한다. */
    suspend fun domesticExecutions(from: LocalDate, to: LocalDate): List<KisExecution> {
        val (cano, product) = account()
        val result = mutableListOf<KisExecution>()
        val recentLimit = LocalDate.now().minusMonths(3)
        for ((start, end) in monthWindows(from, to)) {
            val trId = if (start.isBefore(recentLimit)) "CTSC9215R" else "TTTC0081R"
            var fk = ""
            var nk = ""
            var continued = false
            for (page in 0 until MAX_PAGES) {
                val r = call(
                    "/uapi/domestic-stock/v1/trading/inquire-daily-ccld",
                    trId,
                    mapOf(
                        "CANO" to cano, "ACNT_PRDT_CD" to product,
                        "INQR_STRT_DT" to start.format(YMD), "INQR_END_DT" to end.format(YMD),
                        "SLL_BUY_DVSN_CD" to "00", "INQR_DVSN" to "00", "PDNO" to "", "CCLD_DVSN" to "01",
                        "ORD_GNO_BRNO" to "", "ODNO" to "", "INQR_DVSN_3" to "00", "INQR_DVSN_1" to "",
                        "EXCG_ID_DVSN_CD" to "ALL",
                        "CTX_AREA_FK100" to fk, "CTX_AREA_NK100" to nk,
                    ),
                    continued,
                )
                r.json.optJSONArray("output1")?.objects().orEmpty().forEach { row ->
                    val quantity = row.num("tot_ccld_qty") ?: 0.0
                    val date = parseYmd(row.str("ord_dt"))
                    if (quantity > 0 && date != null) {
                        val amount = row.num("tot_ccld_amt")
                        val price = row.num("avg_prvs")?.takeIf { it > 0 } ?: amount?.div(quantity) ?: 0.0
                        result += KisExecution(
                            date, row.str("odno"), row.str("sll_buy_dvsn_cd") == "02",
                            row.str("pdno"), row.str("prdt_name"), quantity, price, Market.KR,
                        )
                    }
                }
                fk = r.json.str("ctx_area_fk100")
                nk = r.json.str("ctx_area_nk100")
                if (!r.hasMore || nk.isBlank()) break
                continued = true
            }
        }
        return result
    }

    suspend fun overseasExecutions(from: LocalDate, to: LocalDate): List<KisExecution> {
        val (cano, product) = account()
        val result = mutableListOf<KisExecution>()
        for ((start, end) in monthWindows(from, to)) {
            var fk = ""
            var nk = ""
            var continued = false
            for (page in 0 until MAX_PAGES) {
                val r = call(
                    "/uapi/overseas-stock/v1/trading/inquire-ccnl",
                    "TTTS3035R",
                    mapOf(
                        "CANO" to cano, "ACNT_PRDT_CD" to product, "PDNO" to "",
                        "ORD_STRT_DT" to start.format(YMD), "ORD_END_DT" to end.format(YMD),
                        "SLL_BUY_DVSN" to "00", "CCLD_NCCS_DVSN" to "01", "OVRS_EXCG_CD" to "",
                        "SORT_SQN" to "DS", "ORD_DT" to "", "ORD_GNO_BRNO" to "", "ODNO" to "",
                        "CTX_AREA_NK200" to nk, "CTX_AREA_FK200" to fk,
                    ),
                    continued,
                )
                r.json.optJSONArray("output")?.objects().orEmpty().forEach { row ->
                    val quantity = row.num("ft_ccld_qty") ?: 0.0
                    val date = parseYmd(row.str("ord_dt"))
                    if (quantity > 0 && date != null) {
                        result += KisExecution(
                            date, row.str("odno"), row.str("sll_buy_dvsn_cd") == "02",
                            row.str("pdno"), row.str("prdt_name"), quantity,
                            row.num("ft_ccld_unpr3") ?: 0.0, tradingMarket(row.str("ovrs_excg_cd")),
                        )
                    }
                }
                fk = r.json.str("ctx_area_fk200")
                nk = r.json.str("ctx_area_nk200")
                if (!r.hasMore || nk.isBlank()) break
                continued = true
            }
        }
        return result
    }

    private fun monthWindows(from: LocalDate, to: LocalDate): List<Pair<LocalDate, LocalDate>> {
        val windows = mutableListOf<Pair<LocalDate, LocalDate>>()
        var start = from
        while (!start.isAfter(to)) {
            val end = minOf(start.withDayOfMonth(start.lengthOfMonth()), to)
            windows += start to end
            start = end.plusDays(1)
        }
        return windows
    }

    companion object {
        private const val BASE = "https://openapi.koreainvestment.com:9443"
        /** 호출 간격. 실전 한도(초당 20건)보다 넉넉하게 초당 약 4건. */
        private const val CALL_INTERVAL_MS = 250L
        private const val RATE_LIMIT_CODE = "EGW00201"

        /** 초당 호출 한도 오류인지 (코드가 여러 가지라 문구도 본다: "초당 거래건수를 초과"). */
        private fun isRateLimited(json: JSONObject): Boolean =
            json.optString("msg_cd") == RATE_LIMIT_CODE || json.optString("msg1").contains("초당")

        /** 한도 오류 문구 (일시적인 문제라 설정을 고칠 필요가 없다는 뜻이 드러나게). */
        const val RATE_LIMIT_MESSAGE = "한투 API 호출이 몰려 잠시 후 다시 받습니다 (초당 호출 한도)."
        private const val RATE_LIMIT_PAUSE_MS = 1_000L
        private const val MAX_CALL_INTERVAL_MS = 1_000L
        private const val SPEED_UP_AFTER = 20
        private const val SPEED_UP_STEP_MS = 50L
        private const val MAX_RETRIES = 4
        private const val MAX_PAGES = 20
        private val YMD: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        fun parseYmd(text: String): LocalDate? = runCatching { LocalDate.parse(text, YMD) }.getOrNull()

        /** 시세 조회용 거래소 코드. */
        fun quoteExchange(market: Market): String = when (market) {
            Market.NYSE -> "NYS"
            Market.AMEX -> "AMS"
            else -> "NAS"
        }

        /** 계좌 조회 응답의 거래소 코드 → 시장. */
        fun tradingMarket(code: String): Market = when (code.uppercase()) {
            "NYSE", "NYS" -> Market.NYSE
            "AMEX", "AMS" -> Market.AMEX
            "NASD", "NAS", "NASDAQ" -> Market.NASDAQ
            else -> Market.US_OTHER
        }
    }
}
