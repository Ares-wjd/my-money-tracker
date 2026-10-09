package com.mymoneytracker.app.data

import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

data class KisSyncResult(
    val importedTrades: Int,
    val createdHoldings: Int,
    val initialPositions: Int,
    val cashAdjusted: Double?,
    val warnings: List<String>,
)

/**
 * 한투 연결 계좌의 체결 내역·잔고를 불러와 앱 계좌의 기록으로 저장한다.
 *
 * - 체결 내역 → 매수·매도 기록 (문서 ID 를 주문번호로 정해 중복 저장을 막는다)
 * - 처음 연결할 때는 시작일 이전부터 갖고 있던 수량을 "초기 보유" 기록으로 채운다
 * - 원화 예수금은 한투의 D+2 예수금에 맞춰 "예수금 조정" 기록을 만든다 (예탁금 이용료 등)
 */
class KisSyncService(
    private val kis: KisClient,
    private val repository: PortfolioRepository,
) {
    suspend fun sync(
        account: InvestmentAccount,
        holdings: List<Holding>,
        records: List<Record>,
        startDate: LocalDate,
        lastSync: LocalDate?,
    ): KisSyncResult {
        val today = LocalDate.now()
        val warnings = mutableListOf<String>()
        val from = lastSync?.minusDays(3)?.let { maxOf(it, startDate) } ?: startDate

        val executions = buildList {
            addAll(kis.domesticExecutions(from, today))
            try {
                addAll(kis.overseasExecutions(from, today))
            } catch (e: ApiException) {
                warnings += "해외 체결 내역: ${e.message}"
            }
        }

        val accountHoldings = holdings.filter { it.accountId == account.id }.toMutableList()
        val existingIds = records.map { it.id }.toSet()
        val newRecords = mutableListOf<Record>()
        var createdHoldings = 0

        fun holdingFor(code: String, name: String, market: Market): Holding {
            val key = MarketKeys.key(market, code)
            accountHoldings.firstOrNull { it.code.isNotBlank() && MarketKeys.key(it.market, it.code) == key }?.let { return it }
            val created = Holding(
                accountId = account.id,
                name = name.ifBlank { code },
                code = code.uppercase(),
                market = market,
                assetType = if (name.uppercase().contains("ETF") || ETF_BRANDS.any { name.uppercase().startsWith(it) }) AssetType.ETF else AssetType.STOCK,
            )
            val id = repository.saveHolding(created)
            createdHoldings++
            return created.copy(id = id).also { accountHoldings += it }
        }

        for (execution in executions.sortedBy { it.date }) {
            val id = "kis_${execution.date.toString().replace("-", "")}_${execution.orderNo}_${execution.code}".sanitizeId()
            if (id in existingIds || newRecords.any { it.id == id }) continue
            val holding = holdingFor(execution.code, execution.name, execution.market)
            newRecords += Record(
                id = id,
                accountId = account.id,
                type = if (execution.isBuy) RecordType.BUY else RecordType.SELL,
                date = execution.date,
                holdingId = holding.id,
                quantity = execution.quantity,
                price = execution.price,
                externalId = "KIS:${execution.orderNo}",
                memo = "한투에서 불러옴",
                createdAt = System.currentTimeMillis(),
            )
        }

        // 처음 연결: 시작일 이전부터 보유하던 수량을 초기 보유로 채운다.
        var initialPositions = 0
        val domesticBalance = kis.domesticBalance()
        if (lastSync == null) {
            val balanceHoldings = domesticBalance.holdings + try {
                kis.overseasBalance()
            } catch (e: ApiException) {
                warnings += "해외 잔고: ${e.message}"
                emptyList()
            }
            for (real in balanceHoldings) {
                val holding = holdingFor(real.code, real.name, real.market)
                val appQuantity = PortfolioCalculator.position(holding, (records + newRecords).filter { it.holdingId == holding.id }).quantity
                val missing = real.quantity - appQuantity
                if (missing > 1e-9) {
                    newRecords += Record(
                        id = "kis_init_${holding.id}".sanitizeId(),
                        accountId = account.id,
                        type = RecordType.BUY,
                        date = startDate.minusDays(1),
                        holdingId = holding.id,
                        quantity = missing,
                        price = real.averagePrice,
                        initial = true,
                        externalId = "KIS:init",
                        memo = "한투 잔고 기준 초기 보유 (평균단가 기준)",
                        createdAt = 0L,
                    )
                    initialPositions++
                } else if (missing < -1e-9) {
                    warnings += "${holding.name}: 앱 수량이 한투 잔고보다 ${-missing}주 많습니다. 기록을 확인하세요."
                }
            }
        }

        newRecords.forEach { repository.saveRecord(it) }

        // 원화 예수금 맞추기 (같은 날 다시 불러오면 그날의 조정 기록을 덮어쓴다)
        var cashAdjusted: Double? = null
        domesticBalance.settledCash?.let { realCash ->
            val adjustId = "kis_cash_${today.toString().replace("-", "")}_${account.id}".sanitizeId()
            val allRecords = (records + newRecords).filter { it.id != adjustId }
            val summary = PortfolioCalculator.summarize(listOf(account), accountHoldings, allRecords, usdKrw = null)
            val diff = realCash - summary.accounts.single().cash.krw
            if (abs(diff) >= 1) {
                repository.saveRecord(
                    Record(
                        id = adjustId,
                        accountId = account.id,
                        type = RecordType.CASH_ADJUST,
                        date = today,
                        currency = Currency.KRW,
                        amount = diff.roundToLong().toDouble(),
                        externalId = "KIS:cash",
                        memo = "한투 예수금 자동 맞춤 (예탁금 이용료·수수료·세금 등)",
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                cashAdjusted = diff
            }
        }

        return KisSyncResult(
            importedTrades = newRecords.count { it.type != RecordType.BUY || !it.initial },
            createdHoldings = createdHoldings,
            initialPositions = initialPositions,
            cashAdjusted = cashAdjusted,
            warnings = warnings,
        )
    }

    private fun String.sanitizeId(): String = replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(140)

    private companion object {
        val ETF_BRANDS = listOf("KODEX", "TIGER", "ACE", "SOL", "RISE", "KBSTAR", "HANARO", "ARIRANG", "KOSEF", "PLUS", "TIMEFOLIO")
    }
}
