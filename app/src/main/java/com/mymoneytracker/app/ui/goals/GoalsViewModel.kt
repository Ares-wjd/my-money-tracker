package com.mymoneytracker.app.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.mymoneytracker.app.data.SavingsRepository
import com.mymoneytracker.core.goals.GoalCalculator
import com.mymoneytracker.core.goals.SavingsSummary
import com.mymoneytracker.core.model.SavingsAccount
import com.mymoneytracker.core.model.SavingsGoal
import com.mymoneytracker.core.model.SavingsGoalGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class GoalsData(
    val accounts: List<SavingsAccount>,
    val goals: List<SavingsGoal>,
    val summaries: List<SavingsSummary>,
    val groups: List<SavingsGoalGroup> = emptyList(),
) {
    fun account(id: String) = accounts.firstOrNull { it.id == id }
    fun goal(id: String) = goals.firstOrNull { it.id == id }
    fun group(id: String) = groups.firstOrNull { it.id == id }
    fun summary(accountId: String) = summaries.firstOrNull { it.account.id == accountId }
}

/** 목적통장 화면들이 공유하는 데이터. */
class GoalsViewModel(repositoryFactory: ((String) -> Unit) -> SavingsRepository) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val repository = repositoryFactory { _message.value = it }

    val data: StateFlow<GoalsData?> = combine(repository.accounts(), repository.goals(), repository.groups()) { accounts, goals, groups ->
        val today = LocalDate.now()
        GoalsData(
            accounts = accounts,
            goals = goals,
            summaries = accounts.map { account ->
                GoalCalculator.summarize(
                    account,
                    goals.filter { it.accountId == account.id },
                    today,
                    groups.filter { it.accountId == account.id },
                )
            },
            groups = groups,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveAccount(account: SavingsAccount): String = repository.saveAccount(account)

    fun deleteAccount(accountId: String) {
        repository.deleteAccount(accountId, data.value?.goals.orEmpty(), data.value?.groups.orEmpty())
    }

    fun saveGoal(goal: SavingsGoal) = repository.saveGoal(goal)

    fun deleteGoal(goalId: String) = repository.deleteGoal(goalId)

    /** 반복 목표 소비 기록: 그날부터 주기를 다시 센다. */
    fun recordSpending(goal: SavingsGoal, spentOn: LocalDate) = repository.saveGoal(GoalCalculator.recordSpending(goal, spentOn))

    /** 1회성 목표(그룹이면 안의 목표 모두) 구매 완료. [date] 가 null 이면 되돌리기. */
    fun setCompleted(goals: List<SavingsGoal>, date: LocalDate?) = repository.saveGoals(goals.map { it.copy(completedDate = date) })

    fun saveGroup(group: SavingsGoalGroup, memberIds: Set<String>) =
        repository.saveGroup(group, memberIds, data.value?.goals.orEmpty())

    fun deleteGroup(groupId: String) = repository.deleteGroup(groupId, data.value?.goals.orEmpty())

    fun messageShown() {
        _message.value = null
    }

    companion object {
        fun factory(uid: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GoalsViewModel { onError -> SavingsRepository(FirebaseFirestore.getInstance(), uid, onError) }
            }
        }
    }
}
