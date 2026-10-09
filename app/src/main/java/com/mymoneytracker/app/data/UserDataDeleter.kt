package com.mymoneytracker.app.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** 탈퇴할 때 사용자의 클라우드 데이터(users/{uid} 이하 전부)를 지운다. 인터넷 연결이 필요하다. */
class UserDataDeleter(private val db: FirebaseFirestore) {

    suspend fun deleteAll(uid: String) {
        val userDoc = db.collection("users").document(uid)
        for (name in COLLECTIONS) {
            val docs = userDoc.collection(name).get().await().documents
            docs.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
        userDoc.delete().await()
    }

    private companion object {
        val COLLECTIONS = listOf(
            "accounts", "holdings", "records", "settings", "marketData", "savingsAccounts", "savingsGoals",
        )
    }
}
