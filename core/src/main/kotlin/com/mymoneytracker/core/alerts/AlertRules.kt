package com.mymoneytracker.core.alerts

import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.goals.GoalUnit
import com.mymoneytracker.core.goals.SavingsSummary
import com.mymoneytracker.core.market.MarketCalendar
import com.mymoneytracker.core.market.MarketClock
import com.mymoneytracker.core.market.MarketGroup
import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.GoalType
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.portfolio.AllocationView
import com.mymoneytracker.core.portfolio.PortfolioSummary
import com.mymoneytracker.core.portfolio.TodayChange
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** 알림 채널 (폰 설정에서 채널마다 소리를 바꿀 수 있다). */
enum class AlertChannel(val id: String, val label: String) {
    MOVE("move", "큰 등락"),
    CLOSE("close", "장 마감 정리"),
    ALLOCATION("allocation", "비중"),
    GOAL("goal", "목표"),
    DIGEST("digest", "밤사이 모음"),
}

/** 알림을 누르면 열 화면: "today" 시장별 변동, "allocation" 비중 탭 ([id] 비중 설정), "goals" 목표 탭. */
data class AlertTarget(val screen: String, val id: String? = null) {
    companion object {
        val TODAY = AlertTarget("today")
        val GOALS = AlertTarget("goals")
        fun allocation(id: String) = AlertTarget("allocation", id)
    }
}

/** 보낼 알림 하나. [marks] 는 보냈다고 기억할 키 (같은 알림을 다시 보내지 않게). */
data class Alert(
    val key: String,
    val channel: AlertChannel,
    val title: String,
    val body: String,
    val target: AlertTarget,
    val marks: List<String> = listOf(key),
)

/**
 * 알림 판단 규칙 (docs/FEATURES.md 1-9). 폰의 백그라운드 확인이 지금 데이터와 보낸 기록을 넘겨 부른다.
 * 여기서는 무엇을 보낼지만 정하고, 보낸 기록·범위 밖 기억은 부른 쪽이 저장한다.
 */
object AlertRules {
    /** 비중 확인 시각과 목표 확인 시각 (한국 시간). */
    val ALLOCATION_TIME: LocalTime = LocalTime.of(8, 30)
    val GOAL_TIME: LocalTime = LocalTime.of(9, 0)

    /** 큰 등락에서 빼는 작은 종목 (전체 투자 자산 평가금 대비). */
    const val MIN_ITEM_SHARE = 0.01

    // ------------------------------------------------------------ 큰 등락

    /** 기준 넘은 단계: 0 없음, 1 기준 이상, 2 기준의 2배 이상. [thresholdPercent] 가 0 이하면 0. */
    fun level(rate: Double?, thresholdPercent: Double): Int {
        if (rate == null || thresholdPercent <= 0) return 0
        val a = abs(rate) * 100
        return when {
            a >= thresholdPercent * 2 - 1e-9 -> 2
            a >= thresholdPercent - 1e-9 -> 1
            else -> 0
        }
    }

    /** 이 단계까지의 키 중 아직 보내지 않은 것 (방향이 바뀌면 다른 키). */
    private fun newKeys(prefix: String, rate: Double, level: Int, sent: Set<String>): List<String> {
        val dir = if (rate >= 0) "UP" else "DOWN"
        return (1..level).map { "$prefix:$dir:$it" }.filter { it !in sent }
    }

