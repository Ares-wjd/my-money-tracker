package com.mymoneytracker.app.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class SignedInUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
)

class AuthRepository(private val appContext: Context) {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    val currentUser: SignedInUser?
        get() = auth.currentUser?.toSignedInUser()

    val userChanges: Flow<SignedInUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toSignedInUser()) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /**
     * Credential Manager 로 Google 계정을 선택받아 Firebase 에 로그인한다.
     * [activityContext] 는 계정 선택 UI 를 띄울 Activity 컨텍스트여야 한다.
     */
    suspend fun signInWithGoogle(activityContext: Context) {
        val clientId = webClientId()
            ?: error("웹 클라이언트 ID를 찾을 수 없습니다. google-services.json 을 다시 받아 주세요.")

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = CredentialManager.create(activityContext)
            .getCredential(activityContext, request)
            .credential

        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            error("지원하지 않는 로그인 방식입니다.")
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    suspend fun signOut() {
        auth.signOut()
        runCatching {
            CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest())
        }
    }

    /** google-services 플러그인이 생성하는 default_web_client_id 리소스를 찾는다. */
    @SuppressLint("DiscouragedApi")
    private fun webClientId(): String? {
        val resId = appContext.resources.getIdentifier(
            "default_web_client_id", "string", appContext.packageName,
        )
        return if (resId != 0) appContext.getString(resId) else null
    }

    private fun FirebaseUser.toSignedInUser() = SignedInUser(uid, email, displayName)
}
