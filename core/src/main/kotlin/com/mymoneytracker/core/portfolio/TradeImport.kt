package com.mymoneytracker.core.portfolio

import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import java.time.LocalDate
import kotlin.math.abs

/**
 * 표로 일괄 입력 (매수·매도). 엑셀·구글시트에서 복사한 칸을 빈 표에 붙여 넣고, 줄마다 읽어 기록으로 만든다.
 * 칸: 날짜 / 매매구분 / 종목명 / 종목코드 / 평단가 / 수량 / 제비용합
 */
object TradeImport {
    val HEADERS = listOf("날짜", "매매구분", "종목명", "종목코드", "평단가", "수량", "제비용합")
    const val COLUMNS = 7
    const val MAX_ROWS = 300

    const val DATE = 0
    const val SIDE = 1
    const val NAME = 2
    const val CODE = 3
    const val PRICE = 4
    const val QUANTITY = 5
    const val COST = 6

    private val ETF_BRANDS = listOf("KODEX", "TIGER", "ACE", "SOL", "RISE", "KBSTAR", "HANARO", "ARIRANG", "KOSEF", "PLUS", "TIMEFOLIO", "1Q", "KIWOOM")
    // 표 일괄 입력(매매·입출금·배당)의 제목 줄 글자
    private val HEADER_WORDS = HEADERS + listOf(
        "일자", "거래일", "구분", "종목", "코드", "단가", "가격", "매매가", "비용", "수수료",
        "입출금", "입금", "출금", "금액", "배당", "원화", "외화",
    )

    /** 빈 표 (줄 수만큼 빈 칸, 칸 수는 표마다). */
    fun emptyGrid(rows: Int = 10, columns: Int = COLUMNS): List<List<String>> = List(rows) { List(columns) { "" } }

