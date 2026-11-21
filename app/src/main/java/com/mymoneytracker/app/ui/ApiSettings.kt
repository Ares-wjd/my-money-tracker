package com.mymoneytracker.app.ui

import com.mymoneytracker.app.data.ApiException
import com.mymoneytracker.app.data.EximClient
import com.mymoneytracker.app.data.KisClient
import com.mymoneytracker.app.data.KisTokenState
import com.mymoneytracker.app.data.SecureStore
import com.mymoneytracker.core.MoneyFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 이 기기에만 저장하는 한투·수출입은행 API 키와 한투 연결 계좌 정보. 결과 안내는 [showMessage] 로. */
class ApiSettings(
    private val store: SecureStore,
    private val kis: KisClient,
    private val exim: EximClient,
    private val scope: CoroutineScope,
    private val showMessage: (String?) -> Unit,
) {
    private val _status = MutableStateFlow(read())
    val status: StateFlow<ApiStatus> = _status.asStateFlow()

    private fun read(): ApiStatus = ApiStatus(
        kisKeyHint = store.get(SecureStore.KIS_APP_KEY)?.let(::hint),
        kisServiceExpiry = store.get(SecureStore.KIS_SERVICE_EXPIRY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        eximKeyHint = store.get(SecureStore.EXIM_KEY)?.let(::hint),
        linkedAccountNo = store.get(SecureStore.KIS_ACCOUNT_NO),
        linkedProduct = store.get(SecureStore.KIS_ACCOUNT_PRODUCT) ?: "01",
        linkedAccountId = store.get(SecureStore.KIS_LINKED_ACCOUNT_ID),
        syncStart = store.get(SecureStore.KIS_SYNC_START)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        lastSync = store.get(SecureStore.KIS_LAST_SYNC)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    )

    private fun hint(value: String): String = if (value.length <= 4) "••••" else value.take(4) + "••••"

    fun saveKisCredentials(appKey: String, appSecret: String) {
        store.put(SecureStore.KIS_APP_KEY, appKey.trim())
        store.put(SecureStore.KIS_APP_SECRET, appSecret.trim())
        kis.clearToken()
        _status.value = read()
        showMessage("한국투자증권 API 키를 이 기기에 저장했습니다.")
    }

    fun saveKisServiceExpiry(date: LocalDate?) {
        store.put(SecureStore.KIS_SERVICE_EXPIRY, date?.toString())
        _status.value = read()
    }

    fun clearKisCredentials() {
        listOf(SecureStore.KIS_APP_KEY, SecureStore.KIS_APP_SECRET).forEach { store.put(it, null) }
        kis.clearToken()
        _status.value = read()
    }

    // ---------------------------------------------------------------- 접근 토큰 (v2.16)

    /** 접근 토큰 상태: 오전 9시까지 대기 중인지, 무효 응답 뒤 물어봐야 하는지. */
    val tokenState: StateFlow<KisTokenState> = KisClient.tokenState

    /** 오전 9시가 지났는지 등을 다시 확인한다 (설정 화면을 열 때). */
    fun checkTokenState() {
        kis.publishTokenState()
    }

    /** 무효 응답 뒤 "오전 9시까지 대기". */
    fun chooseTokenWait() {
        kis.chooseTokenWait()
        showMessage("오전 9시에 접근 토큰을 새로 받습니다. 그 전에 받으려면 설정 > 한투 연결에서 [지금 새로 받기].")
    }

    /** "지금 새로 받기": 기다리지 않고 바로 새 토큰을 받는다. 받으면 [onRenewed]. */
    fun renewTokenNow(onRenewed: () -> Unit) {
        scope.launch {
            try {
                kis.renewTokenNow()
                showMessage("한투 접근 토큰을 새로 받았습니다.")
                onRenewed()
            } catch (e: ApiException) {
                showMessage(e.message)
            } catch (e: Exception) {
                showMessage("한투 접근 토큰 받기 실패: 인터넷 연결을 확인하세요.")
            }
        }
    }

    fun saveEximKey(key: String) {
        store.put(SecureStore.EXIM_KEY, key.trim())
        _status.value = read()
        showMessage("환율 인증키를 이 기기에 저장했습니다.")
    }

    fun clearEximKey() {
        store.put(SecureStore.EXIM_KEY, null)
        _status.value = read()
    }

    fun testKis() {
        scope.launch {
            showMessage(
                try {
                    val price = kis.domesticPrice("005930")
                    "한투 연결 성공: 삼성전자 현재가 ${MoneyFormat.won(price)}"
                } catch (e: ApiException) {
                    e.message
                } catch (e: Exception) {
                    "한투 연결 실패: 인터넷 연결을 확인하세요."
                },
            )
        }
    }

    fun testExim() {
        scope.launch {
            showMessage(
                try {
                    var day = LocalDate.now()
                    var rate: Double? = null
                    repeat(7) {
                        if (rate == null) {
                            rate = exim.usdKrw(day)
                            if (rate == null) day = day.minusDays(1)
                        }
                    }
                    rate?.let { "환율 연결 성공: $day 기준 1달러 = ${MoneyFormat.decimal(it, 2)}원" } ?: "최근 7일 환율 데이터가 없습니다."
                } catch (e: ApiException) {
                    e.message
                } catch (e: Exception) {
                    "환율 연결 실패: 인터넷 연결을 확인하세요."
                },
            )
        }
    }

    // ---------------------------------------------------------------- 한투 계좌 연결 (M5)

    fun saveKisLink(accountNo: String, product: String, linkedAccountId: String?, syncStart: LocalDate) {
        val previous = _status.value
        store.put(SecureStore.KIS_ACCOUNT_NO, accountNo.filter { it.isDigit() })
        store.put(SecureStore.KIS_ACCOUNT_PRODUCT, product.filter { it.isDigit() }.ifBlank { "01" })
        store.put(SecureStore.KIS_LINKED_ACCOUNT_ID, linkedAccountId)
        store.put(SecureStore.KIS_SYNC_START, syncStart.toString())
        if (previous.linkedAccountId != linkedAccountId || previous.linkedAccountNo != accountNo || previous.syncStart != syncStart) {
            store.put(SecureStore.KIS_LAST_SYNC, null)
        }
        _status.value = read()
        showMessage("연결 계좌 정보를 이 기기에 저장했습니다.")
    }

    /** 앱 계좌를 지우면 그 계좌와의 한투 연결도 푼다. */
    fun forgetLinkedAccount(accountId: String) {
        if (_status.value.linkedAccountId != accountId) return
        store.put(SecureStore.KIS_LINKED_ACCOUNT_ID, null)
        store.put(SecureStore.KIS_LAST_SYNC, null)
        _status.value = read()
    }

    /** 한투 체결을 오늘까지 불러왔다고 기록한다 (다음에는 이 날짜 이후만). */
    fun markSynced() {
        store.put(SecureStore.KIS_LAST_SYNC, LocalDate.now().toString())
    }

    /** 이 기기에 저장된 API 키·연결 정보를 모두 지운다 (탈퇴할 때). */
    fun clearAll() {
        store.clearAll()
        _status.value = read()
    }
}
