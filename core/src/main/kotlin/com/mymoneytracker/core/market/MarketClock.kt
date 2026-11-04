package com.mymoneytracker.core.market

import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Market
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** 시장별 변동의 시장 (v2.10). */
enum class MarketGroup(val label: String) {
    KR("국내"), US("미국"), FX("환율");

    companion object {
        /** 종목의 시장: 원화 종목(금현물 포함)은 국내, 달러 종목은 미국. */
        fun of(holding: Holding): MarketGroup = if (holding.market == Market.KR) KR else US
    }
}

/** 지금 시장 상태. [active] 면 가격이 움직이는 중. */
enum class MarketPhase(val label: String, val active: Boolean) {
    PRE("프리마켓", true),
    REGULAR("정규장", true),
    AFTER("애프터마켓", true),
    DAY("주간거래", true),
    FX_OPEN("외환시장 열림", true),
    BEFORE_OPEN("개장 전", false),
    CLOSED("장 마감", false),
    HOLIDAY("휴장", false),
}

/**
 * 한 시장의 지금 상태. [candleDate] 는 지금 진행 중이거나(진행 중) 마지막(쉬는 중) 일봉의 거래일 (미국은 현지 날짜).
 * [reason] 은 휴장 사유 (모르면 null), [nextAt]·[nextPhase] 는 다음 단계가 시작하는 한국 시각과 그 상태.
 */
data class MarketState(
    val group: MarketGroup,
    val phase: MarketPhase,
    val candleDate: LocalDate,
    val reason: String? = null,
    val nextAt: LocalDateTime? = null,
    val nextPhase: MarketPhase? = null,
) {
    /** 상태 표시 글: "정규장", "휴장 · 추수감사절", 사유를 모르면 "휴장". */
    val chip: String get() = if (phase == MarketPhase.HOLIDAY && reason != null) "${phase.label} · $reason" else phase.label
}

/** 시간표의 한 구간 (한국 시간 그날 0시부터 분, [end] 는 포함하지 않음). */
data class SessionSegment(val group: MarketGroup, val phase: MarketPhase, val start: Int, val end: Int)

/**
 * 국내 개장일 정보 (v2.10): [krFrom] ~ [krTo] 는 한투 국내휴장일조회로 확인한 기간, [krClosed] 는 그중(과 예전에 확인한) 휴장일.
 * 확인하지 않은 날은 요일과 날짜가 고정된 휴일로 판단한다.
 */
data class MarketCalendar(
    val krClosed: Set<LocalDate> = emptySet(),
    val krFrom: LocalDate? = null,
    val krTo: LocalDate? = null,
) {
    fun krOpen(date: LocalDate): Boolean {
        if (Holidays.weekend(date) || date in krClosed) return false
        val checked = krFrom != null && krTo != null && !date.isBefore(krFrom) && !date.isAfter(krTo)
        return checked || Holidays.kr(date) == null
    }

    /** 국내 휴장 사유: 주말, 날짜가 고정된 휴일, 모르면 null. */
    fun krReason(date: LocalDate): String? = if (Holidays.weekend(date)) Holidays.WEEKEND else Holidays.kr(date)

    fun usOpen(date: LocalDate): Boolean = !Holidays.weekend(date) && Holidays.us(date) == null

    fun usReason(date: LocalDate): String? = if (Holidays.weekend(date)) Holidays.WEEKEND else Holidays.us(date)
}

/** 휴장 사유가 되는 휴일. 미국은 뉴욕증권거래소 규칙, 국내는 날짜가 고정된 휴일만 (음력·대체공휴일은 한투 조회로). */
object Holidays {
    const val WEEKEND = "주말"

