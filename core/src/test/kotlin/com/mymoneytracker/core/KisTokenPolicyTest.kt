package com.mymoneytracker.core

import com.mymoneytracker.core.market.KisTokenPolicy
import com.mymoneytracker.core.market.TokenAction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class KisTokenPolicyTest {
    private fun at(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()
    private val day = 86_400_000L
    private fun decide(issued: Long?, expires: Long, now: String, invalidAt: Long? = null, hasToken: Boolean = true) =
        KisTokenPolicy.decide(hasToken, issued, expires, invalidAt, at(now))

    @Test
    fun renewsOnceAfterNineEveryDay() {
        assertEquals(at("2026-11-16T09:00:00+09:00"), KisTokenPolicy.lastRenewal(at("2026-11-16T09:00:00+09:00")))
        assertEquals(at("2026-11-15T09:00:00+09:00"), KisTokenPolicy.lastRenewal(at("2026-11-16T08:59:59+09:00")))

        val issued = at("2026-11-16T09:03:00+09:00")
        // 받은 뒤 다음 날 9시 전까지 그대로
        assertEquals(TokenAction.REUSE, decide(issued, issued + day, "2026-11-16T23:30:00+09:00"))
        assertEquals(TokenAction.REUSE, decide(issued, issued + day, "2026-11-17T08:59:00+09:00"))
        // 다음 날 9시가 지나면 새로
        assertEquals(TokenAction.ISSUE, decide(issued, issued + day, "2026-11-17T09:00:00+09:00"))
        // 오후에 처음 받은 토큰도 다음 날 9시에 새로 (아직 유효해도)
        val afternoon = at("2026-11-16T15:00:00+09:00")
        assertEquals(TokenAction.REUSE, decide(afternoon, afternoon + day, "2026-11-17T08:30:00+09:00"))
        assertEquals(TokenAction.ISSUE, decide(afternoon, afternoon + day, "2026-11-17T09:01:00+09:00"))
        // 예전 버전(받은 시각 모름): 만료 24시간 전에 받은 것으로 본다
        assertEquals(TokenAction.REUSE, decide(null, at("2026-11-17T10:30:00+09:00"), "2026-11-17T08:30:00+09:00"))
        assertEquals(TokenAction.ISSUE, decide(null, at("2026-11-17T08:30:00+09:00"), "2026-11-17T09:30:00+09:00"))
    }

    @Test
    fun waitsUntilNineWhenExpiredBefore() {
        // 새벽에 처음 받은 토큰 (그날 9시 이후 조회가 없었음): 다음 날 새벽에 만료 → 9시까지 기다림
        val early = at("2026-11-16T07:00:00+09:00")
        assertEquals(TokenAction.REUSE, decide(early, early + day, "2026-11-17T06:30:00+09:00"))
        assertEquals(TokenAction.WAIT, decide(early, early + day, "2026-11-17T07:00:00+09:00"))
        assertEquals(TokenAction.WAIT, decide(early, early + day, "2026-11-17T08:59:00+09:00"))
        assertEquals(TokenAction.ISSUE, decide(early, early + day, "2026-11-17T09:00:00+09:00"))
        // 만료 1분 전이면 만료로 본다
        assertEquals(TokenAction.WAIT, decide(early, early + day, "2026-11-17T06:59:30+09:00"))
        // 같은 날 9시가 지나면 새로 (9시에 맞춤)
        assertEquals(TokenAction.ISSUE, decide(early, early + day, "2026-11-16T09:10:00+09:00"))
    }

    @Test
    fun noTokenIssuesRightAway() {
        assertEquals(TokenAction.ISSUE, decide(null, 0, "2026-11-17T03:00:00+09:00", hasToken = false))
    }

    @Test
    fun invalidTokenWaitsUntilNextNine() {
        val issued = at("2026-11-16T09:05:00+09:00")
        val invalid = at("2026-11-16T20:00:00+09:00")
        assertEquals(TokenAction.WAIT, decide(issued, issued + day, "2026-11-16T20:30:00+09:00", invalid))
        assertEquals(TokenAction.WAIT, decide(issued, issued + day, "2026-11-17T08:59:00+09:00", invalid))
        assertEquals(TokenAction.ISSUE, decide(issued, issued + day, "2026-11-17T09:00:00+09:00", invalid))
        // 오전 9시 전에 무효가 되면 그날 9시까지
        val dawn = at("2026-11-17T05:00:00+09:00")
        assertEquals(TokenAction.WAIT, decide(issued, issued + day, "2026-11-17T06:00:00+09:00", dawn))
        assertEquals(TokenAction.ISSUE, decide(issued, issued + day, "2026-11-17T09:00:00+09:00", dawn))
    }
}