    /**
     * 큰 등락: 진행 중인 시장(국내·미국)의 일봉 변동률이 시장 기준 이상이거나, 종목 일봉 등락률이 종목 기준 이상이면
     * 시장마다 한 알림. 같은 일봉·같은 방향에서 단계마다 한 번 (최대 2번). 전체 평가금의 1% 미만 종목은 뺀다.
     */
    fun moves(change: TodayChange?, totalValueKrw: Double, settings: AlertSettings, sent: Set<String>): List<Alert> {
        if (!settings.moveOn || change == null) return emptyList()
        val alerts = mutableListOf<Alert>()
        for (group in listOf(MarketGroup.KR, MarketGroup.US)) {
            val m = change.market(group) ?: continue
            if (!m.live) continue
            val date = m.candleDate ?: m.state.candleDate
            val marketLevel = level(m.rate, settings.moveMarket)
            val marks = mutableListOf<String>()
            if (marketLevel > 0) marks += newKeys("MOVE:${group.name}:$date", m.rate!!, marketLevel, sent)
            val marketNew = marks.isNotEmpty()
            data class Hit(val label: String, val rate: Double, val level: Int)
            val hits = mutableListOf<Hit>()
            for (item in m.items) {
                val rate = item.rate ?: continue
                if (!item.live || item.valueKrw < totalValueKrw * MIN_ITEM_SHARE) continue
                val lv = level(rate, settings.moveItem)
                if (lv == 0) continue
                val keys = newKeys("MOVE_ITEM:${item.key}:$date", rate, lv, sent)
                if (keys.isEmpty()) continue
                marks += keys
                hits += Hit(item.label, rate, lv)
            }
            if (!marketNew && hits.isEmpty()) continue
            val title = if (marketLevel > 0) {
                "${group.label} ${MoneyFormat.rate(m.rate)} (${signedShort(m.changeKrw ?: 0.0)}) · ${m.state.phase.label}"
            } else {
                "${group.label} · 종목 큰 등락"
            }
            val names = if (hits.isNotEmpty()) {
                hits.sortedByDescending { abs(it.rate) }.map { "${it.label} ${MoneyFormat.rate(it.rate)}" + if (it.level == 2) " (2배 넘음)" else "" }
            } else {
                m.items.filter { it.live && it.rate != null }.sortedByDescending { abs(it.rate!!) }.take(3).map { "${it.label} ${MoneyFormat.rate(it.rate)}" }
            }
            val body = "${MarketClock.dayText(date)} 일봉" + if (names.isEmpty()) "" else " · " + names.joinToString(", ")
            alerts += Alert(marks.first(), AlertChannel.MOVE, title, body, AlertTarget.TODAY, marks)
        }
        return alerts
    }

    // ------------------------------------------------------------ 비중

    /** 비중 확인 결과: 보낼 알림과 새로 기억할 범위 밖 종목 (비중 설정 id → 종목 키). */
    data class AllocationResult(val alerts: List<Alert>, val out: Map<String, Set<String>>)

    /**
     * 비중 허용 범위 이탈: 비중 화면의 강조와 같은 기준. 비중 설정마다 범위 밖 종목을 기억해 두고([prevOut])
     * 새로 범위 밖으로 나간 종목이 있을 때만 알린다 (범위 안으로 돌아오면 잊는다). 끈 비중 설정은 기억도 그대로 둔다.
     */
    fun allocations(views: List<AllocationView>, prevOut: Map<String, Set<String>>, settings: AlertSettings, today: LocalDate): AllocationResult {
        if (!settings.allocOn) return AllocationResult(emptyList(), prevOut)
        val out = prevOut.toMutableMap()
        val alerts = mutableListOf<Alert>()
        for (view in views) {
            val id = view.target.id
            if (id in settings.allocOff) continue
            val rows = view.rows.filter { view.highlighted(it) }
            val before = prevOut[id].orEmpty()
            out[id] = rows.map { it.key }.toSet()
            val newly = rows.filter { it.key !in before }
            if (newly.isEmpty()) continue
            val body = newly.joinToString(" · ") { row ->
                val tag = if (row.diffPoints > 0) "초과" else "부족"
                val range = view.range(row)?.let { (lo, hi) -> " (${MoneyFormat.decimal(lo, 1)}~${MoneyFormat.decimal(hi, 1)}%)" }.orEmpty()
                "$tag ${row.name} ${MoneyFormat.decimal(row.currentWeight * 100, 1)}%$range"
            }
            alerts += Alert("ALLOC:$id:$today", AlertChannel.ALLOCATION, "${view.target.name} · 허용 범위 밖 ${rows.size}종목", body, AlertTarget.allocation(id))
        }
        return AllocationResult(alerts, out)
    }

    // ------------------------------------------------------------ 목표

