package com.mymoneytracker.core

import com.mymoneytracker.core.market.KisTokenPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class KisTokenPolicyTest {
    private fun at(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()
    private val day = 86_400_000L

    @Test
    fun renewsOnceAfterTenEveryDay() {
        assertEquals(at("2026-11-16T10:00:00+09:00"), KisTokenPolicy.lastRenewal(at("2026-11-16T10:00:00+09:00")))
        assertEquals(at("2026-11-15T10:00:00+09:00"), KisTokenPolicy.lastRenewal(at("2026-11-16T09:59:59+09:00")))

        val issued = at("2026-11-16T10:03:00+09:00")
        val expires = issued + day
        // 받은 뒤 다음 날 10시 전까지 재사용
        assertTrue(KisTokenPolicy.reusable(issued, expires, at("2026-11-16T23:30:00+09:00")))
        assertTrue(KisTokenPolicy.reusable(issued, expires, at("2026-11-17T09:59:00+09:00")))
        // 다음 날 10시가 지나면 새로
        assertFalse(KisTokenPolicy.reusable(issued, expires, at("2026-11-17T10:00:00+09:00")))
        // 오후에 처음 받은 토큰도 다음 날 10시에 새로 (아직 유효해도)
        val afternoon = at("2026-11-16T15:00:00+09:00")
        assertTrue(KisTokenPolicy.reusable(afternoon, afternoon + day, at("2026-11-17T09:30:00+09:00")))
        assertFalse(KisTokenPolicy.reusable(afternoon, afternoon + day, at("2026-11-17T10:01:00+09:00")))
        // 만료 1분 전이면 새로
        val morning = at("2026-11-16T09:00:00+09:00")
        assertFalse(KisTokenPolicy.reusable(at("2026-11-15T09:00:30+09:00"), morning + 30_000, morning))
        // 예전 버전(받은 시각 모름): 만료 24시간 전에 받은 것으로 본다
        assertTrue(KisTokenPolicy.reusable(null, at("2026-11-17T10:30:00+09:00"), at("2026-11-17T09:00:00+09:00")))
        assertFalse(KisTokenPolicy.reusable(null, at("2026-11-17T09:30:00+09:00"), at("2026-11-17T09:00:00+09:00")))
    }
}
