package com.mymoneytracker.core.market

import java.time.Instant
import java.time.LocalTime

/** 저장된 한투 접근 토큰을 어떻게 할지: 그대로 쓰기 · 새로 받기 · 오전 9시까지 기다리기. */
enum class TokenAction { REUSE, ISSUE, WAIT }

/**
 * 한투 접근 토큰 규칙 (v2.16, docs/FEATURES.md 1-4): 매일 오전 9시(한국 시간)에 새로 받는다.
 * - 가장 최근의 오전 9시 이후에 받은 토큰이고 만료까지 1분 넘게 남았으면 그대로 쓴다.
 * - 새로 받는 것은 오전 9시 이후에만. 9시 전에 만료되면 9시까지 기다린다 (그 전까지 유효하면 그대로 쓴다).
 * - 저장된 토큰이 없으면 (키를 처음 넣었거나 바꿨을 때) 바로 받는다.
 * - 한투가 토큰 무효라고 답하면 ([invalidAt]) 그 뒤 첫 오전 9시까지 기다린다 (사용자가 "지금 새로 받기"를 고르면 토큰을 지워 바로 받는다).
 */
object KisTokenPolicy {
    val RENEW_AT: LocalTime = LocalTime.of(9, 0)
    private const val EXPIRY_MARGIN_MS = 60_000L
    private const val DAY_MS = 86_400_000L

    /** [now] 의 한국 날짜 오전 9시 (epoch ms). */
    private fun nineOf(now: Long): Long =
        Instant.ofEpochMilli(now).atZone(MarketClock.KST).toLocalDate().atTime(RENEW_AT).atZone(MarketClock.KST).toInstant().toEpochMilli()

    /** [now] 기준 가장 최근의 오전 9시 (한국 시간, epoch ms). */
    fun lastRenewal(now: Long): Long {
        val nine = nineOf(now)
        return if (now < nine) Instant.ofEpochMilli(nine).atZone(MarketClock.KST).minusDays(1).toInstant().toEpochMilli() else nine
    }

    /**
     * [hasToken] 저장된 토큰이 있는지, [issuedAt] 받은 시각 (모르면 만료 24시간 전으로 본다), [expiresAt] 만료 시각,
     * [invalidAt] 한투가 토큰 무효라고 답한 시각 (없으면 null).
     */
    fun decide(hasToken: Boolean, issuedAt: Long?, expiresAt: Long, invalidAt: Long?, now: Long): TokenAction {
        val renewal = lastRenewal(now)
        if (invalidAt != null) return if (invalidAt < renewal) TokenAction.ISSUE else TokenAction.WAIT
        if (!hasToken) return TokenAction.ISSUE
        val issued = issuedAt ?: (expiresAt - DAY_MS)
        val expired = now >= expiresAt - EXPIRY_MARGIN_MS
        if (issued >= renewal && !expired) return TokenAction.REUSE
        if (now < nineOf(now)) return if (expired) TokenAction.WAIT else TokenAction.REUSE
        return TokenAction.ISSUE
    }
}