    /** 붙여 넣은 글 → 줄·칸 (탭·줄바꿈으로 구분, 마지막 빈 줄은 버림, 칸을 감싼 따옴표는 뗀다). */
    fun splitClipboard(text: String): List<List<String>> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        return lines.map { line ->
            line.split('\t').map { cell ->
                val t = cell.trim()
                if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t.substring(1, t.length - 1).replace("\"\"", "\"").trim() else t
            }
        }
    }

    /** 제목 줄인지 (채워진 칸이 모두 제목 글자). */
    fun isHeaderRow(cells: List<String>): Boolean {
        val filled = cells.map { it.trim() }.filter { it.isNotEmpty() }
        return filled.isNotEmpty() && filled.all { cell -> HEADER_WORDS.any { cell.replace(" ", "").contains(it) } } &&
            filled.none { parseNumber(it) != null || parseDate(it) != null }
    }

    /**
     * [row], [col] 칸부터 붙여 넣는다: 오른쪽·아래로 채우고, 줄이 모자라면 늘리고(최대 [MAX_ROWS]), 오른쪽으로 넘치는 칸은 버린다.
     * 제목 줄은 건너뛴다.
     */
    fun paste(grid: List<List<String>>, row: Int, col: Int, text: String, columns: Int = COLUMNS): List<List<String>> {
        val pasted = splitClipboard(text).filterNot { isHeaderRow(it) }
        if (pasted.isEmpty()) return grid
        val result = grid.map { it.toMutableList() }.toMutableList()
        pasted.forEachIndexed { i, cells ->
            val r = row + i
            if (r >= MAX_ROWS) return@forEachIndexed
            while (result.size <= r) result += MutableList(columns) { "" }
            cells.forEachIndexed { j, cell ->
                val c = col + j
                if (c < columns) result[r][c] = cell
            }
        }
        return result
    }

    // ---------------------------------------------------------------- 칸 읽기

    private val DATE_PATTERN = Regex("""^(\d{4})\s*[-./년]\s*(\d{1,2})\s*[-./월]\s*(\d{1,2})\s*[.일]?$""")
    private val COMPACT_DATE = Regex("""^(\d{4})(\d{2})(\d{2})$""")

    fun parseDate(text: String): LocalDate? {
        val t = text.trim()
        val m = DATE_PATTERN.matchEntire(t) ?: COMPACT_DATE.matchEntire(t) ?: return null
        val (y, mo, d) = m.destructured
        return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
    }

    fun parseSide(text: String): RecordType? {
        val t = text.trim().lowercase()
        return when {
            t.contains("매수") || t == "buy" || t == "b" -> RecordType.BUY
            t.contains("매도") || t == "sell" || t == "s" -> RecordType.SELL
            else -> null
        }
    }

    /** 숫자 (쉼표·공백·원·$ 무시, 음수는 -3·−3·(3)). 빈 칸이면 null. */
    fun parseNumber(text: String): Double? {
        var t = text.replace(",", "").replace(" ", "").replace("원", "").replace("$", "").replace("₩", "").replace('−', '-').trim()
        if (t.length >= 3 && t.startsWith("(") && t.endsWith(")")) t = "-" + t.substring(1, t.length - 1)
        if (t.isEmpty() || t.any { it.isLetter() }) return null
        return t.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    // ---------------------------------------------------------------- 분석

    /** 새로 만들 종목 (같은 코드끼리, 코드가 없으면 같은 이름끼리 하나). */
    data class NewHolding(val key: String, val name: String, val code: String, val market: Market, val assetType: AssetType)

    enum class Status { EMPTY, OK, DUPLICATE, ERROR }

    data class Row(
        val index: Int,
        val status: Status,
        val date: LocalDate? = null,
        val type: RecordType? = null,
        val price: Double = 0.0,
        val quantity: Double = 0.0,
        val cost: Double = 0.0,
        /** 이 계좌의 기존 종목 */
        val holding: Holding? = null,
        /** 새 종목 키 ([NewHolding.key]) */
        val newKey: String? = null,
        val error: String? = null,
    ) {
        val saved: Boolean get() = status == Status.OK
    }

    data class Analysis(val rows: List<Row>, val newHoldings: List<NewHolding>) {
        val saveCount: Int get() = rows.count { it.saved }
        val errorRows: List<Row> get() = rows.filter { it.status == Status.ERROR }
        val duplicateCount: Int get() = rows.count { it.status == Status.DUPLICATE }
    }

    fun newKey(name: String, code: String): String =
        code.trim().uppercase().ifEmpty { "이름:" + name.trim() }

    /** 새 종목의 시장 추정: 다른 계좌의 같은 코드 → 숫자로 시작하는 6자리·코드 없음·금 = 국내, 그 외 영문 티커 = 나스닥. */
    fun guessMarket(code: String, all: List<Holding>): Market {
        val c = code.trim().uppercase()
        all.firstOrNull { c.isNotEmpty() && it.code.equals(c, ignoreCase = true) }?.let { return it.market }
        return if (c.isEmpty() || c == AssetType.GOLD_CODE || Regex("^[0-9][0-9A-Z]{5}$").matches(c)) Market.KR else Market.NASDAQ
    }

    /** 새 종목의 자산 유형 추정: 다른 계좌의 같은 코드 → 금 코드 = 금현물, ETF 브랜드 이름 = ETF, 나머지 주식. */
    fun guessAssetType(name: String, code: String, all: List<Holding>): AssetType {
        val c = code.trim().uppercase()
        all.firstOrNull { c.isNotEmpty() && it.code.equals(c, ignoreCase = true) }?.let { return it.assetType }
        val n = name.trim().uppercase()
        return when {
            c == AssetType.GOLD_CODE || n.contains("금현물") -> AssetType.GOLD
            n.contains("ETF") || ETF_BRANDS.any { n.startsWith(it) } -> AssetType.ETF
            else -> AssetType.STOCK
        }
    }

    /** 이 계좌의 종목 찾기: 코드가 같은 종목 → (코드가 비었으면) 이름이 같은 종목. */
    fun matchHolding(name: String, code: String, accountHoldings: List<Holding>): Holding? {
        val c = code.trim()
        if (c.isNotEmpty()) return accountHoldings.firstOrNull { it.code.equals(c, ignoreCase = true) }
        val n = name.trim().replace(" ", "")
        return accountHoldings.firstOrNull { it.name.replace(" ", "").equals(n, ignoreCase = true) }
    }

    /**
     * 표를 읽는다.
     * @param accountHoldings 이 계좌의 종목
     * @param allHoldings 모든 계좌의 종목 (새 종목의 시장·유형 추정용)
     * @param records 이 계좌의 기록 (중복·매도 수량 확인용)
     * @param overrides 표 아래에서 고친 새 종목 (키 → 시장·유형)
     */
    fun analyze(
        grid: List<List<String>>,
        accountId: String,
        accountHoldings: List<Holding>,
        allHoldings: List<Holding>,
        records: List<Record>,
        overrides: Map<String, NewHolding> = emptyMap(),
    ): Analysis {
        val newHoldings = linkedMapOf<String, NewHolding>()
        val parsed = grid.mapIndexed { index, raw ->
            val cells = List(COLUMNS) { raw.getOrNull(it)?.trim().orEmpty() }
            if (cells.all { it.isEmpty() }) return@mapIndexed Row(index, Status.EMPTY)
            val date = parseDate(cells[DATE])
            // 수량 부호: 양수 = 매수, 음수 = 매도. 매매구분은 비워도 되고, 적었으면 부호와 맞아야 한다.
            val side = parseSide(cells[SIDE])
            val name = cells[NAME]
            val code = cells[CODE].uppercase()
            val price = parseNumber(cells[PRICE])
            val signed = parseNumber(cells[QUANTITY])
            val cost = if (cells[COST].isEmpty()) 0.0 else parseNumber(cells[COST])
            val error = when {
                date == null -> "날짜를 읽지 못함"
                cells[SIDE].isNotEmpty() && side == null -> "매매구분은 매수·매도 (비워도 됨)"
                name.isEmpty() && code.isEmpty() -> "종목명이나 종목코드 필요"
                price == null || price < 0 -> "평단가 확인"
                signed == null || signed == 0.0 -> "수량 확인 (매수 +, 매도 −)"
                side == RecordType.BUY && signed < 0 -> "매수인데 수량이 음수"
                side == RecordType.SELL && signed > 0 -> "매도는 수량을 음수로 (예: -3)"
                cost == null || cost < 0 -> "제비용합 확인"
                else -> null
            }
            if (error != null) return@mapIndexed Row(index, Status.ERROR, error = error)
            val type = if (signed!! > 0) RecordType.BUY else RecordType.SELL
            val quantity = abs(signed)
            val holding = matchHolding(name, code, accountHoldings)
            val key = if (holding == null) newKey(name, code) else null
            if (key != null && key !in newHoldings) {
                newHoldings[key] = overrides[key]?.copy(name = name.ifEmpty { code }, code = code)
                    ?: NewHolding(key, name.ifEmpty { code }, code, guessMarket(code, allHoldings), guessAssetType(name, code, allHoldings))
            }
            Row(index, Status.OK, date, type, price!!, quantity, cost!!, holding, key)
        }

        // 중복 의심: 이미 있는 기록과 날짜·종목·구분·수량·단가가 같음
        val withDuplicates = parsed.map { row ->
            val holding = row.holding
            if (row.status != Status.OK || holding == null) return@map row
            val duplicate = records.any {
                it.holdingId == holding.id && it.type == row.type && it.date == row.date &&
                    abs(it.quantity - row.quantity) < 1e-9 && abs(it.price - row.price) < 1e-9
            }
            if (duplicate) row.copy(status = Status.DUPLICATE) else row
        }

        // 매도 수량: 그날 보유 수량 (기존 기록 + 표의 다른 저장할 줄)
        val draft = build(withDuplicates, accountId) { key -> "new:$key" }.map { (i, r) -> r.copy(id = "draft:$i") }
        val allRecords = records + draft
        val rows = withDuplicates.map { row ->
            if (row.status != Status.OK || row.type != RecordType.SELL) return@map row
            val holdingId = row.holding?.id ?: "new:${row.newKey}"
            val held = PortfolioCalculator.quantityAt(
                Holding(id = holdingId, accountId = accountId, name = ""),
                allRecords,
                row.date!!,
                excludeRecordId = "draft:${row.index}",
            )
            if (held + 1e-9 < row.quantity) {
                row.copy(status = Status.ERROR, error = "매도 수량이 그날 보유 수량(${MoneyFormat.decimal(held)})보다 많음")
            } else {
                row
            }
        }
        return Analysis(rows, newHoldings.values.toList())
    }

    /**
     * 저장할 줄을 기록으로 만든다. [holdingIdOf] 는 새 종목 키 → 저장한 종목 id.
     * 돌려주는 값: (줄 번호, 기록). 기록 id 는 비워 둔다 (저장할 때 정해짐).
     */
    fun build(rows: List<Row>, accountId: String, holdingIdOf: (String) -> String?): List<Pair<Int, Record>> =
        rows.filter { it.status == Status.OK }.mapNotNull { row ->
            val holdingId = row.holding?.id ?: row.newKey?.let(holdingIdOf) ?: return@mapNotNull null
            row.index to Record(
                accountId = accountId,
                type = row.type!!,
                date = row.date!!,
                holdingId = holdingId,
                quantity = row.quantity,
                price = row.price,
                cost = row.cost,
            )
        }
}
