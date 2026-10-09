package com.mymoneytracker.app.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.firebase.firestore.FirebaseFirestore
import com.mymoneytracker.app.data.SignedInUser
import com.mymoneytracker.app.data.UserDataSource
import com.mymoneytracker.app.ui.accounts.AccountDetailScreen
import com.mymoneytracker.app.ui.accounts.AccountEditScreen
import com.mymoneytracker.app.ui.accounts.AccountsScreen
import com.mymoneytracker.app.ui.common.LoadingBox
import com.mymoneytracker.app.ui.goals.GoalEditScreen
import com.mymoneytracker.app.ui.goals.GoalsScreen
import com.mymoneytracker.app.ui.goals.GoalsViewModel
import com.mymoneytracker.app.ui.goals.SavingsAccountDetailScreen
import com.mymoneytracker.app.ui.goals.SavingsAccountEditScreen
import com.mymoneytracker.app.ui.holdings.HoldingDetailScreen
import com.mymoneytracker.app.ui.holdings.HoldingEditScreen
import com.mymoneytracker.app.ui.home.HomeScreen
import com.mymoneytracker.app.ui.records.CashAdjustScreen
import com.mymoneytracker.app.ui.records.RecordEditScreen
import com.mymoneytracker.app.ui.settings.SettingsScreen
import com.mymoneytracker.app.ui.update.AppUpdateViewModel
import com.mymoneytracker.app.ui.update.UpdateDialog
import com.mymoneytracker.app.ui.update.UpdateState
import com.mymoneytracker.core.model.RecordType

private object Routes {
    const val HOME = "home"
    const val ACCOUNTS = "accounts"
    const val GOALS = "goals"
    const val SETTINGS = "settings"

    const val ACCOUNT_DETAIL = "account/{accountId}"
    const val ACCOUNT_EDIT = "account-edit?accountId={accountId}"
    const val HOLDING_DETAIL = "holding/{holdingId}"
    const val HOLDING_EDIT = "holding-edit/{accountId}?holdingId={holdingId}"
    const val RECORD_EDIT = "record-edit/{accountId}/{type}?recordId={recordId}&holdingId={holdingId}"
    const val CASH_ADJUST = "cash-adjust/{accountId}"
    const val SAVINGS_DETAIL = "savings/{savingsId}"
    const val SAVINGS_EDIT = "savings-edit?savingsId={savingsId}"
    const val GOAL_EDIT = "goal-edit/{savingsId}?goalId={goalId}"

    fun accountDetail(id: String) = "account/$id"
    fun accountEdit(id: String? = null) = if (id == null) "account-edit" else "account-edit?accountId=$id"
    fun holdingDetail(id: String) = "holding/$id"
    fun holdingEdit(accountId: String, holdingId: String? = null) =
        "holding-edit/$accountId" + (holdingId?.let { "?holdingId=$it" } ?: "")
    fun recordEdit(accountId: String, type: RecordType, recordId: String? = null, holdingId: String? = null): String {
        val query = listOfNotNull(recordId?.let { "recordId=$it" }, holdingId?.let { "holdingId=$it" })
        return "record-edit/$accountId/${type.name}" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
    }
    fun cashAdjust(accountId: String) = "cash-adjust/$accountId"
    fun savingsDetail(id: String) = "savings/$id"
    fun savingsEdit(id: String? = null) = if (id == null) "savings-edit" else "savings-edit?savingsId=$id"
    fun goalEdit(savingsId: String, goalId: String? = null) =
        "goal-edit/$savingsId" + (goalId?.let { "?goalId=$it" } ?: "")
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "홈", Icons.Filled.Home),
    Tab(Routes.ACCOUNTS, "계좌", Icons.Filled.AccountBalance),
    Tab(Routes.GOALS, "목표", Icons.Filled.Flag),
    Tab(Routes.SETTINGS, "설정", Icons.Filled.Settings),
)

