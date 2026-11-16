package com.mymoneytracker.core.market

import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 한투 접근 토큰 재사용 규칙 (v2.12, docs/FEATURES.md 1-4): 매일 오전 10시(한국 시간)에 새로 받는다.
 * 가장 최근의 오전 10시 이후에 받은 토큰이고 만료까지 1분 넘게 남았으면 재사용한다.
 */
object KisTokenPolicy {
    val RENEW_AT: LocalTime = LocalTime.of(10, 0)
    private const val EXPIRY_MARGIN_MS = 60_000L

    /** [now] 기준 가장 최근의 오전 10시 (한국 시간, epoch ms). */
    fun lastRenewal(now: Long): Long {
        val kst = Instant.ofEpochMilli(now).atZone(MarketClock.KST)
        val today: ZonedDateTime = kst.toLocalDate().atTime(RENEW_AT).atZone(MarketClock.KST)
        val boundary = if (kst.isBefore(today)) today.minusDays(1) else today
        return boundary.toInstant().toEpochMilli()
    }

    /** 저장된 토큰을 그대로 쓸지. [issuedAt] 을 모르면(예전 버전) 만료 24시간 전에 받은 것으로 본다. */
    fun reusable(issuedAt: Long?, expiresAt: Long, now: Long): Boolean {
        if (now >= expiresAt - EXPIRY_MARGIN_MS) return false
        val issued = issuedAt ?: (expiresAt - 86_400_000L)
        return issued >= lastRenewal(now)
    }
}
