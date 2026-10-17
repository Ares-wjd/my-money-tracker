package com.mymoneytracker.app.ui

import android.content.Context
import com.mymoneytracker.core.model.RecordType
import com.mymoneytracker.core.portfolio.DisplayCurrency
import com.mymoneytracker.core.portfolio.HoldingSort
import com.mymoneytracker.core.portfolio.TodayFilter
import com.mymoneytracker.core.portfolio.TodayKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** 이 기기에만 기억하는 보기 설정 (보기 통화·정렬·비중 탭·기록 필터·오늘 변동 필터)과 수출입은행 하루 호출 수. */
class DisplayPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE)

    /** 해외 종목 금액 보기 (원화 / 외화). */
    private val _displayCurrency = MutableStateFlow(
        DisplayCurrency.entries.firstOrNull { it.name == prefs.getString(KEY_DISPLAY_CURRENCY, null) } ?: DisplayCurrency.FOREIGN,
    )
    val displayCurrency: StateFlow<DisplayCurrency> = _displayCurrency.asStateFlow()

    fun setDisplayCurrency(mode: DisplayCurrency) {
        _displayCurrency.value = mode
        prefs.edit().putString(KEY_DISPLAY_CURRENCY, mode.name).apply()
    }

    /** 보유 종목 정렬 기준 (평가금 / 총 수익률 / 오늘 수익률). */
    private val _holdingSort = MutableStateFlow(
        HoldingSort.entries.firstOrNull { it.name == prefs.getString(KEY_HOLDING_SORT, null) } ?: HoldingSort.VALUE,
    )
    val holdingSort: StateFlow<HoldingSort> = _holdingSort.asStateFlow()

    fun setHoldingSort(sort: HoldingSort) {
        _holdingSort.value = sort
        prefs.edit().putString(KEY_HOLDING_SORT, sort.name).apply()
    }

    /** 비중 탭에서 마지막으로 본 설정·도넛 보기. */
    private val _selectedAllocation = MutableStateFlow(prefs.getString(KEY_ALLOCATION, null))
    val selectedAllocation: StateFlow<String?> = _selectedAllocation.asStateFlow()

    fun selectAllocation(id: String) {
        _selectedAllocation.value = id
        prefs.edit().putString(KEY_ALLOCATION, id).apply()
    }

    private val _allocationRing = MutableStateFlow(prefs.getString(KEY_ALLOCATION_RING, null) ?: "COMPARE")
    val allocationRing: StateFlow<String> = _allocationRing.asStateFlow()

    fun setAllocationRing(ring: String) {
        _allocationRing.value = ring
        prefs.edit().putString(KEY_ALLOCATION_RING, ring).apply()
    }

    /** 비중 매수 계산기에서 현재 예수금도 함께 쓸지 (기본 켬). */
    private val _allocationUseCash = MutableStateFlow(prefs.getBoolean(KEY_ALLOCATION_USE_CASH, true))
    val allocationUseCash: StateFlow<Boolean> = _allocationUseCash.asStateFlow()

    fun setAllocationUseCash(use: Boolean) {
        _allocationUseCash.value = use
        prefs.edit().putBoolean(KEY_ALLOCATION_USE_CASH, use).apply()
    }

    /** 계좌별 기록 유형 필터 (뺀 유형, 기본은 전부 보임). */
    private val _recordHidden = MutableStateFlow(
        prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_RECORD_HIDDEN)) return@mapNotNull null
            val types = (value as? String).orEmpty().split(",").mapNotNull { name -> RecordType.entries.firstOrNull { it.name == name } }.toSet()
            key.removePrefix(KEY_RECORD_HIDDEN) to types
        }.toMap(),
    )
    val recordHidden: StateFlow<Map<String, Set<RecordType>>> = _recordHidden.asStateFlow()

    fun setRecordHidden(accountId: String, hidden: Set<RecordType>) {
        _recordHidden.value = _recordHidden.value + (accountId to hidden)
        prefs.edit().putString(KEY_RECORD_HIDDEN + accountId, hidden.joinToString(",") { it.name }).apply()
    }

    /** 오늘 변동 상세 화면의 필터 (뺀 계좌·종류, 기본은 전부 보임). */
    private val _todayFilter = MutableStateFlow(
        TodayFilter(
            hiddenAccounts = prefs.getString(KEY_TODAY_HIDDEN_ACCOUNTS, null).orEmpty().split(",").filter { it.isNotEmpty() }.toSet(),
            hiddenKinds = prefs.getString(KEY_TODAY_HIDDEN_KINDS, null).orEmpty().split(",")
                .mapNotNull { name -> TodayKind.entries.firstOrNull { it.name == name } }.toSet(),
        ),
    )
    val todayFilter: StateFlow<TodayFilter> = _todayFilter.asStateFlow()

    fun setTodayFilter(filter: TodayFilter) {
        _todayFilter.value = filter
        prefs.edit()
            .putString(KEY_TODAY_HIDDEN_ACCOUNTS, filter.hiddenAccounts.joinToString(","))
            .putString(KEY_TODAY_HIDDEN_KINDS, filter.hiddenKinds.joinToString(",") { it.name })
            .apply()
    }

    /** 오늘 과거 환율 채우기에 더 쓸 수 있는 수출입은행 호출 수 (하루 1,000회 한도 중 [FX_DAILY_BUDGET]). */
    fun fxBudgetLeft(): Int = (FX_DAILY_BUDGET - fxCallsToday()).coerceAtLeast(0)

    fun spendFxBudget(calls: Int) {
        if (calls <= 0) return
        prefs.edit().putString(KEY_FX_DAY, LocalDate.now().toString()).putInt(KEY_FX_CALLS, fxCallsToday() + calls).apply()
    }

    private fun fxCallsToday(): Int =
        if (prefs.getString(KEY_FX_DAY, null) == LocalDate.now().toString()) prefs.getInt(KEY_FX_CALLS, 0) else 0

    private companion object {
        const val KEY_DISPLAY_CURRENCY = "holding_currency"
        const val KEY_HOLDING_SORT = "holding_sort"
        const val KEY_ALLOCATION = "allocation_selected"
        const val KEY_ALLOCATION_RING = "allocation_ring"
        const val KEY_ALLOCATION_USE_CASH = "allocation_use_cash"
        const val KEY_RECORD_HIDDEN = "record_hidden_"
        const val KEY_TODAY_HIDDEN_ACCOUNTS = "today_hidden_accounts"
        const val KEY_TODAY_HIDDEN_KINDS = "today_hidden_kinds"
        const val KEY_FX_DAY = "fx_backfill_day"
        const val KEY_FX_CALLS = "fx_backfill_calls"
        /** 과거 환율 채우기에 하루에 쓸 수 있는 수출입은행 호출 수 (한도 1,000회 중 나머지는 최신·매매일 환율용). */
        const val FX_DAILY_BUDGET = 600
    }
}
