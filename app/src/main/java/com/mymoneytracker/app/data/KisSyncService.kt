package com.mymoneytracker.app.data

import com.mymoneytracker.core.market.MarketKeys
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import java.time.LocalDate
import kotlin.math.abs

data class KisSyncResult(
    val importedTrades: Int,
    val createdHoldings: Int,
    val initialPositions: Int,
    val quantityAdjustments: Int,
    /** 한투 원화 예수금 (D+2). */
    val brokerCashKrw: Double?,
    /** 한투 달러 예수금. */
    val brokerCashUsd: Double?,
    /** 예전 버전이 자동으로 만든 예수금 조정 중 지운 개수. */
    val removedCashAdjustments: Int,
    val warnings: List<String>,
)

/**
 * 한투 연결 계좌의 체결 내역·잔고를 불러와 앱 계좌의 기록으로 저장한다.
 *
 * - 체결 내역 → 매수·매도 기록 (문서 ID 를 주문번호로 정해 중복 저장을 막는다)
 * - 처음 연결할 때는 시작일 이전부터 갖고 있던 수량을 "초기 보유" 기록으로 채운다
 * - 이후에는 잔고 수량과 비교해 미니스탁(소수점)처럼 체결 내역에 안 잡히는 매매를 "수량 맞춤" 으로 채운다
 * - 입금·출금(투자금)은 사용자가 직접 입력한다. 예수금은 자동으로 맞추지 않고 한투 값만 돌려준다.
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

        // 잔고 수량 맞추기
        // - 처음 연결: 시작일 이전부터 보유하던 수량을 "초기 보유" 로 채운다.
        // - 이후: 체결 내역으로 잡히지 않는 미니스탁(소수점) 매매·누락분을 오늘 날짜 "수량 맞춤" 으로 채운다.
        var initialPositions = 0
        var quantityAdjustments = 0
        val todayKey = today.toString().replace("-", "")
        val domesticBalance = kis.domesticBalance()
        val overseasBalance = try {
            kis.overseasPresentBalance()
        } catch (e: ApiException) {
            warnings += "해외 잔고: ${e.message}"
            null
        }
        for (real in domesticBalance.holdings + overseasBalance?.holdings.orEmpty()) {
            val holding = holdingFor(real.code, real.name, real.market)
            val adjustId = "kis_adj_${todayKey}_${holding.id}".sanitizeId()
            val related = (records + newRecords).filter { it.holdingId == holding.id && it.id != adjustId }
            val position = PortfolioCalculator.position(holding, related)
            val missing = real.quantity - position.quantity
            if (kotlin.math.abs(missing) <= QUANTITY_EPSILON) continue

            if (lastSync == null && missing > 0) {
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
            } else if (lastSync == null) {
                warnings += "${holding.name}: 앱 수량이 한투 잔고보다 ${-missing}주 많습니다. 기록을 확인하세요."
            } else if (missing > 0) {
                // 앱의 평균단가가 한투 평균단가와 같아지도록 단가를 정한다.
                val cost = real.quantity * real.averagePrice - position.quantity * position.averagePrice
                newRecords += Record(
                    id = adjustId,
                    accountId = account.id,
                    type = RecordType.BUY,
                    date = today,
                    holdingId = holding.id,
                    quantity = missing,
                    price = (cost / missing).takeIf { it > 0 } ?: real.averagePrice,
                    externalId = "KIS:adjust",
                    memo = "한투 잔고 기준 수량 맞춤 (소수점·누락 체결)",
                    createdAt = System.currentTimeMillis(),
                )
                quantityAdjustments++
            } else {
                newRecords += Record(
                    id = adjustId,
                    accountId = account.id,
                    type = RecordType.SELL,
                    date = today,
                    holdingId = holding.id,
                    quantity = -missing,
                    price = real.currentPrice?.takeIf { it > 0 } ?: position.averagePrice,
                    externalId = "KIS:adjust",
                    memo = "한투 잔고 기준 수량 맞춤 (소수점·누락 체결)",
                    createdAt = System.currentTimeMillis(),
                )
                quantityAdjustments++
            }
        }
        val balanceKeys = (domesticBalance.holdings + overseasBalance?.holdings.orEmpty())
            .map { MarketKeys.key(it.market, it.code) }.toSet()
        accountHoldings.filter { it.code.isNotBlank() && MarketKeys.key(it.market, it.code) !in balanceKeys }.forEach { holding ->
            val quantity = PortfolioCalculator.position(holding, (records + newRecords).filter { it.holdingId == holding.id }).quantity
            if (quantity > QUANTITY_EPSILON && (holding.market == Market.KR || overseasBalance != null)) {
                warnings += "${holding.name}: 한투 잔고에는 없는데 앱에는 ${quantity}주가 있습니다. 기록을 확인하세요."
            }
        }

        newRecords.forEach { repository.saveRecord(it) }

        // 예수금은 자동으로 맞추지 않는다. 기록 안 된 입출금이 "수익" 으로 섞이지 않도록,
        // 한투 예수금만 돌려주고 차이는 화면에서 보여준다 (입출금은 사용자가 직접 입력).
        // 예전 버전이 자동으로 만든 예수금 조정 기록은 지운다.
        val legacyCashAdjustments = records.filter { it.accountId == account.id && it.externalId == "KIS:cash" }
        legacyCashAdjustments.forEach { repository.deleteRecord(it.id) }

        return KisSyncResult(
            importedTrades = newRecords.count { it.externalId?.startsWith("KIS:") == true && it.externalId !in setOf("KIS:init", "KIS:adjust") },
            createdHoldings = createdHoldings,
            initialPositions = initialPositions,
            quantityAdjustments = quantityAdjustments,
            brokerCashKrw = domesticBalance.settledCash,
            brokerCashUsd = overseasBalance?.usdCash,
            removedCashAdjustments = legacyCashAdjustments.size,
            warnings = warnings,
        )
    }

    private fun String.sanitizeId(): String = replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(140)

    private companion object {
        const val QUANTITY_EPSILON = 1e-6
        val ETF_BRANDS = listOf("KODEX", "TIGER", "ACE", "SOL", "RISE", "KBSTAR", "HANARO", "ARIRANG", "KOSEF", "PLUS", "TIMEFOLIO")
    }
}
