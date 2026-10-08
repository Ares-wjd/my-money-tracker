package com.mymoneytracker.app.data

import android.util.Log
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 로그인한 사용자 한 명의 Firestore 저장 공간.
 *
 * 모든 데이터는 users/{uid} 문서 아래에 하위 컬렉션으로 저장한다.
 * (예: users/{uid}/accounts, users/{uid}/transactions — 세부 구조는 기능 확정 후 결정)
 *
 * Firestore 는 기기에 로컬 캐시를 두므로 오프라인에서도 읽기/쓰기가 되고,
 * 온라인이 되면 자동으로 클라우드와 동기화된다.
 */
class UserDataSource(
    db: FirebaseFirestore,
    uid: String,
) {
    val userDocument: DocumentReference = db.collection("users").document(uid)

    /** 로그인할 때마다 사용자 프로필 문서를 갱신한다. (클라우드 저장 연결 확인용) */
    fun updateProfile(user: SignedInUser) {
        userDocument.set(
            mapOf(
                "email" to user.email,
                "displayName" to user.displayName,
                "lastLoginAt" to System.currentTimeMillis(),
            ),
            SetOptions.merge(),
        ).addOnFailureListener { Log.w(TAG, "프로필 저장 실패", it) }
    }

    companion object {
        private const val TAG = "UserDataSource"

        /** Firestore 쿼리 결과를 실시간 Flow 로 변환한다. */
        fun <T> Query.asFlow(mapper: (DocumentSnapshot) -> T?): Flow<List<T>> = callbackFlow {
            val registration = addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) trySend(snapshot.documents.mapNotNull(mapper))
            }
            awaitClose { registration.remove() }
        }
    }
}
