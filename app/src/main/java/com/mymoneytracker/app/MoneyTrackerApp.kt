package com.mymoneytracker.app

import android.app.Application
import com.google.firebase.FirebaseApp

class MoneyTrackerApp : Application() {

    /** google-services.json 이 없어 Firebase 를 초기화할 수 없으면 false. */
    var firebaseReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        firebaseReady = FirebaseApp.getApps(this).isNotEmpty() || FirebaseApp.initializeApp(this) != null
    }
}