    /**
     * 목표 마감 임박: 완료하지 않은 목표(하나로 계산하는 그룹은 묶음 하나)의 다음 날짜까지 남은 날이 고른 날이면 한 번.
     * 반복 목표의 예정일이 지났는데 소비 기록이 없으면 (예정일이 넘어가지 않으므로 다음 날짜 < 오늘) 한 번.
     */
    fun goals(summaries: List<SavingsSummary>, today: LocalDate, settings: AlertSettings, sent: Set<String>): List<Alert> {
        if (!settings.goalOn) return emptyList()
        val alerts = mutableListOf<Alert>()
        fun add(id: String, name: String, accountName: String, due: LocalDate, amount: Double, allocated: Double, recurring: Boolean) {
            val days = ChronoUnit.DAYS.between(today, due).toInt()
            if (days >= 0 && days in settings.goalDays) {
                val key = "GOAL:$id:$due:$days"
                if (key in sent) return
                val left = if (days == 0) "오늘" else "${days}일 남음"
                val remaining = amount - allocated
                val rate = if (amount > 0) minOf(1.0, allocated / amount) else 1.0
                val status = if (remaining > 0.5) "${MoneyFormat.shortWon(remaining)} 부족" else "달성"
                alerts += Alert(
                    key,
                    AlertChannel.GOAL,
                    "$name · $left (${due.monthValue}/${due.dayOfMonth})",
                    "$accountName · 달성 ${MoneyFormat.decimal(rate * 100, 0)}% (${MoneyFormat.shortWon(allocated)} / ${MoneyFormat.shortWon(amount)}) · $status",
                    AlertTarget.GOALS,
                )
            } else if (days < 0 && recurring && settings.goalOverdue) {
                val key = "GOAL_OVERDUE:$id:$due"
                if (key in sent) return
                alerts += Alert(
                    key,
                    AlertChannel.GOAL,
                    "$name · 예정일 지남",
                    "${due.monthValue}/${due.dayOfMonth} 예정이었어요. 샀다면 소비 기록을 남겨 주세요.",
                    AlertTarget.GOALS,
                )
            }
        }
        for (s in summaries) {
            for (unit in s.units) {
                when (unit) {
                    is GoalUnit.Single -> unit.progress.let { p ->
                        add(p.goal.id, p.goal.name, s.account.name, p.nextDueDate, p.goal.amount, p.allocated, p.goal.type == GoalType.RECURRING)
                    }
                    is GoalUnit.Group -> {
                        val combined = unit.combined
                        if (combined != null) {
                            add("group:${unit.group.id}", unit.group.name, s.account.name, unit.group.dueDate, combined.amount, combined.allocated, false)
                        } else {
                            unit.members.forEach { p ->
                                add(p.goal.id, p.goal.name, s.account.name, p.nextDueDate, p.goal.amount, p.allocated, p.goal.type == GoalType.RECURRING)
                            }
                        }
                    }
                }
            }
        }
        return alerts
    }

    // ------------------------------------------------------------ 장 마감 정리

    /**
     * 장 마감 정리를 보낼 일봉 날짜: 켜져 있고 지금(한국 시간)이 정한 시각 이후이며 그 일봉 날이 개장일이면.
     * 국내는 오늘, 미국은 한국 날짜 하루 전 미국 거래일 (마감이 한국 시간 다음 날 새벽·오전).
     */
    fun closeDate(group: MarketGroup, setting: CloseSetting, now: ZonedDateTime, calendar: MarketCalendar): LocalDate? {
        if (!setting.on) return null
        val kst = now.withZoneSameInstant(MarketClock.KST)
        if (kst.toLocalTime().isBefore(setting.time)) return null
        return if (group == MarketGroup.KR) {
            kst.toLocalDate().takeIf { calendar.krOpen(it) }
        } else {
            kst.toLocalDate().minusDays(1).takeIf { calendar.usOpen(it) }
        }
    }

    /** 장 마감 정리의 종목 한 줄. */
    data class CloseLine(val label: String, val changeKrw: Double, val rate: Double, val valueKrw: Double)

