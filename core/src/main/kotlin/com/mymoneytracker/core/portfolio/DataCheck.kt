package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 데이터 점검 (v2.9, 웹 dataCheck.ts 와 같은 규칙·같은 설명 글): 계산이 틀어지거나 숫자가 비었을 수 있는 곳을 모은다.
 * 무시한 항목은 지우지 않고 화면에 흐리게 남긴다 ([alertCount] 에서만 뺀다).
 */
object DataCheck {

    /** 단계: 계산이 틀어짐 / 비었거나 오래됨 / 확인해 보세요. */
    enum class Level(val label: String) { ERROR("계산이 틀어짐"), MISSING("비었거나 오래됨"), REVIEW("확인해 보세요") }

    /** 누르면 갈 곳. */
    enum class Target { RECORD, ACCOUNT, HOLDING, SETTINGS }

    enum class Kind(val level: Level, val target: Target, val title: String, val ignoreEffect: String) {
        SHORT_SELL(
            Level.ERROR, Target.RECORD, "계산되지 않는 매도",
            "이 매도는 계속 계산에서 빠집니다. 보유 수량·평균단가·실현손익이 실제와 다르게 보입니다.",
        ),
        NEGATIVE_CASH(
            Level.ERROR, Target.ACCOUNT, "음수 예수금",
            "음수 예수금이 그대로 평가금·수익금에 들어갑니다. 입금 기록이 빠진 경우라면 투자금·수익률도 실제와 다릅니다.",
        ),
        USD_FLOW_NO_KRW(
            Level.ERROR, Target.RECORD, "원화 금액이 빠진 달러 입출금",
            "이 기록이 투자금에 0원으로 들어가 투자금·수익금·수익률이 실제와 다르게 보입니다.",
        ),
        ORPHAN_RECORD(
            Level.ERROR, Target.RECORD, "주인 없는 기록",
            "지운 계좌·종목을 가리키는 기록이라 일부 계산(예수금 등)에만 섞이거나 빠져 숫자가 맞지 않을 수 있습니다.",
        ),
        NO_PRICE(
            Level.MISSING, Target.HOLDING, "현재가 없음",
            "이 종목이 평가금에서 빠져 평가금·수익률이 실제보다 낮게 보입니다.",
        ),
        STALE_PRICE(
            Level.MISSING, Target.HOLDING, "오래된 현재가",
            "오래된 가격으로 평가금·수익률을 계산합니다.",
        ),
        NO_FX(
            Level.MISSING, Target.SETTINGS, "환율 없음",
            "달러 예수금·해외 종목이 원화 합계에서 빠져 평가금·수익률이 실제보다 낮게 보입니다.",
        ),
        NO_TRADE_FX(
            Level.MISSING, Target.HOLDING, "매매일 환율 없음",
            "원화 보기에서 환차익을 반영하지 못하고 오늘 환율로 단순 환산합니다.",
        ),
        DUPLICATE(
            Level.REVIEW, Target.RECORD, "중복 의심",
            "같은 기록이 두 번 들어간 것이라면 수량·투자금·예수금이 두 배로 계산됩니다.",
        ),
        EMPTY_HOLDING(
            Level.REVIEW, Target.HOLDING, "기록 없는 종목",
            "계산에는 영향이 없습니다. 0주 종목으로 목록에 남습니다.",
        ),
    }

    /**
     * 점검 항목 하나. [key] 는 무시 목록에 저장하는 값 (종류:대상). 누르면 [Kind.target] 에 따라
     * [recordIds] 의 첫 기록 · [accountId] 계좌 · [holdingId] 종목 · 설정으로 간다.
     */
    data class Issue(
        val key: String,
        val kind: Kind,
        val detail: String,
        val accountId: String? = null,
        val holdingId: String? = null,
        val recordIds: List<String> = emptyList(),
    )

    /** 자동 시세 종목은 7일, 직접 입력 종목은 30일 넘게 지난 가격을 오래된 것으로 본다. */
    const val STALE_QUOTED_DAYS = 7L
    const val STALE_MANUAL_DAYS = 30L

