package com.mymoneytracker.app.data.alerts

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.mymoneytracker.core.alerts.AlertSettings
import com.mymoneytracker.core.alerts.CloseBasis
import com.mymoneytracker.core.alerts.CloseSetting
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 알림 설정 (Firestore users/{uid}/settings/alerts, v2.11): 다시 설치해도 유지되고 폰마다 같은 설정. */
class AlertSettingsRepository(db: FirebaseFirestore, uid: String) {
    private val doc = db.collection("users").document(uid).collection("settings").document(DOC)

    fun settings(): Flow<AlertSettings> = callbackFlow {
        val registration = doc.addSnapshotListener { snapshot, error ->
            if (error != null) {
                // 잠깐의 오류로 "꺼짐"을 보내면 예약이 취소되므로 아무것도 보내지 않는다
                Log.w(TAG, "알림 설정 불러오기 실패", error)
                return@addSnapshotListener
            }
            trySend(snapshot?.let(::fromDoc) ?: AlertSettings())
        }
        awaitClose { registration.remove() }
    }

    /** 한 번 읽기 (백그라운드 확인용). 읽을 수 없으면 null. */
    suspend fun load(): AlertSettings? = runCatching { fromDoc(doc.get().await()) }.getOrNull()

    fun save(settings: AlertSettings, onError: (String) -> Unit) {
        doc.set(toMap(settings)).addOnFailureListener {
            Log.w(TAG, "알림 설정 저장 실패", it)
            onError("알림 설정을 저장하지 못했습니다. 보안 규칙을 새로 게시했는지 확인하세요.")
        }
    }

    companion object {
        private const val TAG = "AlertSettings"
        const val DOC = "alerts"
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun time(text: String?, default: LocalTime): LocalTime = text?.let { runCatching { LocalTime.parse(it, HHMM) }.getOrNull() } ?: default
        fun timeText(t: LocalTime): String = t.format(HHMM)

        fun fromDoc(d: DocumentSnapshot): AlertSettings = if (!d.exists()) AlertSettings() else fromMap(d.data.orEmpty())

        /** Firestore 문서(또는 기기에 둔 사본)의 맵 → 설정. 없는 값은 기본값. */
        fun fromMap(m: Map<String, Any?>): AlertSettings {
            val def = AlertSettings()
            fun bool(k: String, v: Boolean) = m[k] as? Boolean ?: v
            fun dbl(k: String, v: Double) = (m[k] as? Number)?.toDouble() ?: v
            fun basis(k: String, v: CloseBasis) = CloseBasis.entries.firstOrNull { it.name == m[k] } ?: v
            fun list(k: String): List<Any?>? = (m[k] as? List<*>) ?: (m[k] as? Array<*>)?.toList()
            return AlertSettings(
                enabled = bool("enabled", def.enabled),
                moveOn = bool("moveOn", def.moveOn),
                moveMarket = dbl("moveMarket", def.moveMarket),
                moveItem = dbl("moveItem", def.moveItem),
                allocOn = bool("allocOn", def.allocOn),
                allocOff = list("allocOff").orEmpty().mapNotNull { it as? String }.toSet(),
                goalOn = bool("goalOn", def.goalOn),
                goalDays = list("goalDays")?.mapNotNull { (it as? Number)?.toInt() }?.toSet() ?: def.goalDays,
                goalOverdue = bool("goalOverdue", def.goalOverdue),
                krClose = CloseSetting(bool("krCloseOn", def.krClose.on), basis("krCloseBasis", def.krClose.basis), time(m["krCloseTime"] as? String, def.krClose.time)),
                usClose = CloseSetting(bool("usCloseOn", def.usClose.on), basis("usCloseBasis", def.usClose.basis), time(m["usCloseTime"] as? String, def.usClose.time)),
                quietOn = bool("quietOn", def.quietOn),
                quietFrom = time(m["quietFrom"] as? String, def.quietFrom),
                quietTo = time(m["quietTo"] as? String, def.quietTo),
            )
        }

        fun toMap(s: AlertSettings): Map<String, Any?> = mapOf(
            "enabled" to s.enabled,
            "moveOn" to s.moveOn,
            "moveMarket" to s.moveMarket,
            "moveItem" to s.moveItem,
            "allocOn" to s.allocOn,
            "allocOff" to s.allocOff.toList(),
            "goalOn" to s.goalOn,
            "goalDays" to s.goalDays.sortedDescending(),
            "goalOverdue" to s.goalOverdue,
            "krCloseOn" to s.krClose.on,
            "krCloseBasis" to s.krClose.basis.name,
            "krCloseTime" to timeText(s.krClose.time),
            "usCloseOn" to s.usClose.on,
            "usCloseBasis" to s.usClose.basis.name,
            "usCloseTime" to timeText(s.usClose.time),
            "quietOn" to s.quietOn,
            "quietFrom" to timeText(s.quietFrom),
            "quietTo" to timeText(s.quietTo),
            "updatedAt" to System.currentTimeMillis(),
        )
    }
}