    /** 정리 내용: 종목들과 합계. [fxRate] 미국 환산 환율. */
    data class CloseResult(val lines: List<CloseLine>, val changeKrw: Double, val rate: Double?, val valueKrw: Double, val fxRate: Double?)

    /**
     * 장 마감 정리 계산 (내 종목만): 그 시장 보유 종목(같은 시세 키는 합침)의 [date] 일봉으로 변동 = 수량 × (마감 가격 − 기준가).
     * 정규장 기준 가격은 국내 일봉의 정규장 가격, 미국은 [regularCloses] (한투 일별 종가) → 저장된 그날 종가 → 일봉 현재가.
     * 장외까지 기준은 일봉 현재가. 일봉은 지금 일봉 또는 직전 일봉 중 그 날짜. 해외는 지금 환율로 원화. 그날 일봉이 하나도 없으면 null.
     */
    fun closeResult(
        group: MarketGroup,
        date: LocalDate,
        basis: CloseBasis,
        summary: PortfolioSummary,
        snapshot: MarketSnapshot,
        regularCloses: Map<String, Double> = emptyMap(),
    ): CloseResult? {
        data class Acc(val holding: Holding, var quantity: Double)
        val byKey = linkedMapOf<String, Acc>()
        for (account in summary.accounts) {
            for (v in account.holdings) {
                val h = v.holding
                if (v.position.quantity <= 1e-9 || MarketGroup.of(h) != group) continue
                val key = MarketKeys.of(h) ?: continue
                val history = snapshot.prices[key] ?: continue
                // 직접 입력 가격을 쓰는 종목은 뺀다 (시장별 변동과 같음)
                if (history.latest == null || PriceLookup.current(h, snapshot) != history.latest) continue
                byKey.getOrPut(key) { Acc(h, 0.0) }.quantity += v.position.quantity
            }
        }
        val fx = summary.usdKrw
        val lines = mutableListOf<CloseLine>()
        var change = 0.0
        var base = 0.0
        var value = 0.0
        for ((key, acc) in byKey) {
            val history = snapshot.prices[key] ?: continue
            val candle = history.candle?.takeIf { it.date == date } ?: history.prevCandle?.takeIf { it.date == date } ?: continue
            if (candle.base <= 0) continue
            val price = when {
                basis == CloseBasis.EXTENDED -> candle.last
                group == MarketGroup.KR -> candle.regular ?: candle.last
                else -> regularCloses[key] ?: history.closes[date] ?: candle.last
            }
            val rate = if (acc.holding.currency == Currency.USD) fx ?: continue else 1.0
            val c = acc.quantity * (price - candle.base) * rate
            change += c
            base += acc.quantity * candle.base * rate
            value += acc.quantity * price * rate
            lines += CloseLine(label(acc.holding), c, price / candle.base - 1, acc.quantity * price * rate)
        }
        if (lines.isEmpty()) return null
        return CloseResult(lines, change, if (base > 0) change / base else null, value, if (group == MarketGroup.US) fx else null)
    }

    /** 장 마감 정리 알림: 합계 제목, 오른·내린 종목 3개씩 (등락률 순), 평가금·기준 줄. 미국은 환율과 환율 효과 [fxEffectKrw]. */
    fun closeAlert(group: MarketGroup, date: LocalDate, basis: CloseBasis, result: CloseResult, fxEffectKrw: Double? = null): Alert {
        val ups = result.lines.filter { it.rate > 0 }.sortedByDescending { it.rate }.take(3)
        val downs = result.lines.filter { it.rate < 0 }.sortedBy { it.rate }.take(3)
        val body = buildList {
            if (ups.isNotEmpty()) add("▲ " + ups.joinToString(" · ") { "${it.label} ${MoneyFormat.percent(it.rate)}" })
            if (downs.isNotEmpty()) add("▼ " + downs.joinToString(" · ") { "${it.label} ${MoneyFormat.percent(it.rate)}" })
            var foot = "${group.label} 평가금 ${MoneyFormat.shortWon(result.valueKrw)} · ${MarketClock.dayText(date)} " +
                (if (basis == CloseBasis.REGULAR) "정규장 마감" else "장외까지") + " 기준"
            if (group == MarketGroup.US) {
                result.fxRate?.let { foot += " · 환율 ${MoneyFormat.decimal(it, 1)}원" }
                fxEffectKrw?.takeIf { abs(it) > 0.5 }?.let { foot += " · 환율 효과 ${signedShort(it)}" }
            }
            add(foot)
        }.joinToString("\n")
        return Alert(
            "CLOSE:${group.name}:$date",
            AlertChannel.CLOSE,
            "${group.label} 마감 · 내 종목 ${signedShort(result.changeKrw)} ${MoneyFormat.rate(result.rate)}",
            body,
            AlertTarget.TODAY,
        )
    }

