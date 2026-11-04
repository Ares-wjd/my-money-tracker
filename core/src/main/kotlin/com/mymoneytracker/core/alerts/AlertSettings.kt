package com.mymoneytracker.core.alerts

import com.mymoneytracker.core.market.MarketGroup
import java.time.LocalTime

/** 장 마감 정리의 마감 기준 (v2.11): 정규장 마감 / 장외까지 끝난 뒤. */
enum class CloseBasis(val label: String) { REGULAR("정규장"), EXTENDED("장외까지") }

/** 장 마감 정리 한 시장의 설정: 켜기, 마감 기준, 보낼 시각 (한국 시간). */
data class CloseSetting(val on: Boolean, val basis: CloseBasis, val time: LocalTime)

/**
 * 알림 설정 (Firestore settings/alerts, docs/FEATURES.md 1-9). 기준값은 % 단위 (2.0 = ±2%).
 * [allocOff] 는 끈 비중 설정 id (새로 만든 비중 설정은 켜진 상태), [goalDays] 는 남은 날 (0 = 당일).
 */
data class AlertSettings(
    val enabled: Boolean = false,
    val moveOn: Boolean = true,
    val moveMarket: Double = 2.0,
    val moveItem: Double = 5.0,
    val allocOn: Boolean = true,
    val allocOff: Set<String> = emptySet(),
    val goalOn: Boolean = true,
    val goalDays: Set<Int> = setOf(30, 7, 1),
    val goalOverdue: Boolean = true,
    val krClose: CloseSetting = CloseSetting(true, CloseBasis.REGULAR, CloseTimes.earliest(MarketGroup.KR, CloseBasis.REGULAR)),
    val usClose: CloseSetting = CloseSetting(true, CloseBasis.REGULAR, LocalTime.of(7, 0)),
    val quietOn: Boolean = true,
    val quietFrom: LocalTime = LocalTime.of(23, 0),
    val quietTo: LocalTime = LocalTime.of(7, 0),
) {
    fun close(group: MarketGroup): CloseSetting = if (group == MarketGroup.KR) krClose else usClose

    companion object {
        /** 목표 마감 알림에서 고를 수 있는 날. */
        val GOAL_DAY_OPTIONS = listOf(30, 7, 3, 1, 0)
    }
}

/**
 * 장 마감 정리를 보낼 수 있는 시각: 마감 + 10분 이후 ~ 23:50 (한국 시간).
 * 미국은 서머타임이 아닐 때 마감 기준이라 1년 내내 마감 뒤 (서머타임엔 한 시간 일찍 마감).
 */
object CloseTimes {
    val LATEST: LocalTime = LocalTime.of(23, 50)

    fun earliest(group: MarketGroup, basis: CloseBasis): LocalTime = when (group) {
        MarketGroup.KR -> if (basis == CloseBasis.REGULAR) LocalTime.of(15, 40) else LocalTime.of(20, 10)
        else -> if (basis == CloseBasis.REGULAR) LocalTime.of(6, 10) else LocalTime.of(10, 10)
    }

    /** 고를 수 있는 범위로 맞춘다 (기준을 바꿔 너무 이르면 가장 이른 시각). */
    fun clamp(group: MarketGroup, basis: CloseBasis, time: LocalTime): LocalTime {
        val min = earliest(group, basis)
        return when {
            time.isBefore(min) -> min
            time.isAfter(LATEST) -> LATEST
            else -> time
        }
    }

    /** 마감 기준 설명: "정규장 (15:30 마감)" 등. */
    fun basisLabel(group: MarketGroup, basis: CloseBasis): String = when (group) {
        MarketGroup.KR -> if (basis == CloseBasis.REGULAR) "정규장 (15:30 마감)" else "장외까지 (20:00 마감)"
        else -> if (basis == CloseBasis.REGULAR) "정규장 (06:00 마감, 서머타임 05:00)" else "장외까지 (10:00 마감, 서머타임 09:00)"
    }
}
