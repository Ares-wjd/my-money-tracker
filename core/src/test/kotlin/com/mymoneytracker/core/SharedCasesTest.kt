package com.mymoneytracker.core

import com.mymoneytracker.core.market.MarketSnapshot
import com.mymoneytracker.core.market.PriceHistory
import com.mymoneytracker.core.market.PriceLookup
import com.mymoneytracker.core.model.AccountKind
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.PricePoint
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.FlowPoint
import com.mymoneytracker.core.portfolio.PeriodReturns
import com.mymoneytracker.core.portfolio.PortfolioCalculator
import com.mymoneytracker.core.portfolio.TodayChangeCalculator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.max

/**
 * 공통 테스트 케이스 (testdata 폴더의 json 파일, 웹 sharedCases.test.ts 와 같은 파일): 같은 입력에 폰과 PC 가 같은 숫자를 내는지.
 * 입력은 Firestore 문서 모양 그대로, 기대 결과는 있는 항목만 검사한다. 형식은 testdata/README.md.
 */
class SharedCasesTest {
    private val root = File("../testdata")

    private fun files(dir: String): List<File> =
        File(root, dir).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    @Test
    fun casesExist() {
        assertTrue("testdata/portfolio 가 비었음", files("portfolio").isNotEmpty())
        assertTrue("testdata/period 가 비었음", files("period").isNotEmpty())
    }

