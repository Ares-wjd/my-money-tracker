package com.mymoneytracker.app.ui.update

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mymoneytracker.app.data.LatestRelease
import com.mymoneytracker.app.data.UpdateRepository
import com.mymoneytracker.core.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: LatestRelease) : UpdateState
    data class Downloading(val release: LatestRelease, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: LatestRelease, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** 앱 업데이트 확인·다운로드 상태. 앱을 열 때 한 번 자동으로 확인한다. */
class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {

    val repository = UpdateRepository(application)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 새 버전 안내 대화상자를 보여줄지. */
    private val _showDialog = MutableStateFlow(false)
    val showDialog: StateFlow<Boolean> = _showDialog.asStateFlow()

    init {
        check(manual = false)
    }

    /**
     * @param manual 설정 화면에서 직접 누른 경우. 자동 확인은 실패해도 조용히 넘어가고,
     * "나중에" 를 누른 빌드는 다시 묻지 않는다.
     */
    fun check(manual: Boolean) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            val latest = runCatching { repository.fetchLatest() }
                .onFailure { Log.w(TAG, "업데이트 확인 실패", it) }
            val release = latest.getOrNull()
            _state.value = when {
                latest.isFailure -> if (manual) UpdateState.Failed("업데이트를 확인하지 못했습니다. 인터넷 연결을 확인하세요.") else UpdateState.Idle
                release == null || release.versionCode <= repository.installedVersionCode -> UpdateState.UpToDate
                else -> UpdateState.Available(release)
            }
            if (release != null) {
                val skipped = if (manual) null else repository.skippedVersionCode
                _showDialog.value = UpdateInfo.shouldNotify(release.versionCode, repository.installedVersionCode, skipped)
            }
        }
    }

    fun skip() {
        (_state.value as? UpdateState.Available)?.let { repository.skippedVersionCode = it.release.versionCode }
        _showDialog.value = false
    }

    fun dismissDialog() {
        _showDialog.value = false
    }

    fun download() {
        val release = (_state.value as? UpdateState.Available)?.release ?: return
        _showDialog.value = true
        _state.value = UpdateState.Downloading(release, 0f)
        viewModelScope.launch {
            runCatching {
                repository.download(release) { progress -> _state.value = UpdateState.Downloading(release, progress) }
            }.onSuccess { apk ->
                _state.value = UpdateState.ReadyToInstall(release, apk)
            }.onFailure {
                Log.w(TAG, "업데이트 다운로드 실패", it)
                _state.value = UpdateState.Failed("다운로드에 실패했습니다. 잠시 후 다시 시도하세요.")
            }
        }
    }

    fun resetAfterFailure() {
        _state.value = UpdateState.Idle
        _showDialog.value = false
    }

    private companion object {
        const val TAG = "AppUpdate"
    }
}
