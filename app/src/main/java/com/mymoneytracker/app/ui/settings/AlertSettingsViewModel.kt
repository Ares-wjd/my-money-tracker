package com.mymoneytracker.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.mymoneytracker.app.data.alerts.AlertNotifier
import com.mymoneytracker.app.data.alerts.AlertScheduler
import com.mymoneytracker.app.data.alerts.AlertSettingsRepository
import com.mymoneytracker.app.data.alerts.AlertState
import com.mymoneytracker.app.data.alerts.SentAlert
import com.mymoneytracker.core.alerts.AlertSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 필수 설정 두 가지 (알림 권한 · 배터리 최적화 제외). */
data class AlertReadiness(val notifications: Boolean, val battery: Boolean) {
    val ready: Boolean get() = notifications && battery
}

/**
 * 알림 설정 (설정 › 알림, v2.11): Firestore settings/alerts 를 읽고 쓰고, 바뀔 때마다 예약(30분 확인·시각 알람)을 맞춘다.
 * 앱을 열 때 MainScreen 에서 만들어져 예약을 다시 건다.
 */
class AlertSettingsViewModel(application: Application, uid: String) : AndroidViewModel(application) {
    private val repository = AlertSettingsRepository(FirebaseFirestore.getInstance(), uid)
    private val state = AlertState(application)

    /** null 이면 아직 읽기 전. */
    val settings: StateFlow<AlertSettings?> = repository.settings().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _readiness = MutableStateFlow(checkReadiness())
    val readiness: StateFlow<AlertReadiness> = _readiness.asStateFlow()

    private val _recent = MutableStateFlow(state.recent())
    val recent: StateFlow<List<SentAlert>> = _recent.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            settings.collect { s -> if (s != null) AlertScheduler.apply(getApplication(), s) }
        }
    }

    private fun checkReadiness() = AlertReadiness(AlertNotifier.notificationsAllowed(getApplication()), AlertNotifier.batteryUnrestricted(getApplication()))

    /** 화면으로 돌아올 때마다 (권한 창·배터리 설정에서 돌아온 뒤) 다시 확인한다. */
    fun refresh() {
        _readiness.value = checkReadiness()
        _recent.value = state.recent()
    }

    fun update(change: (AlertSettings) -> AlertSettings) {
        val current = settings.value ?: AlertSettings()
        val next = change(current)
        if (next == current) return
        repository.save(next) { _message.value = it }
    }

    /** [알림 받기]: 필수 설정이 다 되어 있어야 켤 수 있다. */
    fun setEnabled(on: Boolean): Boolean {
        refresh()
        if (on && !_readiness.value.ready) {
            _message.value = "알림 권한과 배터리 최적화 제외를 먼저 허용하세요."
            return false
        }
        update { it.copy(enabled = on) }
        if (on) AlertScheduler.runNow(getApplication())
        return true
    }

    fun sendTest() {
        _message.value = if (AlertNotifier.test(getApplication())) null else "알림 권한이 없어 보내지 못했습니다."
    }

    fun messageShown() {
        _message.value = null
    }

    companion object {
        fun factory(uid: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { AlertSettingsViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!, uid) }
        }
    }
}
