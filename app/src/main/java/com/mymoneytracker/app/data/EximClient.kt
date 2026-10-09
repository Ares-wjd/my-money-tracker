package com.mymoneytracker.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 한국수출입은행 현재환율 API. 명세는 docs/API_NOTES.md 참고. */
class EximClient(private val store: SecureStore) {

    val hasKey: Boolean get() = !store.get(SecureStore.EXIM_KEY).isNullOrBlank()

    /**
     * [date] 의 USD 매매기준율. 비영업일이거나 당일 11시 이전이면 null.
     * 인증키 오류·호출 한도 초과는 예외.
     */
    suspend fun usdKrw(date: LocalDate): Double? {
        val key = store.get(SecureStore.EXIM_KEY)
        if (key.isNullOrBlank()) throw ApiException("설정에서 수출입은행 환율 인증키를 입력하세요.")
        val response = withContext(Dispatchers.IO) {
            HttpJson.get(
                URL,
                mapOf("authkey" to key, "searchdate" to date.format(DateTimeFormatter.BASIC_ISO_DATE), "data" to "AP01"),
                emptyMap(),
            )
        }
        val body = response.body.trim()
        if (response.code !in 200..299) throw ApiException("환율 조회 실패 (HTTP ${response.code})")
        if (body.isEmpty() || body == "null") return null
        val array = runCatching { JSONArray(body) }.getOrNull() ?: throw ApiException("환율 응답을 읽지 못했습니다.")
        if (array.length() == 0) return null
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            when (row.optInt("result", 1)) {
                3 -> throw ApiException("환율 인증키가 잘못되었거나 만료(2년 보유기간 경과로 파기)되었습니다. 재발급 후 설정에서 다시 입력하세요.")
                4 -> throw ApiException("오늘 환율 API 호출 한도(1,000회)를 넘었습니다. 내일 다시 시도합니다.")
                2 -> throw ApiException("환율 API 요청 형식 오류입니다.")
            }
            if (row.optString("cur_unit") == "USD") {
                return row.optString("deal_bas_r").replace(",", "").toDoubleOrNull()
            }
        }
        return null
    }

    private companion object {
        const val URL = "https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON"
    }
}
