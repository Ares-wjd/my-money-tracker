package com.mymoneytracker.app.ui.goals

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.mymoneytracker.core.MoneyFormat
import com.mymoneytracker.core.model.SavingsSubAccount
import kotlin.math.roundToLong

/** 목적통장 편집 화면의 계좌 한 줄 (입력 중인 문자열 그대로). */
data class SubAccountRow(val name: String = "", val number: String = "", val balance: String = "") {
    fun toSubAccount(index: Int) = SavingsSubAccount(
        name = name.trim().ifBlank { "계좌 ${index + 1}" },
        number = number.trim(),
        balance = MoneyFormat.parseDecimal(balance) ?: 0.0,
    )

    companion object {
        fun from(sub: SavingsSubAccount) = SubAccountRow(
            name = sub.name,
            number = sub.number,
            balance = if (sub.balance == 0.0) "" else sub.balance.roundToLong().toString(),
        )
    }
}

/** 화면 회전 등에도 입력 중인 계좌 목록을 유지한다. */
val subAccountRowsSaver: Saver<SnapshotStateList<SubAccountRow>, Any> = listSaver(
    save = { rows -> rows.flatMap { listOf(it.name, it.number, it.balance) } },
    restore = { flat -> flat.chunked(3).map { SubAccountRow(it[0], it[1], it[2]) }.toMutableStateList() },
)
