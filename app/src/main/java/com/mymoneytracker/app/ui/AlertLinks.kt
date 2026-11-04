package com.mymoneytracker.app.ui

import android.content.Intent
import com.mymoneytracker.app.data.alerts.AlertNotifier
import com.mymoneytracker.core.alerts.AlertTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 알림을 눌러 앱이 열렸을 때 갈 화면 (MainActivity 가 넣고 MainScreen 이 꺼내 이동한다). */
object AlertLinks {
    private val _pending = MutableStateFlow<AlertTarget?>(null)
    val pending: StateFlow<AlertTarget?> = _pending.asStateFlow()

    fun handle(intent: Intent?) {
        val screen = intent?.getStringExtra(AlertNotifier.EXTRA_SCREEN) ?: return
        _pending.value = AlertTarget(screen, intent.getStringExtra(AlertNotifier.EXTRA_ID))
        intent.removeExtra(AlertNotifier.EXTRA_SCREEN)
    }

    fun consumed() {
        _pending.value = null
    }
}