    fun weekend(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    private val KR_FIXED = mapOf(
        "01-01" to "신정", "03-01" to "삼일절", "05-01" to "근로자의 날", "05-05" to "어린이날", "06-06" to "현충일",
        "08-15" to "광복절", "10-03" to "개천절", "10-09" to "한글날", "12-25" to "성탄절", "12-31" to "연말 휴장",
    )

    /** 국내 고정 휴일 이름 (주말이 아니어도 그날이면). */
    fun kr(date: LocalDate): String? = KR_FIXED["%02d-%02d".format(date.monthValue, date.dayOfMonth)]

    /** 뉴욕증권거래소 휴장일 이름 (평일만, 아니면 null). */
    fun us(date: LocalDate): String? {
        if (weekend(date)) return null
        return usHolidays(date.year)[date] ?: usHolidays(date.year + 1)[date]
    }

    private fun usHolidays(year: Int): Map<LocalDate, String> {
        val out = mutableMapOf<LocalDate, String>()
        // 새해 첫날: 일요일이면 월요일, 토요일이면 옮기지 않는다 (전년 12/31 은 연다)
        val newYear = LocalDate.of(year, 1, 1)
        when (newYear.dayOfWeek) {
            DayOfWeek.SATURDAY -> Unit
            DayOfWeek.SUNDAY -> out[newYear.plusDays(1)] = "새해 첫날"
            else -> out[newYear] = "새해 첫날"
        }
        out[nth(year, 1, DayOfWeek.MONDAY, 3)] = "마틴 루서 킹 데이"
        out[nth(year, 2, DayOfWeek.MONDAY, 3)] = "대통령의 날"
        out[easter(year).minusDays(2)] = "성금요일"
        out[LocalDate.of(year, 5, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY))] = "메모리얼 데이"
        if (year >= 2022) out[observed(LocalDate.of(year, 6, 19))] = "준틴스"
        out[observed(LocalDate.of(year, 7, 4))] = "독립기념일"
        out[nth(year, 9, DayOfWeek.MONDAY, 1)] = "노동절"
        out[nth(year, 11, DayOfWeek.THURSDAY, 4)] = "추수감사절"
        out[observed(LocalDate.of(year, 12, 25))] = "크리스마스"
        return out
    }

    /** 토요일이면 금요일, 일요일이면 월요일에 쉰다. */
    private fun observed(date: LocalDate): LocalDate = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date.minusDays(1)
        DayOfWeek.SUNDAY -> date.plusDays(1)
        else -> date
    }

    private fun nth(year: Int, month: Int, day: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day))

    /** 부활절 (그레고리력). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }
}

/**
 * 지금 시장 상태 (웹 marketClock.ts 와 같은 규칙, v2.10). 시각은 모두 한국 시간으로 돌려주고, 미국은 뉴욕 시간대로 서머타임을 맞춘다.
 * - 국내: 개장일 08:00 프리마켓(NXT) → 09:00 정규장 → 15:30 애프터마켓(NXT·시간외 단일가) → 20:00 장 마감.
 * - 미국: 현지 날짜 D 의 일봉 = 뉴욕 D−1 20:00 주간거래 → D 04:00 프리 → 09:30 정규장 → 16:00 애프터 → 20:00.
 * - 환율(서울 외환시장): 개장일 09:00 ~ 다음 날 02:00.
 */
object MarketClock {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")
    val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

    private val KR_PRE = LocalTime.of(8, 0)
    private val KR_REGULAR = LocalTime.of(9, 0)
    private val KR_AFTER = LocalTime.of(15, 30)
    private val KR_END = LocalTime.of(20, 0)
    private val US_PRE = LocalTime.of(4, 0)
    private val US_REGULAR = LocalTime.of(9, 30)
    private val US_AFTER = LocalTime.of(16, 0)
    private val US_END = LocalTime.of(20, 0)
    private val FX_OPEN = LocalTime.of(9, 0)
    private val FX_END = LocalTime.of(2, 0)

    fun states(now: ZonedDateTime, calendar: MarketCalendar): Map<MarketGroup, MarketState> =
        mapOf(MarketGroup.KR to kr(now, calendar), MarketGroup.US to us(now, calendar), MarketGroup.FX to fx(now, calendar))

