package com.mymoneytracker.app.data

import com.mymoneytracker.core.market.Candle
import com.mymoneytracker.core.market.MarketCalendar
import com.mymoneytracker.core.market.MarketClock
import com.mymoneytracker.core.market.MarketPhase
import com.mymoneytracker.core.market.MarketState
import com.mymoneytracker.core.market.PriceHistory
import com.mymoneytracker.core.model.Holding
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * 시장별 변동의 일봉 만들기 (v2.10, docs/FEATURES.md 1-5 시장별 변동). 기준가는 저장된 일별 종가 중 일봉 직전 거래일 종가를 먼저 쓰고
 * (응답이 어느 장 값인지 헷갈려도 기준이 틀어지지 않게), 없으면 시세 응답의 전일 종가.
 * 쉬는 중(개장 전·휴장)에는 이미 받은 같은 날 일봉을 그대로 둔다 (장외 마지막 가격을 정규장 종가로 덮지 않게).
 */
class CandleBuilder(private val kis: KisClient) {

    /**
     * 국내: 현재가 = KRX, 프리·애프터·장 마감에는 통합(KRX+NXT) 현재가가 다르면 그것,
     * 16:00 이후 NXT 변동이 없고 KRX 시간외 단일가 체결이 있으면 단일가. 금현물은 KRX 만.
     */
    suspend fun domestic(
        holding: Holding,
        quote: Quote,
        stored: PriceHistory?,
        state: MarketState,
        calendar: MarketCalendar,
        now: ZonedDateTime,
        gold: Boolean,
    ): Candle? {
        keep(stored, state)?.let { return it }
        val pre = state.phase == MarketPhase.PRE
        val storedBase = stored?.closes?.get(MarketClock.prevKrOpen(state.candleDate, calendar))
        // 프리마켓 중 KRX 현재가는 아직 전 개장일 종가
        val base = storedBase ?: (if (pre) quote.price else quote.prevClose) ?: return null
        val regular = if (pre) base else quote.price
        var last = regular
        val extended = pre || state.phase == MarketPhase.AFTER || state.phase == MarketPhase.CLOSED
        if (!gold && extended) {
            runCatching { kis.domesticQuote(holding.code, venue = "UN") }.getOrNull()?.price
                ?.takeIf { it > 0 && abs(it - quote.price) > 1e-9 }
                ?.let { last = it }
        }
        val afternoon = now.withZoneSameInstant(MarketClock.KST).toLocalTime() >= OVERTIME_START
        if (!gold && last == regular && afternoon && (state.phase == MarketPhase.AFTER || state.phase == MarketPhase.CLOSED)) {
            runCatching { kis.domesticOvertime(holding.code) }.getOrNull()
                ?.takeIf { it.second > 0 }
                ?.let { last = it.first }
        }
        return Candle(state.candleDate, base, last, regular, now.toInstant().toEpochMilli())
    }

    /** 미국: 현재가 = 현재체결가, 주간거래 중에는 주간거래 거래소 코드 현재가 (못 받으면 현재체결가). */
    suspend fun overseas(
        holding: Holding,
        quote: Quote,
        stored: PriceHistory?,
        state: MarketState,
        calendar: MarketCalendar,
        now: ZonedDateTime,
    ): Candle? {
        keep(stored, state)?.let { return it }
        val storedBase = stored?.closes?.get(MarketClock.prevUsOpen(state.candleDate, calendar))
        val early = state.phase == MarketPhase.DAY || state.phase == MarketPhase.PRE
        val base = storedBase ?: (if (early) quote.price else quote.prevClose) ?: return null
        var last = quote.price
        if (state.phase == MarketPhase.DAY) {
            runCatching { kis.overseasQuote(holding.market, holding.code, daytime = true) }.getOrNull()?.price
                ?.takeIf { it > 0 }
                ?.let { last = it }
        }
        return Candle(state.candleDate, base, last, null, now.toInstant().toEpochMilli())
    }

    /** 개장 전·휴장에는 이미 받은 같은 날 일봉을 그대로 쓴다. */
    private fun keep(stored: PriceHistory?, state: MarketState): Candle? {
        if (state.phase != MarketPhase.BEFORE_OPEN && state.phase != MarketPhase.HOLIDAY) return null
        return stored?.candle?.takeIf { it.date == state.candleDate }
    }

    private companion object {
        val OVERTIME_START: LocalTime = LocalTime.of(16, 0)
    }
}
