package com.mymoneytracker.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 화면 모드 (v2.18): [밝게] [어둡게] 중 고른 값 (기기에 기억). 고르기 전에는 null = 폰 설정을 따른다.
 * 앱을 시작할 때 [init] 으로 읽는다.
 */
object ThemeMode {
    private const val PREFS = "theme"
    private const val KEY = "mode"
    private var prefs: android.content.SharedPreferences? = null
    private val state = MutableStateFlow<Boolean?>(null)

    /** true 어둡게, false 밝게, null 고르기 전. */
    val chosen: StateFlow<Boolean?> = state.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        state.value = when (p.getString(KEY, null)) {
            "dark" -> true
            "light" -> false
            else -> null
        }
    }

    fun choose(dark: Boolean) {
        state.value = dark
        prefs?.edit()?.putString(KEY, if (dark) "dark" else "light")?.apply()
    }
}
