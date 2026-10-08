package com.mymoneytracker.app.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.firebase.firestore.FirebaseFirestore
import com.mymoneytracker.app.data.SignedInUser
import com.mymoneytracker.app.data.UserDataSource
import com.mymoneytracker.app.ui.common.PlaceholderContent
import com.mymoneytracker.app.ui.settings.SettingsScreen

/** 화면 경로. 세부 기능이 확정되면 탭 구성과 상세 화면 경로를 추가/변경한다. */
private object Routes {
    const val HOME = "home"
    const val ASSETS = "assets"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "요약", Icons.Filled.PieChart),
    Tab(Routes.ASSETS, "자산", Icons.Filled.AccountBalance),
    Tab(Routes.HISTORY, "내역", Icons.AutoMirrored.Filled.ReceiptLong),
    Tab(Routes.SETTINGS, "설정", Icons.Filled.Settings),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(user: SignedInUser, onSignOut: () -> Unit) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    LaunchedEffect(user.uid) {
        UserDataSource(FirebaseFirestore.getInstance(), user.uid).updateProfile(user)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
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
                PlaceholderContent("요약", "순자산과 이번 달 현황이 표시될 자리입니다.")
            }
            composable(Routes.ASSETS) {
                PlaceholderContent("자산", "보유 자산·부채 목록이 표시될 자리입니다.")
            }
            composable(Routes.HISTORY) {
                PlaceholderContent("내역", "수입·지출 내역이 표시될 자리입니다.")
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(user = user, onSignOut = onSignOut)
            }
        }
    }
}
