package com.mymoneytracker.app.data

import com.mymoneytracker.core.market.MarketClock
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
    /** 체결 내역에 없는 입고로 채운 기록 수 (v2.12). */
    val inboundRecords: Int = 0,
    /** 주문 시각으로 순서를 바로잡은 기존 기록 수 (v2.12). */
    val reorderedRecords: Int = 0,
    /** 한투 원화 예수금 (D+2). */
    val brokerCashKrw: Double?,
    /** 한투 달러 예수금. */
    val brokerCashUsd: Double?,
    /** 예전 버전이 자동으로 만든 예수금 조정 중 지운 개수. */
    val removedCashAdjustments: Int,
    /** 잔고에 함께 온 현재가 (시세 키 → 가격). */
    val currentPrices: Map<String, Double>,
    val warnings: List<String>,
)

/**
 * 한투 연결 계좌의 체결 내역·잔고를 불러와 앱 계좌의 기록으로 저장한다.
 *
 * - 체결 내역 → 매수·매도 기록 (문서 ID 를 주문번호로 정해 중복 저장을 막는다)
 * - 처음 연결할 때는 시작일 이전부터 갖고 있던 수량을 "초기 보유" 기록으로 채운다
 * - 이후에는 잔고 수량과 비교해 미니스탁(소수점)처럼 체결 내역에 안 잡히는 매매를 "수량 맞춤" 으로 채운다
 * - 입금·출금(투자금)은 사용자가 직접 입력한다. 예수금은 한투 값을 돌려주고, 화면 쪽에서 예수금 기록으로 남긴다.
 */
/** 연결 계좌의 예수금과 현재가 (주기적 새로고침용, 체결 내역은 받지 않는다). */
data class BrokerSnapshot(
    val cashKrw: Double?,
    val cashUsd: Double?,
    val prices: Map<String, Double>,
    val warnings: List<String>,
)