    fun kr(now: ZonedDateTime, calendar: MarketCalendar): MarketState {
        val k = now.withZoneSameInstant(KST)
        val d = k.toLocalDate()
        val t = k.toLocalTime()
        val g = MarketGroup.KR
        if (!calendar.krOpen(d)) {
            return MarketState(g, MarketPhase.HOLIDAY, prevKrOpen(d, calendar), calendar.krReason(d), nextKrOpen(d.plusDays(1), calendar).atTime(KR_PRE), MarketPhase.PRE)
        }
        return when {
            t < KR_PRE -> MarketState(g, MarketPhase.BEFORE_OPEN, prevKrOpen(d, calendar), null, d.atTime(KR_PRE), MarketPhase.PRE)
            t < KR_REGULAR -> MarketState(g, MarketPhase.PRE, d, null, d.atTime(KR_REGULAR), MarketPhase.REGULAR)
            t < KR_AFTER -> MarketState(g, MarketPhase.REGULAR, d, null, d.atTime(KR_AFTER), MarketPhase.AFTER)
            t < KR_END -> MarketState(g, MarketPhase.AFTER, d, null, d.atTime(KR_END), MarketPhase.CLOSED)
            else -> MarketState(g, MarketPhase.CLOSED, d, null, nextKrOpen(d.plusDays(1), calendar).atTime(KR_PRE), MarketPhase.PRE)
        }
    }

    fun us(now: ZonedDateTime, calendar: MarketCalendar): MarketState {
        val et = now.withZoneSameInstant(NEW_YORK)
        val t = et.toLocalTime()
        val d = if (t >= US_END) et.toLocalDate().plusDays(1) else et.toLocalDate()
        val g = MarketGroup.US
        if (!calendar.usOpen(d)) {
            // 다음 일봉은 다음 거래일 전날 20:00 (뉴욕) 주간거래로 시작한다
            val start = kst(nextUsOpen(d.plusDays(1), calendar).minusDays(1).atTime(US_END))
            val phase = if (start.toLocalDate() == now.withZoneSameInstant(KST).toLocalDate()) MarketPhase.BEFORE_OPEN else MarketPhase.HOLIDAY
            val reason = if (phase == MarketPhase.HOLIDAY) calendar.usReason(d) else null
            return MarketState(g, phase, prevUsOpen(d, calendar), reason, start, MarketPhase.DAY)
        }
        return when {
            t >= US_END || t < US_PRE -> MarketState(g, MarketPhase.DAY, d, null, kst(d.atTime(US_PRE)), MarketPhase.PRE)
            t < US_REGULAR -> MarketState(g, MarketPhase.PRE, d, null, kst(d.atTime(US_REGULAR)), MarketPhase.REGULAR)
            t < US_AFTER -> MarketState(g, MarketPhase.REGULAR, d, null, kst(d.atTime(US_AFTER)), MarketPhase.AFTER)
            else -> MarketState(
                g, MarketPhase.AFTER, d, null, kst(d.atTime(US_END)),
                if (calendar.usOpen(d.plusDays(1))) MarketPhase.DAY else MarketPhase.CLOSED,
            )
        }
    }

    fun fx(now: ZonedDateTime, calendar: MarketCalendar): MarketState {
        val k = now.withZoneSameInstant(KST)
        val d = k.toLocalDate()
        val t = k.toLocalTime()
        val g = MarketGroup.FX
        return when {
            t < FX_END && calendar.krOpen(d.minusDays(1)) -> MarketState(g, MarketPhase.FX_OPEN, d.minusDays(1), null, d.atTime(FX_END), MarketPhase.CLOSED)
            !calendar.krOpen(d) -> MarketState(g, MarketPhase.HOLIDAY, prevKrOpen(d, calendar), calendar.krReason(d), nextKrOpen(d.plusDays(1), calendar).atTime(FX_OPEN), MarketPhase.FX_OPEN)
            t < FX_OPEN -> MarketState(g, MarketPhase.BEFORE_OPEN, prevKrOpen(d, calendar), null, d.atTime(FX_OPEN), MarketPhase.FX_OPEN)
            else -> MarketState(g, MarketPhase.FX_OPEN, d, null, d.plusDays(1).atTime(FX_END), MarketPhase.CLOSED)
        }
    }

    /** [date] 바로 전 국내 개장일. */
    fun prevKrOpen(date: LocalDate, calendar: MarketCalendar): LocalDate = step(date.minusDays(1), -1) { calendar.krOpen(it) }

    /** [date] 또는 그 뒤 첫 국내 개장일. */
    fun nextKrOpen(date: LocalDate, calendar: MarketCalendar): LocalDate = step(date, 1) { calendar.krOpen(it) }

