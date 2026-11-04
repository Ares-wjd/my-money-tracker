package com.mymoneytracker.app.data.alerts

import android.content.Context
import com.mymoneytracker.core.alerts.Alert
import com.mymoneytracker.core.alerts.AlertChannel
import com.mymoneytracker.core.alerts.AlertSettings
import com.mymoneytracker.core.alerts.AlertTarget
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** 이 폰에서 보낸 알림 한 건 (최근 알림 목록). */
data class SentAlert(val at: Long, val title: String, val body: String)

/**
 * 알림을 보내는 폰에만 두는 상태 (v2.11): 보낸 알림 키(중복 방지, 60일 지나면 지움), 비중 범위 밖 기억,
 * 하루 한 번 확인한 날, 밤에 모은 알림, 최근 알림 50건, 재부팅 뒤 알람을 다시 걸 설정 사본.
 */
class AlertState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 보낸 키

    fun sent(): Set<String> = sentMap().keys

    fun markSent(keys: Collection<String>, today: LocalDate) {
        if (keys.isEmpty()) return
        val map = sentMap().filterValues { it >= today.toEpochDay() - KEEP_DAYS }.toMutableMap()
        keys.forEach { map[it] = today.toEpochDay() }
        prefs.edit().putString(K_SENT, JSONObject(map as Map<*, *>).toString()).apply()
    }

    private fun sentMap(): Map<String, Long> {
        val o = runCatching { JSONObject(prefs.getString(K_SENT, null) ?: "{}") }.getOrElse { JSONObject() }
        return o.keys().asSequence().associateWith { o.optLong(it) }
    }

    // ------------------------------------------------------------ 비중 범위 밖 기억

    fun allocOut(): Map<String, Set<String>> {
        val o = runCatching { JSONObject(prefs.getString(K_ALLOC_OUT, null) ?: "{}") }.getOrElse { JSONObject() }
        return o.keys().asSequence().associateWith { id ->
            val a = o.optJSONArray(id) ?: JSONArray()
            (0 until a.length()).map { a.getString(it) }.toSet()
        }
    }

    fun saveAllocOut(out: Map<String, Set<String>>) {
        val o = JSONObject()
        out.forEach { (id, keys) -> o.put(id, JSONArray(keys.toList())) }
        prefs.edit().putString(K_ALLOC_OUT, o.toString()).apply()
    }

    // ------------------------------------------------------------ 하루 한 번 확인한 날

    fun lastChecked(kind: String): LocalDate? = prefs.getString(K_LAST + kind, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun setLastChecked(kind: String, date: LocalDate) {
        prefs.edit().putString(K_LAST + kind, date.toString()).apply()
    }

    // ------------------------------------------------------------ 밤에 모은 알림

    fun pending(): List<Alert> = readAlerts(K_PENDING)

    fun addPending(alerts: List<Alert>) {
        if (alerts.isEmpty()) return
        writeAlerts(K_PENDING, (pending() + alerts).takeLast(MAX_PENDING))
    }

    fun clearPending() {
        prefs.edit().remove(K_PENDING).apply()
    }

    private fun readAlerts(key: String): List<Alert> {
        val a = runCatching { JSONArray(prefs.getString(key, null) ?: "[]") }.getOrElse { JSONArray() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Alert(
                key = o.optString("key"),
                channel = AlertChannel.entries.firstOrNull { it.name == o.optString("channel") } ?: AlertChannel.MOVE,
                title = o.optString("title"),
                body = o.optString("body"),
                target = AlertTarget(o.optString("screen", "today"), o.optString("id").ifEmpty { null }),
            )
        }
    }

    private fun writeAlerts(key: String, alerts: List<Alert>) {
        val a = JSONArray()
        alerts.forEach {
            a.put(
                JSONObject().put("key", it.key).put("channel", it.channel.name).put("title", it.title).put("body", it.body)
                    .put("screen", it.target.screen).put("id", it.target.id ?: ""),
            )
        }
        prefs.edit().putString(key, a.toString()).apply()
    }

    // ------------------------------------------------------------ 최근 알림

    fun recent(): List<SentAlert> {
        val a = runCatching { JSONArray(prefs.getString(K_RECENT, null) ?: "[]") }.getOrElse { JSONArray() }
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { SentAlert(it.optLong("at"), it.optString("title"), it.optString("body")) }
        }
    }

    fun addRecent(alert: Alert, at: Long = System.currentTimeMillis()) {
        val list = listOf(SentAlert(at, alert.title, alert.body)) + recent()
        val a = JSONArray()
        list.take(MAX_RECENT).forEach { a.put(JSONObject().put("at", it.at).put("title", it.title).put("body", it.body)) }
        prefs.edit().putString(K_RECENT, a.toString()).apply()
    }

    // ------------------------------------------------------------ 설정 사본 (재부팅 뒤 알람용)

    fun cachedSettings(): AlertSettings? {
        val text = prefs.getString(K_SETTINGS, null) ?: return null
        return runCatching {
            val o = JSONObject(text)
            val map = o.keys().asSequence().associateWith { k ->
                when (val v = o.get(k)) {
                    is JSONArray -> (0 until v.length()).map { v.get(it) }
                    JSONObject.NULL -> null
                    else -> v
                }
            }
            AlertSettingsRepository.fromMap(map)
        }.getOrNull()
    }

    fun cacheSettings(settings: AlertSettings) {
        val o = JSONObject()
        AlertSettingsRepository.toMap(settings).forEach { (k, v) -> o.put(k, if (v is List<*>) JSONArray(v) else v) }
        prefs.edit().putString(K_SETTINGS, o.toString()).apply()
    }

    /** 로그아웃·탈퇴할 때 지운다. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val CHECK_ALLOCATION = "allocation"
        const val CHECK_GOAL = "goal"
        private const val K_SENT = "sent"
        private const val K_ALLOC_OUT = "alloc_out"
        private const val K_LAST = "last_"
        private const val K_PENDING = "pending"
        private const val K_RECENT = "recent"
        private const val K_SETTINGS = "settings"
        private const val KEEP_DAYS = 60L
        private const val MAX_RECENT = 50
        private const val MAX_PENDING = 30
    }
}