    // ------------------------------------------------------------ 밤 모으기 · 시각

    /** 밤 모으기 시간인지 ([quietFrom] ~ [quietTo], 자정을 넘어가도 된다). */
    fun isQuiet(time: LocalTime, settings: AlertSettings): Boolean {
        if (!settings.quietOn || settings.quietFrom == settings.quietTo) return false
        return if (settings.quietFrom.isBefore(settings.quietTo)) {
            !time.isBefore(settings.quietFrom) && time.isBefore(settings.quietTo)
        } else {
            !time.isBefore(settings.quietFrom) || time.isBefore(settings.quietTo)
        }
    }

    /** 밤에 모은 알림을 한 알림으로: "밤사이 알림 n건", 각 알림 제목 (최대 5줄, 나머지 "외 n건"). */
    fun digest(pending: List<Alert>, today: LocalDate): Alert? {
        if (pending.isEmpty()) return null
        if (pending.size == 1) return pending.single()
        val lines = pending.take(5).map { it.title } + if (pending.size > 5) listOf("외 ${pending.size - 5}건") else emptyList()
        val target = pending.map { it.target }.distinct().singleOrNull() ?: AlertTarget.TODAY
        return Alert("DIGEST:$today:${pending.size}", AlertChannel.DIGEST, "밤사이 알림 ${pending.size}건", lines.joinToString("\n"), target, emptyList())
    }

    /** 하루 한 번 확인(비중·목표)할 때인지: [time] 이후이고 오늘 아직 확인하지 않았으면. */
    fun dailyDue(now: LocalDateTime, time: LocalTime, lastChecked: LocalDate?): Boolean =
        !now.toLocalTime().isBefore(time) && lastChecked != now.toLocalDate()

    /** 다음에 시각 알람으로 깨울 때: 비중·목표 확인 시각, 장 마감 정리 시각, 밤 모으기 끝 중 가장 이른 다음 시각. 없으면 null. */
    fun nextWake(now: ZonedDateTime, settings: AlertSettings): ZonedDateTime? {
        if (!settings.enabled) return null
        val kst = now.withZoneSameInstant(MarketClock.KST)
        val times = buildList {
            if (settings.allocOn) add(ALLOCATION_TIME)
            if (settings.goalOn) add(GOAL_TIME)
            if (settings.krClose.on) add(settings.krClose.time)
            if (settings.usClose.on) add(settings.usClose.time)
            if (settings.quietOn) add(settings.quietTo)
        }
        return times.map { t ->
            val today = kst.toLocalDate().atTime(t).atZone(MarketClock.KST)
            if (today.isAfter(kst.plusSeconds(30))) today else today.plusDays(1)
        }.minOrNull()
    }

    // ------------------------------------------------------------ 글

    /** 목록에 쓰는 종목 이름 (시장별 변동과 같음): 금현물, 종목명, 이름이 없으면 종목코드. */
    private fun label(h: Holding): String = when {
        h.assetType == AssetType.GOLD -> "금현물"
        h.name.isNotBlank() -> h.name.trim()
        else -> h.code.uppercase()
    }

    /** "+18.0만원" / "-48.2만원". */
    fun signedShort(v: Double): String = (if (v > 0.5) "+" else "") + MoneyFormat.shortWon(v)
}
