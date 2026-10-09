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
) {
    fun account(id: String) = accounts.firstOrNull { it.id == id }
    fun goal(id: String) = goals.firstOrNull { it.id == id }
    fun summary(accountId: String) = summaries.firstOrNull { it.account.id == accountId }
}

/** 목적통장 화면들이 공유하는 데이터. */
class GoalsViewModel(repositoryFactory: ((String) -> Unit) -> SavingsRepository) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val repository = repositoryFactory { _message.value = it }

    val data: StateFlow<GoalsData?> = combine(repository.accounts(), repository.goals()) { accounts, goals ->
        val today = LocalDate.now()
        GoalsData(
            accounts = accounts,
            goals = goals,
            summaries = accounts.map { account ->
                GoalCalculator.summarize(account, goals.filter { it.accountId == account.id }, today)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveAccount(account: SavingsAccount): String = repository.saveAccount(account)

    fun deleteAccount(accountId: String) {
        repository.deleteAccount(accountId, data.value?.goals.orEmpty())
    }

    fun saveGoal(goal: SavingsGoal) = repository.saveGoal(goal)

    fun deleteGoal(goalId: String) = repository.deleteGoal(goalId)

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