    /** 점검 결과 (단계 → 종류 → 설명 글 → 키 순서). */
    fun check(
        accounts: List<InvestmentAccount>,
        holdings: List<Holding>,
        records: List<Record>,
        summary: PortfolioSummary,
        market: MarketSnapshot,
        today: LocalDate,
    ): List<Issue> {
        val accountName = accounts.associate { it.id to it.name }
        val holdingById = holdings.associateBy { it.id }
        val sorted = records.sortedWith(PortfolioCalculator.recordOrder)
        val out = mutableListOf<Issue>()

        for (s in PortfolioCalculator.shortSells(records)) {
            val r = s.record
            val h = holdingById[r.holdingId]
            val unit = h?.assetType?.unit ?: "주"
            out += Issue(
                "SHORT_SELL:${r.id}", Kind.SHORT_SELL,
                listOfNotNull(
                    accountName[r.accountId], h?.name,
                    "${dateText(r.date)} 매도 ${MoneyFormat.decimal(r.quantity)}$unit (그때 보유 ${MoneyFormat.decimal(s.held)}$unit)",
                ).joinToString(" · "),
                accountId = r.accountId, holdingId = r.holdingId, recordIds = listOf(r.id),
            )
        }

        for (a in summary.accounts) {
            if (a.brokerLinked) continue
            if (a.cash.krw < -0.5) {
                out += Issue("NEGATIVE_CASH:${a.account.id}:KRW", Kind.NEGATIVE_CASH, "${a.account.name} · 원화 ${MoneyFormat.won(a.cash.krw)}", accountId = a.account.id)
            }
            if (a.cash.usd < -0.005) {
                out += Issue("NEGATIVE_CASH:${a.account.id}:USD", Kind.NEGATIVE_CASH, "${a.account.name} · 달러 ${MoneyFormat.usd(a.cash.usd)}", accountId = a.account.id)
            }
        }

        for (r in sorted) {
            val flow = r.type == RecordType.DEPOSIT || r.type == RecordType.WITHDRAW || r.type == RecordType.TRANSFER
            if (flow && r.currency == Currency.USD && r.amount > 0 && r.krwAmount == 0.0) {
                out += Issue(
                    "USD_FLOW_NO_KRW:${r.id}", Kind.USD_FLOW_NO_KRW,
                    listOfNotNull(accountName[r.accountId], "${dateText(r.date)} ${r.type.label} ${MoneyFormat.usd(r.amount)}").joinToString(" · "),
                    accountId = r.accountId, recordIds = listOf(r.id),
                )
            }
            val reason = when {
                r.accountId !in accountName -> "계좌가 없음"
                r.holdingId != null && r.holdingId !in holdingById -> "종목이 없음"
                r.type == RecordType.TRANSFER && r.toAccountId != null && r.toAccountId !in accountName -> "받는 계좌가 없음"
                else -> null
            }
            if (reason != null) {
                out += Issue(
                    "ORPHAN_RECORD:${r.id}", Kind.ORPHAN_RECORD,
                    "${accountName[r.accountId] ?: "지운 계좌"} · ${dateText(r.date)} ${r.type.label} · $reason",
                    accountId = r.accountId.takeIf { it in accountName }, recordIds = listOf(r.id),
                )
            }
        }

        val usdKrw = summary.usdKrw
        for (a in summary.accounts) {
            for (v in a.holdings) {
                if (v.position.quantity <= 0) continue
                val h = v.holding
                val where = "${a.account.name} · ${h.name}"
                val price = v.price
                if (price == null) {
                    out += Issue("NO_PRICE:${h.id}", Kind.NO_PRICE, where, accountId = a.account.id, holdingId = h.id)
                } else if (price.date != null) {
                    val days = ChronoUnit.DAYS.between(price.date, today)
                    val limit = if (MarketKeys.quotable(h)) STALE_QUOTED_DAYS else STALE_MANUAL_DAYS
                    if (days > limit) {
                        out += Issue(
                            "STALE_PRICE:${h.id}:${price.date}", Kind.STALE_PRICE, "$where · ${dateText(price.date)} 가격 (${days}일 전)",
                            accountId = a.account.id, holdingId = h.id,
                        )
                    }
                }
                if (h.currency == Currency.USD && usdKrw != null &&
                    KrwHoldingCalculator.view(v, records, usdKrw) { market.tradeUsdKrw(it, today) } == null
                ) {
                    out += Issue("NO_TRADE_FX:${h.id}", Kind.NO_TRADE_FX, where, accountId = a.account.id, holdingId = h.id)
                }
            }
        }
        if (summary.missingFx) out += Issue("NO_FX", Kind.NO_FX, "달러 금액이 있는데 쓸 환율이 없습니다")

        sorted.filter { it.type != RecordType.CASH_BALANCE }
            .groupBy { listOf(it.accountId, it.type, it.date, it.holdingId, it.toAccountId, it.currency, it.quantity, it.price, it.amount) }
            .values
            .filter { group -> group.size >= 2 && !splitFills(group) }
            .forEach { group ->
                val r = group.first()
                val h = holdingById[r.holdingId]
                val what = if (r.type == RecordType.BUY || r.type == RecordType.SELL) {
                    "${h?.name ?: "종목"} ${MoneyFormat.decimal(r.quantity)}${h?.assetType?.unit ?: "주"} × ${MoneyFormat.amount(r.currency, r.price)}"
                } else {
                    listOfNotNull(h?.name, MoneyFormat.amount(r.currency, r.amount)).joinToString(" ")
                }
                out += Issue(
                    "DUPLICATE:" + group.map { it.id }.sorted().joinToString(","), Kind.DUPLICATE,
                    listOfNotNull(accountName[r.accountId], "${dateText(r.date)} ${r.type.label}", what, "${group.size}건").joinToString(" · "),
                    accountId = r.accountId, holdingId = r.holdingId, recordIds = group.map { it.id },
                )
            }

        val used = records.mapNotNull { it.holdingId }.toSet()
        for (h in holdings) {
            if (h.id in used) continue
            out += Issue(
                "EMPTY_HOLDING:${h.id}", Kind.EMPTY_HOLDING, listOfNotNull(accountName[h.accountId], h.name).joinToString(" · "),
                accountId = h.accountId, holdingId = h.id,
            )
        }

        return out.sortedWith(compareBy<Issue>({ it.kind.ordinal }, { it.detail }, { it.key }))
    }

    /** 홈 알림 개수: 무시하지 않은 🔴·🟠 (⚪ 는 세지 않는다). */
    fun alertCount(issues: List<Issue>, ignored: Set<String>): Int =
        issues.count { it.kind.level != Level.REVIEW && it.key !in ignored }

    /** 한투에서 불러온 분할 체결: 모두 체결번호가 있고 서로 다르면 중복이 아니다. */
    private fun splitFills(group: List<Record>): Boolean {
        val ids = group.map { it.externalId }
        return ids.all { it != null } && ids.toSet().size == ids.size
    }

    /** 설명 글의 날짜: "2026.10.5". */
    fun dateText(date: LocalDate): String = "${date.year}.${date.monthValue}.${date.dayOfMonth}"
}