    fun prevUsOpen(date: LocalDate, calendar: MarketCalendar): LocalDate = step(date.minusDays(1), -1) { calendar.usOpen(it) }

    fun nextUsOpen(date: LocalDate, calendar: MarketCalendar): LocalDate = step(date, 1) { calendar.usOpen(it) }

    private fun step(from: LocalDate, by: Long, ok: (LocalDate) -> Boolean): LocalDate {
        var d = from
        repeat(40) {
            if (ok(d)) return d
            d = d.plusDays(by)
        }
        return d
    }

    private fun kst(newYork: LocalDateTime): LocalDateTime = newYork.atZone(NEW_YORK).withZoneSameInstant(KST).toLocalDateTime()

    /**
     * 한국 시간 [date] 하루(0~24시)의 시장별 구간: 국내 프리·정규·애프터, 미국 주간·프리·정규·애프터 (서머타임 반영),
     * 환율 열린 구간. 상세 화면의 "지금 열린 장" 시간표.
     */
    fun timeline(date: LocalDate, calendar: MarketCalendar): List<SessionSegment> {
        val out = mutableListOf<SessionSegment>()
        if (calendar.krOpen(date)) {
            out += SessionSegment(MarketGroup.KR, MarketPhase.PRE, 8 * 60, 9 * 60)
            out += SessionSegment(MarketGroup.KR, MarketPhase.REGULAR, 9 * 60, 15 * 60 + 30)
            out += SessionSegment(MarketGroup.KR, MarketPhase.AFTER, 15 * 60 + 30, 20 * 60)
        }
        val dayStart = date.atStartOfDay()
        val dayEnd = date.plusDays(1).atStartOfDay()
        for (offset in -1L..1L) {
            val d = date.plusDays(offset)
            if (!calendar.usOpen(d)) continue
            val parts = listOf(
                MarketPhase.DAY to (d.minusDays(1).atTime(US_END) to d.atTime(US_PRE)),
                MarketPhase.PRE to (d.atTime(US_PRE) to d.atTime(US_REGULAR)),
                MarketPhase.REGULAR to (d.atTime(US_REGULAR) to d.atTime(US_AFTER)),
                MarketPhase.AFTER to (d.atTime(US_AFTER) to d.atTime(US_END)),
            )
            for ((phase, range) in parts) {
                val s = maxOf(kst(range.first), dayStart)
                val e = minOf(kst(range.second), dayEnd)
                if (s.isBefore(e)) {
                    out += SessionSegment(MarketGroup.US, phase, minutes(dayStart, s), minutes(dayStart, e))
                }
            }
        }
        if (calendar.krOpen(date.minusDays(1))) out += SessionSegment(MarketGroup.FX, MarketPhase.FX_OPEN, 0, 2 * 60)
        if (calendar.krOpen(date)) out += SessionSegment(MarketGroup.FX, MarketPhase.FX_OPEN, 9 * 60, 24 * 60)
        return out.sortedWith(compareBy({ it.group.ordinal }, { it.start }))
    }

    private fun minutes(from: LocalDateTime, to: LocalDateTime): Int = ChronoUnit.MINUTES.between(from, to).toInt()

    /** 다음 단계 안내: "08:00 프리마켓", "내일 08:00 프리마켓", "11/10(월) 10:00 주간거래". */
    fun nextText(state: MarketState, now: ZonedDateTime): String? {
        val at = state.nextAt ?: return null
        val label = state.nextPhase?.label ?: return null
        val today = now.withZoneSameInstant(KST).toLocalDate()
        val time = "%02d:%02d".format(at.hour, at.minute)
        val day = when (ChronoUnit.DAYS.between(today, at.toLocalDate())) {
            0L -> ""
            1L -> "내일 "
            else -> dayText(at.toLocalDate()) + " "
        }
        return "$day$time $label"
    }

    /** "11/3(월)". */
    fun dayText(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}(${WEEKDAYS[date.dayOfWeek.value - 1]})"

    private val WEEKDAYS = listOf("월", "화", "수", "목", "금", "토", "일")
}