    @Test
    fun portfolioCases() {
        val problems = mutableListOf<String>()
        for (file in files("portfolio")) problems += checkPortfolio(file.name, JSONObject(file.readText()))
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun periodCases() {
        val problems = mutableListOf<String>()
        for (file in files("period")) problems += checkPeriod(file.name, JSONObject(file.readText()))
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    // ------------------------------------------------------------ 비교

    private class Checker(val file: String) {
        val problems = mutableListOf<String>()

        fun num(path: String, expected: Any?, actual: Double?) {
            val e = if (expected == null || expected == JSONObject.NULL) null else (expected as Number).toDouble()
            when {
                e == null && actual == null -> Unit
                e == null || actual == null -> problems += "$file · $path: 기대 $e, 실제 $actual"
                abs(e - actual) > max(1e-6, 1e-9 * abs(e)) -> problems += "$file · $path: 기대 $e, 실제 $actual"
            }
        }

        fun text(path: String, expected: Any?, actual: String?) {
            val e = if (expected == null || expected == JSONObject.NULL) null else expected.toString()
            if (e != actual) problems += "$file · $path: 기대 $e, 실제 $actual"
        }
    }

    private fun JSONObject.opt2(key: String): Any? = if (has(key)) get(key) else null

    // ------------------------------------------------------------ 입력 (Firestore 문서 모양)

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private fun JSONObject.docs(): List<Pair<String, JSONObject>> = keys().asSequence().map { it to getJSONObject(it) }.toList()

    private fun accounts(o: JSONObject) = o.docs().map { (id, d) ->
        InvestmentAccount(id, d.getString("name"), enumOf(d.str("kind"), AccountKind.GENERAL), d.str("number").orEmpty(), d.str("memo").orEmpty(), d.optLong("createdAt"))
    }

    private fun holdings(o: JSONObject) = o.docs().map { (id, d) ->
        Holding(
            id, d.getString("accountId"), d.str("name").orEmpty(), d.str("code").orEmpty(),
            enumOf(d.str("market"), Market.KR), enumOf(d.str("assetType"), AssetType.STOCK),
            d.dbl("manualPrice"), d.str("manualPriceDate")?.let(LocalDate::parse), d.optLong("createdAt"),
        )
    }

    private fun records(o: JSONObject) = o.docs().map { (id, d) ->
        Record(
            id = id,
            accountId = d.getString("accountId"),
            type = enumOf(d.str("type"), RecordType.DEPOSIT),
            date = LocalDate.parse(d.getString("date")),
            currency = enumOf(d.str("currency"), Currency.KRW),
            amount = d.dbl("amount") ?: 0.0,
            krwAmount = d.dbl("krwAmount") ?: 0.0,
            toAccountId = d.str("toAccountId"),
            holdingId = d.str("holdingId"),
            quantity = d.dbl("quantity") ?: 0.0,
            price = d.dbl("price") ?: 0.0,
            fee = d.dbl("fee") ?: 0.0,
            tax = d.dbl("tax") ?: 0.0,
            cost = d.dbl("cost"),
            initial = d.optBoolean("initial"),
            externalId = d.str("externalId"),
            memo = d.str("memo").orEmpty(),
            createdAt = d.optLong("createdAt"),
        )
    }

    private fun dateMap(o: JSONObject?): TreeMap<LocalDate, Double> {
        val out = TreeMap<LocalDate, Double>()
        o?.keys()?.forEach { k -> runCatching { out[LocalDate.parse(k)] = o.getDouble(k) } }
        return out
    }

    /** marketData 문서들 → 시세 스냅샷 (폰 MarketDataRepository 와 같은 읽기: fx_USD 위에 fx_USD_checked 를 덮는다). */
    private fun market(o: JSONObject?): MarketSnapshot {
        o ?: return MarketSnapshot()
        val prices = mutableMapOf<String, PriceHistory>()
        var recent = TreeMap<LocalDate, Double>()
        var checked = TreeMap<LocalDate, Double>()
        var latest: Pair<LocalDate, Double>? = null
        var source: String? = null
        for ((id, d) in o.docs()) {
            when {
                id == "fx_USD" -> recent = dateMap(d.optJSONObject("rates"))
                id == "fx_USD_checked" -> {
                    checked = dateMap(d.optJSONObject("rates"))
                    val rate = d.dbl("latest")
                    val date = d.str("latestDate")?.let(LocalDate::parse)
                    latest = if (rate != null && date != null) date to rate else null
                    source = d.str("latestSource")
                }
                id.startsWith("price_") -> prices[id.removePrefix("price_")] = PriceHistory(
                    dateMap(d.optJSONObject("closes")),
                    d.dbl("latest")?.let { PricePoint(it, d.str("latestDate")?.let(LocalDate::parse)) },
                    d.dbl("prevClose"),
                )
            }
        }
        return MarketSnapshot(prices, TreeMap(recent).apply { putAll(checked) }, latest, source)
    }

    // ------------------------------------------------------------ 검사

    private fun checkPortfolio(file: String, case: JSONObject): List<String> {
        val c = Checker(file)
        val today = LocalDate.parse(case.getString("today"))
        val accounts = accounts(case.getJSONObject("accounts"))
        val holdings = holdings(case.optJSONObject("holdings") ?: JSONObject())
        val records = records(case.optJSONObject("records") ?: JSONObject())
        val snapshot = market(case.optJSONObject("market"))
        val usdKrw = snapshot.latestUsdKrw()?.second ?: case.dbl("manualUsdKrw")
        val summary = PortfolioCalculator.summarize(accounts, holdings, records, usdKrw, priceOf = { PriceLookup.current(it, snapshot) })
        val expect = case.getJSONObject("expect")

        expect.optJSONObject("summary")?.let { e ->
            c.num("summary.investedKrw", e.opt2("investedKrw"), summary.investedKrw)
            c.num("summary.valueKrw", e.opt2("valueKrw"), summary.valueKrw)
            c.num("summary.profitKrw", e.opt2("profitKrw"), summary.profitKrw)
            if (e.has("returnRate")) c.num("summary.returnRate", e.get("returnRate"), summary.returnRate)
            if (e.has("returnRateExDividends")) c.num("summary.returnRateExDividends", e.get("returnRateExDividends"), summary.returnRateExDividends)
            if (e.has("dividendsKrw")) c.num("summary.dividendsKrw", e.get("dividendsKrw"), summary.dividendsKrw)
        }
        expect.optJSONObject("accounts")?.let { all ->
            for ((id, e) in all.docs()) {
                val a = summary.accounts.firstOrNull { it.account.id == id }
                if (a == null) {
                    c.problems += "$file · accounts.$id: 계좌 없음"
                    continue
                }
                val p = "accounts.$id"
                if (e.has("investedKrw")) c.num("$p.investedKrw", e.get("investedKrw"), a.investedKrw)
                if (e.has("valueKrw")) c.num("$p.valueKrw", e.get("valueKrw"), a.valueKrw)
                if (e.has("profitKrw")) c.num("$p.profitKrw", e.get("profitKrw"), a.profitKrw)
                if (e.has("returnRate")) c.num("$p.returnRate", e.get("returnRate"), a.returnRate)
                if (e.has("cashKrw")) c.num("$p.cashKrw", e.get("cashKrw"), a.cash.krw)
                if (e.has("cashUsd")) c.num("$p.cashUsd", e.get("cashUsd"), a.cash.usd)
                if (e.has("noRateReason")) c.text("$p.noRateReason", e.get("noRateReason"), PortfolioCalculator.noRateReason(id, a.investedKrw, records)?.name)
            }
        }
        expect.optJSONObject("positions")?.let { all ->
            for ((id, e) in all.docs()) {
                val v = summary.accounts.flatMap { it.holdings }.firstOrNull { it.holding.id == id }
                if (v == null) {
                    c.problems += "$file · positions.$id: 종목 없음"
                    continue
                }
                val p = "positions.$id"
                if (e.has("quantity")) c.num("$p.quantity", e.get("quantity"), v.position.quantity)
                if (e.has("averagePrice")) c.num("$p.averagePrice", e.get("averagePrice"), v.position.averagePrice)
                if (e.has("realizedProfit")) c.num("$p.realizedProfit", e.get("realizedProfit"), v.position.realizedProfit)
                if (e.has("dividends")) c.num("$p.dividends", e.get("dividends"), v.position.dividends)
            }
        }
        expect.optJSONObject("cumulativeInvested")?.let { all ->
            for ((accountId, e) in all.docs()) {
                val actual = PortfolioCalculator.cumulativeInvested(accountId, records)
                c.text("cumulativeInvested.$accountId 기록", e.keys().asSequence().toSortedSet().toString(), actual.keys.toSortedSet().toString())
                for (recordId in e.keys()) c.num("cumulativeInvested.$accountId.$recordId", e.get(recordId), actual[recordId])
            }
        }
        expect.optJSONArray("shortSells")?.let { e ->
            val actual = PortfolioCalculator.shortSells(records)
            c.text("shortSells 기록", (0 until e.length()).map { e.getJSONObject(it).getString("id") }.toString(), actual.map { it.record.id }.toString())
            actual.zip((0 until e.length()).map { e.getJSONObject(it) }).forEach { (s, x) -> c.num("shortSells.${s.record.id}.held", x.get("held"), s.held) }
        }
        if (expect.has("todayChange")) {
            val t = TodayChangeCalculator.calculate(summary, snapshot, today)
            if (expect.isNull("todayChange")) {
                if (t != null) c.problems += "$file · todayChange: 기대 null, 실제 ${t.totalKrw}"
            } else {
                val e = expect.getJSONObject("todayChange")
                if (t == null) {
                    c.problems += "$file · todayChange: 기대 값, 실제 null"
                } else {
                    c.num("todayChange.totalKrw", e.opt2("totalKrw"), t.totalKrw)
                    if (e.has("rate")) c.num("todayChange.rate", e.get("rate"), t.rate)
                    if (e.has("domesticKrw")) c.num("todayChange.domesticKrw", e.get("domesticKrw"), t.domesticKrw)
                    if (e.has("foreignKrw")) c.num("todayChange.foreignKrw", e.get("foreignKrw"), t.foreignKrw)
                    if (e.has("fxKrw")) c.num("todayChange.fxKrw", e.get("fxKrw"), t.fxKrw)
                    if (e.has("fxFrom")) c.num("todayChange.fxFrom", e.get("fxFrom"), t.fxFrom)
                    if (e.has("fxTo")) c.num("todayChange.fxTo", e.get("fxTo"), t.fxTo)
                    e.optJSONObject("accounts")?.let { ea ->
                        c.text("todayChange.accounts 계좌", ea.keys().asSequence().toSortedSet().toString(), t.accounts.map { it.account.id }.toSortedSet().toString())
                        for (id in ea.keys()) c.num("todayChange.accounts.$id", ea.get(id), t.accounts.firstOrNull { it.account.id == id }?.changeKrw)
                    }
                }
            }
        }
        return c.problems
    }

    private fun checkPeriod(file: String, case: JSONObject): List<String> {
        val c = Checker(file)
        val today = LocalDate.parse(case.getString("today"))
        val arr: JSONArray = case.getJSONArray("series")
        val series = (0 until arr.length()).map { i ->
            val p = arr.getJSONArray(i)
            FlowPoint(LocalDate.parse(p.getString(0)), p.getDouble(1), p.getDouble(2))
        }
        val expect = case.getJSONObject("expect")
        val ytd = PeriodReturns.yearToDate(series, today)
        val long = PeriodReturns.longTerm(series, today)
        expect.optJSONObject("ytd")?.let { e ->
            c.num("ytd.profitKrw", e.opt2("profitKrw"), ytd?.profitKrw)
            if (e.has("rate")) c.num("ytd.rate", e.get("rate"), ytd?.rate)
            if (e.has("netFlowKrw")) c.num("ytd.netFlowKrw", e.get("netFlowKrw"), ytd?.netFlowKrw)
        }
        expect.optJSONObject("long")?.let { e ->
            if (e.has("annual")) c.num("long.annual", e.get("annual"), long?.annual)
            if (e.has("period")) c.num("long.period", e.get("period"), long?.period)
            if (e.has("days")) c.num("long.days", e.get("days"), long?.days?.toDouble())
        }
        expect.optJSONObject("months")?.let { e ->
            val items = e.getJSONArray("items")
            val actual = PeriodReturns.months(series, today, e.getInt("count"))
            c.text("months 시작일", (0 until items.length()).map { items.getJSONObject(it).getString("from") }.toString(), actual.map { it.from.toString() }.toString())
            actual.zip((0 until items.length()).map { items.getJSONObject(it) }).forEach { (m, x) ->
                c.num("months.${m.from}.profitKrw", x.opt2("profitKrw"), m.profitKrw)
                if (x.has("rate")) c.num("months.${m.from}.rate", x.get("rate"), m.rate)
            }
        }
        expect.optJSONArray("years")?.let { e ->
            val actual = PeriodReturns.years(series, today)
            c.text("years 시작일", (0 until e.length()).map { e.getJSONObject(it).getString("from") }.toString(), actual.map { it.from.toString() }.toString())
            actual.zip((0 until e.length()).map { e.getJSONObject(it) }).forEach { (y, x) -> c.num("years.${y.from}.profitKrw", x.opt2("profitKrw"), y.profitKrw) }
        }
        if (expect.has("summaryText")) c.text("summaryText", expect.get("summaryText"), PeriodReturns.summaryText(ytd, long))
        return c.problems
    }
}