class KisSyncService(
    private val kis: KisClient,
    private val repository: PortfolioRepository,
) {
    /** 잔고 조회 2번(국내·해외)으로 실제 예수금과 현재가를 받는다. */
    suspend fun brokerSnapshot(): BrokerSnapshot {
        val warnings = mutableListOf<String>()
        val domestic = kis.domesticBalance()
        val overseas = try {
            kis.overseasPresentBalance()
        } catch (e: ApiException) {
            warnings += "해외 잔고: ${e.message}"
            null
        }
        val prices = (domestic.holdings + overseas?.holdings.orEmpty())
            .mapNotNull { h -> h.currentPrice?.takeIf { it > 0 }?.let { MarketKeys.key(h.market, h.code) to it } }
            .toMap()
        return BrokerSnapshot(domestic.settledCash, overseas?.usdCash, prices, warnings)
    }

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

        // 같은 날 계산 순서 = 주문 시각 (v2.12). 한투는 최신순으로 주므로 받은 순서를 쓰면 매수·매도가 뒤집힐 수 있다.
        val orderTimes = orderTimes(executions)
        val reordered = mutableMapOf<String, Long>()
        for ((index, execution) in executions.withIndex().sortedBy { orderTimes[it.index] }) {
            val id = "kis_${execution.date.toString().replace("-", "")}_${execution.orderNo}_${execution.code}".sanitizeId()
            val createdAt = orderTimes[index]
            if (id in existingIds) {
                // 이미 저장된 한투 기록은 입력 시각만 주문 시각으로 바로잡는다
                records.firstOrNull { it.id == id }?.takeIf { it.createdAt != createdAt }?.let { reordered[id] = createdAt }
                continue
            }
            if (newRecords.any { it.id == id }) continue
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
                createdAt = createdAt,
            )
        }
        reordered.forEach { (id, createdAt) -> repository.setRecordCreatedAt(id, createdAt) }
        val current = records.map { r -> reordered[r.id]?.let { r.copy(createdAt = it) } ?: r }

        // 체결 내역에 없는 입고 (이벤트·이전 등, v2.12): 보유 수량이 모자란 한투 매도 중 같은 날 뒤에 매수가 없는 것 → 그 앞에 0원 입고
        var inbound = 0
        val accountHoldingIds = accountHoldings.map { it.id }.toSet()
        PortfolioCalculator.shortSells((current + newRecords).filter { it.holdingId in accountHoldingIds }).forEach { short ->
            val sell = short.record
            if (sell.accountId != account.id || sell.externalId?.startsWith("KIS:") != true) return@forEach
            if (PortfolioCalculator.buyFirstOrder(current + newRecords, sell) != null) return@forEach
            val id = "kis_in_${sell.id}".sanitizeId()
            val shortfall = sell.quantity - short.held
            if (id in existingIds || shortfall <= QUANTITY_EPSILON) return@forEach
            newRecords += Record(
                id = id,
                accountId = account.id,
                type = RecordType.BUY,
                date = sell.date,
                holdingId = sell.holdingId,
                quantity = shortfall,
                price = 0.0,
                externalId = "KIS:in",
                memo = "한투 체결 내역에 없는 입고 (이벤트·이전 등)",
                createdAt = maxOf(1L, sell.createdAt - 1),
            )
            inbound++
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
            val related = (current + newRecords).filter { it.holdingId == holding.id && it.id != adjustId }
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
            val quantity = PortfolioCalculator.position(holding, (current + newRecords).filter { it.holdingId == holding.id }).quantity
            if (quantity > QUANTITY_EPSILON && (holding.market == Market.KR || overseasBalance != null)) {
                warnings += "${holding.name}: 한투 잔고에는 없는데 앱에는 ${quantity}주가 있습니다. 기록을 확인하세요."
            }
        }

        newRecords.forEach { repository.saveRecord(it) }

        // 예전 버전이 자동으로 만든 예수금 조정 기록은 지운다 (예수금은 이제 한투 값을 그대로 쓴다).
        val legacyCashAdjustments = records.filter { it.accountId == account.id && it.externalId == "KIS:cash" }
        legacyCashAdjustments.forEach { repository.deleteRecord(it.id) }

        return KisSyncResult(
            importedTrades = newRecords.count { it.externalId?.startsWith("KIS:") == true && it.externalId !in setOf("KIS:init", "KIS:adjust", "KIS:in") },
            inboundRecords = inbound,
            reorderedRecords = reordered.size,
            createdHoldings = createdHoldings,
            initialPositions = initialPositions,
            quantityAdjustments = quantityAdjustments,
            brokerCashKrw = domesticBalance.settledCash,
            brokerCashUsd = overseasBalance?.usdCash,
            removedCashAdjustments = legacyCashAdjustments.size,
            currentPrices = (domesticBalance.holdings + overseasBalance?.holdings.orEmpty())
                .mapNotNull { h -> h.currentPrice?.takeIf { it > 0 }?.let { MarketKeys.key(h.market, h.code) to it } }
                .toMap(),
            warnings = warnings,
        )
    }

    /**
     * 체결마다 입력 시각: 주문일 + 주문시각 (한국 시간 기준 밀리초). 주문시각이 같거나 없으면 그날 안에서 오래된 주문(한투 목록의 뒤쪽)부터 차례로.
     */
    private fun orderTimes(executions: List<KisExecution>): List<Long> {
        val inDay = mutableMapOf<LocalDate, Int>()
        val counts = executions.groupingBy { it.date }.eachCount()
        return executions.map { e ->
            val position = inDay.merge(e.date, 1, Int::plus)!! - 1
            val base = e.date.atTime(e.time ?: java.time.LocalTime.MIDNIGHT).atZone(MarketClock.KST).toInstant().toEpochMilli()
            base + (counts.getValue(e.date) - 1 - position)
        }
    }

    private fun String.sanitizeId(): String = replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(140)

    private companion object {
        const val QUANTITY_EPSILON = 1e-6
        val ETF_BRANDS = listOf("KODEX", "TIGER", "ACE", "SOL", "RISE", "KBSTAR", "HANARO", "ARIRANG", "KOSEF", "PLUS", "TIMEFOLIO")
    }
}