private fun optionalString(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(user: SignedInUser, onSignOut: () -> Unit) {
    val portfolioViewModel: PortfolioViewModel = viewModel(key = user.uid, factory = PortfolioViewModel.factory(user.uid))
    val goalsViewModel: GoalsViewModel = viewModel(key = "goals-" + user.uid, factory = GoalsViewModel.factory(user.uid))
    val updateViewModel: AppUpdateViewModel = viewModel()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }
    val message by portfolioViewModel.message.collectAsStateWithLifecycle()
    val goalsMessage by goalsViewModel.message.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val showUpdateDialog by updateViewModel.showDialog.collectAsStateWithLifecycle()

    LaunchedEffect(user.uid) {
        UserDataSource(FirebaseFirestore.getInstance(), user.uid).updateProfile(user)
    }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            portfolioViewModel.messageShown()
        }
    }

    LaunchedEffect(goalsMessage) {
        goalsMessage?.let {
            snackbarHostState.showSnackbar(it)
            goalsViewModel.messageShown()
        }
    }

    if (showUpdateDialog || updateState is UpdateState.Failed) {
        UpdateDialog(updateState, updateViewModel)
    }

    Scaffold(
        // 시스템 바 여백은 각 화면의 Scaffold 가 처리한다.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (tabs.any { it.route == currentRoute }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    viewModel = portfolioViewModel,
                    onOpenAccount = { navController.navigate(Routes.accountDetail(it)) },
                    onAddAccount = { navController.navigate(Routes.accountEdit()) },
                    onOpenSettings = { navController.navigateToTab(Routes.SETTINGS) },
                )
            }
            composable(Routes.ACCOUNTS) {
                AccountsScreen(
                    viewModel = portfolioViewModel,
                    onOpenAccount = { navController.navigate(Routes.accountDetail(it)) },
                    onAddAccount = { navController.navigate(Routes.accountEdit()) },
                )
            }
            composable(Routes.GOALS) {
                GoalsScreen(
                    viewModel = goalsViewModel,
                    onOpenAccount = { navController.navigate(Routes.savingsDetail(it)) },
                    onAddAccount = { navController.navigate(Routes.savingsEdit()) },
                )
            }
            composable(Routes.SAVINGS_DETAIL) { entry ->
                val savingsId = entry.arguments?.getString("savingsId").orEmpty()
                SavingsAccountDetailScreen(
                    viewModel = goalsViewModel,
                    accountId = savingsId,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(Routes.savingsEdit(savingsId)) },
                    onAddGoal = { navController.navigate(Routes.goalEdit(savingsId)) },
                    onOpenGoal = { navController.navigate(Routes.goalEdit(savingsId, it)) },
                )
            }
            composable(Routes.SAVINGS_EDIT, arguments = listOf(optionalString("savingsId"))) { entry ->
                val savingsId = entry.arguments?.getString("savingsId")
                SavingsAccountEditScreen(
                    viewModel = goalsViewModel,
                    accountId = savingsId,
                    onBack = { navController.popBackStack() },
                    onSaved = { savedId ->
                        navController.popBackStack()
                        if (savingsId == null) navController.navigate(Routes.savingsDetail(savedId))
                    },
                    onDeleted = {
                        if (!navController.popBackStack(Routes.GOALS, inclusive = false)) {
                            navController.navigateToTab(Routes.GOALS)
                        }
                    },
                )
            }
            composable(Routes.GOAL_EDIT, arguments = listOf(optionalString("goalId"))) { entry ->
                GoalEditScreen(
                    viewModel = goalsViewModel,
                    accountId = entry.arguments?.getString("savingsId").orEmpty(),
                    goalId = entry.arguments?.getString("goalId"),
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    user = user,
                    portfolioViewModel = portfolioViewModel,
                    updateViewModel = updateViewModel,
                    onSignOut = onSignOut,
                )
            }

            composable(Routes.ACCOUNT_EDIT, arguments = listOf(optionalString("accountId"))) { entry ->
                val accountId = entry.arguments?.getString("accountId")
                AccountEditScreen(
                    viewModel = portfolioViewModel,
                    accountId = accountId,
                    onBack = { navController.popBackStack() },
                    onSaved = { savedId ->
                        navController.popBackStack()
                        if (accountId == null) navController.navigate(Routes.accountDetail(savedId))
                    },
                    onDeleted = {
                        // 삭제된 계좌의 상세 화면까지 닫고 계좌 목록으로 돌아간다.
                        if (!navController.popBackStack(Routes.ACCOUNTS, inclusive = false)) {
                            navController.navigateToTab(Routes.ACCOUNTS)
                        }
                    },
                )
            }
            composable(Routes.ACCOUNT_DETAIL) { entry ->
                val accountId = entry.arguments?.getString("accountId").orEmpty()
                AccountDetailScreen(
                    viewModel = portfolioViewModel,
                    accountId = accountId,
                    onBack = { navController.popBackStack() },
                    onEditAccount = { navController.navigate(Routes.accountEdit(accountId)) },
                    onAddHolding = { navController.navigate(Routes.holdingEdit(accountId)) },
                    onOpenHolding = { navController.navigate(Routes.holdingDetail(it)) },
                    onAddRecord = { type -> navController.navigate(Routes.recordEdit(accountId, type)) },
                    onOpenRecord = { recordId ->
                        // 유형은 기록에서 읽으므로 경로에는 아무 유형이나 넣어도 된다.
                        navController.navigate(Routes.recordEdit(accountId, RecordType.DEPOSIT, recordId = recordId))
                    },
                    onAdjustCash = { navController.navigate(Routes.cashAdjust(accountId)) },
                )
            }
            composable(Routes.HOLDING_EDIT, arguments = listOf(optionalString("holdingId"))) { entry ->
                val accountId = entry.arguments?.getString("accountId").orEmpty()
                val holdingId = entry.arguments?.getString("holdingId")
                HoldingEditScreen(
                    viewModel = portfolioViewModel,
                    accountId = accountId,
                    holdingId = holdingId,
                    onBack = { navController.popBackStack() },
                    onSaved = { savedId ->
                        navController.popBackStack()
                        if (holdingId == null) navController.navigate(Routes.holdingDetail(savedId))
                    },
                    onDeleted = { navController.popBackStack(Routes.ACCOUNT_DETAIL, inclusive = false) },
                )
            }
            composable(Routes.HOLDING_DETAIL) { entry ->
                val holdingId = entry.arguments?.getString("holdingId").orEmpty()
                val data by portfolioViewModel.data.collectAsStateWithLifecycle()
                val accountId = data?.holding(holdingId)?.accountId
                if (accountId == null) {
                    LoadingBox()
                } else {
                    HoldingDetailScreen(
                        viewModel = portfolioViewModel,
                        holdingId = holdingId,
                        onBack = { navController.popBackStack() },
                        onEdit = { navController.navigate(Routes.holdingEdit(accountId, holdingId)) },
                        onAddRecord = { type -> navController.navigate(Routes.recordEdit(accountId, type, holdingId = holdingId)) },
                        onOpenRecord = { recordId ->
                            navController.navigate(Routes.recordEdit(accountId, RecordType.BUY, recordId = recordId))
                        },
                    )
                }
            }
            composable(
                Routes.RECORD_EDIT,
                arguments = listOf(optionalString("recordId"), optionalString("holdingId")),
            ) { entry ->
                val args = entry.arguments
                RecordEditScreen(
                    viewModel = portfolioViewModel,
                    accountId = args?.getString("accountId").orEmpty(),
                    type = RecordType.entries.firstOrNull { it.name == args?.getString("type") } ?: RecordType.DEPOSIT,
                    recordId = args?.getString("recordId"),
                    presetHoldingId = args?.getString("holdingId"),
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.CASH_ADJUST) { entry ->
                CashAdjustScreen(
                    viewModel = portfolioViewModel,
                    accountId = entry.arguments?.getString("accountId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
