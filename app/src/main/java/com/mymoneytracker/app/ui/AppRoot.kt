package com.mymoneytracker.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.firebase.firestore.FirebaseFirestore
import com.mymoneytracker.app.data.AuthRepository
import com.mymoneytracker.app.data.UserDataDeleter
import com.mymoneytracker.app.ui.login.LoginScreen
import kotlinx.coroutines.launch

/** 로그인 여부에 따라 로그인 화면 또는 메인 화면을 보여준다. */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val authRepository = remember { AuthRepository(context.applicationContext) }
    val user by authRepository.userChanges.collectAsStateWithLifecycle(authRepository.currentUser)
    val scope = rememberCoroutineScope()

    when (val signedIn = user) {
        null -> LoginScreen(onSignIn = { activityContext -> authRepository.signInWithGoogle(activityContext) })
        else -> MainScreen(
            user = signedIn,
            onSignOut = { scope.launch { authRepository.signOut() } },
            onDeleteAccount = { activityContext ->
                authRepository.deleteAccount(activityContext) { uid ->
                    UserDataDeleter(FirebaseFirestore.getInstance()).deleteAll(uid)
                }
            },
        )
    }
}

/** google-services.json 없이 빌드된 경우 보여주는 안내 화면. */
@Composable
fun SetupRequiredScreen() {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Firebase 설정이 필요합니다", style = MaterialTheme.typography.titleLarge)
            Text(
                "Firebase 콘솔에서 받은 google-services.json 파일을 app/ 폴더에 넣고 다시 빌드해 주세요. " +
                    "자세한 방법은 README 를 참고하세요.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
