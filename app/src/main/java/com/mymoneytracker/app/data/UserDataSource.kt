package com.mymoneytracker.app.data

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/** 로그인한 사용자의 프로필 문서(users/{uid}). */
class UserDataSource(
    db: FirebaseFirestore,
    uid: String,
) {
    private val userDocument = db.collection("users").document(uid)

    /** 로그인할 때마다 사용자 프로필 문서를 갱신한다. */
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

    private companion object {
        const val TAG = "UserDataSource"
    }
}
