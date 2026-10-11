package com.mymoneytracker.app.data

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.mymoneytracker.core.model.GoalType
import com.mymoneytracker.core.model.SavingsAccount
import com.mymoneytracker.core.model.SavingsGoal
import com.mymoneytracker.core.model.SavingsGoalGroup
import com.mymoneytracker.core.model.SavingsSubAccount
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.LocalDate

/**
 * 목적통장 데이터. 투자 자산과 컬렉션부터 분리한다.
 *
 * users/{uid}/savingsAccounts/{id}
 * users/{uid}/savingsGoals/{id}   (accountId 로 통장과 연결)
 * users/{uid}/savingsGoalGroups/{id} (목표 그룹, 목표의 groupId 로 연결)
 */
class SavingsRepository(
    private val db: FirebaseFirestore,
    uid: String,
    private val onError: (String) -> Unit,
) {
    private val userDoc = db.collection("users").document(uid)
    private val accountsCol = userDoc.collection("savingsAccounts")
    private val goalsCol = userDoc.collection("savingsGoals")
    private val groupsCol = userDoc.collection("savingsGoalGroups")

    fun accounts(): Flow<List<SavingsAccount>> = accountsCol.orderBy(F_CREATED_AT).listen { it.toAccount() }

    fun goals(): Flow<List<SavingsGoal>> = goalsCol.orderBy(F_CREATED_AT).listen { it.toGoal() }

    fun groups(): Flow<List<SavingsGoalGroup>> = groupsCol.orderBy(F_CREATED_AT).listen { it.toGroup() }

    fun saveAccount(account: SavingsAccount): String {
        val doc = if (account.id.isEmpty()) accountsCol.document() else accountsCol.document(account.id)
        doc.set(
            mapOf(
                "name" to account.name,
                "subAccounts" to account.subAccounts.map {
                    mapOf("name" to it.name, "number" to it.number, "balance" to it.balance)
                },
                // 예전 버전·조회 편의를 위해 합계도 함께 저장한다.
                "balance" to account.balance,
                "balanceDate" to account.balanceDate?.toString(),
                "annualRate" to account.annualRate,
                "memo" to account.memo,
                F_CREATED_AT to if (account.createdAt > 0) account.createdAt else System.currentTimeMillis(),
            ),
        ).logFailure("목적통장 저장")
        return doc.id
    }

    fun deleteAccount(accountId: String, goals: List<SavingsGoal>, groups: List<SavingsGoalGroup>) {
        val batch = db.batch()
        batch.delete(accountsCol.document(accountId))
        goals.filter { it.accountId == accountId }.forEach { batch.delete(goalsCol.document(it.id)) }
        groups.filter { it.accountId == accountId }.forEach { batch.delete(groupsCol.document(it.id)) }
        batch.commit().logFailure("목적통장 삭제")
    }

    fun saveGoal(goal: SavingsGoal) {
        val doc = if (goal.id.isEmpty()) goalsCol.document() else goalsCol.document(goal.id)
        doc.set(goalData(goal)).logFailure("목표 저장")
    }

    /** 여러 목표를 한 번에 저장 (그룹 구매 완료·되돌리기). */
    fun saveGoals(goals: List<SavingsGoal>) {
        val batch = db.batch()
        goals.forEach { batch.set(goalsCol.document(it.id), goalData(it)) }
        batch.commit().logFailure("목표 저장")
    }

    /** 그룹을 저장하고 [memberIds] 목표는 이 그룹으로, 원래 이 그룹이던 나머지는 그룹에서 뺀다 (한 번에). */
    fun saveGroup(group: SavingsGoalGroup, memberIds: Set<String>, goals: List<SavingsGoal>) {
        val doc = if (group.id.isEmpty()) groupsCol.document() else groupsCol.document(group.id)
        val batch = db.batch()
        batch.set(
            doc,
            mapOf(
                "accountId" to group.accountId,
                "name" to group.name,
                "dueDate" to group.dueDate.toString(),
                "combined" to group.combined,
                F_CREATED_AT to if (group.createdAt > 0) group.createdAt else System.currentTimeMillis(),
            ),
        )
        goals.forEach { goal ->
            val shouldBeIn = goal.id in memberIds
            val isIn = goal.groupId == doc.id
            if (shouldBeIn != isIn) batch.set(goalsCol.document(goal.id), goalData(goal.copy(groupId = if (shouldBeIn) doc.id else null)))
        }
        batch.commit().logFailure("그룹 저장")
    }

    /** 그룹을 푼다: 그룹만 지우고 목표는 남긴다. */
    fun deleteGroup(groupId: String, goals: List<SavingsGoal>) {
        val batch = db.batch()
        batch.delete(groupsCol.document(groupId))
        goals.filter { it.groupId == groupId }.forEach { batch.set(goalsCol.document(it.id), goalData(it.copy(groupId = null))) }
        batch.commit().logFailure("그룹 풀기")
    }

    private fun goalData(goal: SavingsGoal): Map<String, Any?> = mapOf(
        "accountId" to goal.accountId,
        "name" to goal.name,
        "type" to goal.type.name,
        "amount" to goal.amount,
        "dueDate" to goal.dueDate.toString(),
        "intervalMonths" to goal.intervalMonths,
        F_CREATED_AT to if (goal.createdAt > 0) goal.createdAt else System.currentTimeMillis(),
        "groupId" to goal.groupId,
        "lastSpentDate" to goal.lastSpentDate?.toString(),
        "spentDates" to goal.spentDates.map { it.toString() },
        "completedDate" to goal.completedDate?.toString(),
    )

    fun deleteGoal(goalId: String) {
        goalsCol.document(goalId).delete().logFailure("목표 삭제")
    }

    private fun <T> Query.listen(mapper: (DocumentSnapshot) -> T?): Flow<List<T>> = callbackFlow {
        val registration = addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "불러오기 실패", error)
                onError("목적통장 불러오기 실패: ${error.localizedMessage}")
                return@addSnapshotListener
            }
            if (snapshot != null) trySend(snapshot.documents.mapNotNull(mapper))
        }
        awaitClose { registration.remove() }
    }

    private fun Task<*>.logFailure(action: String) {
        addOnFailureListener {
            Log.w(TAG, "$action 실패", it)
            onError("$action 실패: ${it.localizedMessage}")
        }
    }

    private fun DocumentSnapshot.toAccount(): SavingsAccount? {
        val name = getString("name") ?: return null
        val subAccounts = (get("subAccounts") as? List<*>)?.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            SavingsSubAccount(
                name = map["name"] as? String ?: "",
                number = map["number"] as? String ?: "",
                balance = (map["balance"] as? Number)?.toDouble() ?: 0.0,
            )
        }
            // 예전 버전에서 만든 통장: 잔액 하나를 계좌 하나로 옮긴다.
            ?: listOf(SavingsSubAccount(name = "기본 계좌", balance = getDouble("balance") ?: 0.0))
        return SavingsAccount(
            id = id,
            name = name,
            subAccounts = subAccounts,
            balanceDate = getString("balanceDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            annualRate = getDouble("annualRate") ?: 0.0,
            memo = getString("memo").orEmpty(),
            createdAt = getLong(F_CREATED_AT) ?: 0L,
        )
    }

    private fun DocumentSnapshot.toGoal(): SavingsGoal? {
        val accountId = getString("accountId") ?: return null
        val due = getString("dueDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return SavingsGoal(
            id = id,
            accountId = accountId,
            name = getString("name").orEmpty(),
            type = GoalType.entries.firstOrNull { it.name == getString("type") } ?: GoalType.ONE_TIME,
            amount = getDouble("amount") ?: 0.0,
            dueDate = due,
            intervalMonths = getLong("intervalMonths")?.toInt() ?: 12,
            createdAt = getLong(F_CREATED_AT) ?: 0L,
            groupId = getString("groupId")?.ifBlank { null },
            lastSpentDate = getString("lastSpentDate")?.let(::parseDate),
            spentDates = (get("spentDates") as? List<*>)?.mapNotNull { (it as? String)?.let(::parseDate) }.orEmpty(),
            completedDate = getString("completedDate")?.let(::parseDate),
        )
    }

    private fun DocumentSnapshot.toGroup(): SavingsGoalGroup? {
        val accountId = getString("accountId") ?: return null
        val due = getString("dueDate")?.let(::parseDate) ?: return null
        return SavingsGoalGroup(
            id = id,
            accountId = accountId,
            name = getString("name").orEmpty(),
            dueDate = due,
            combined = getBoolean("combined") ?: true,
            createdAt = getLong(F_CREATED_AT) ?: 0L,
        )
    }

    private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

    private companion object {
        const val TAG = "SavingsRepository"
        const val F_CREATED_AT = "createdAt"
    }
}
