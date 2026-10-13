package com.mymoneytracker.app.data

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.mymoneytracker.core.chart.DayValue
import com.mymoneytracker.core.chart.StoredChart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.LocalDate

/**
 * 그래프 일별 값 (미리 계산해 저장, 웹과 같은 형식).
 *
 * users/{uid}/chartDaily/{계좌id} { v, from, to, days: {날짜: [평가금, 투자금, 누적 배당, 시세 없음 0|1]},
 *                                  recSig: {날짜: 요약}, accSig, priceSig: {키: 요약}, anchor }
 */
class ChartRepository(db: FirebaseFirestore, uid: String) {
    private val col = db.collection("users").document(uid).collection("chartDaily")

    /** 저장값을 읽을 수 있는지 (보안 규칙을 게시하기 전에는 false → 그래프를 바로 계산하고 저장하지 않는다). */
    @Volatile
    var available: Boolean = true
        private set

    /** 계좌 id → 저장된 값. */
    fun stored(): Flow<Map<String, StoredChart>> = callbackFlow {
        val registration = col.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "그래프 값 불러오기 실패", error)
                available = false
                trySend(emptyMap())
                return@addSnapshotListener
            }
            available = true
            if (snapshot != null) trySend(snapshot.documents.mapNotNull { doc -> doc.toStored()?.let { doc.id to it } }.toMap())
        }
        awaitClose { registration.remove() }
    }

    /** 쓰기는 기다리지 않는다 (로컬 캐시에 먼저 반영되어 [stored] 가 바로 바뀐다). */
    fun save(accountId: String, chart: StoredChart) {
        col.document(accountId).set(
            mapOf(
                "v" to chart.version,
                "from" to chart.from.toString(),
                "to" to chart.to.toString(),
                "days" to chart.days.entries.associate { (date, d) ->
                    date.toString() to listOf(d.valueKrw, d.investedKrw, d.dividendsKrw, if (d.missingPrice) 1 else 0)
                },
                "recSig" to chart.recSig.mapKeys { it.key.toString() },
                "accSig" to chart.accSig,
                "priceSig" to chart.priceSig,
                "anchor" to chart.anchor?.toString(),
            ),
        ).addOnFailureListener { Log.w(TAG, "그래프 값 저장 실패", it) }
    }

    fun delete(accountId: String) {
        col.document(accountId).delete().addOnFailureListener { Log.w(TAG, "그래프 값 지우기 실패", it) }
    }

    private fun DocumentSnapshot.toStored(): StoredChart? = runCatching {
        val days = (get("days") as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
            val date = LocalDate.parse(k as String)
            val values = (v as? List<*>)?.map { (it as Number).toDouble() } ?: return@mapNotNull null
            date to DayValue(values[0], values[1], values[2], values.getOrNull(3) == 1.0)
        }.toMap()
        StoredChart(
            version = (get("v") as? Number)?.toInt() ?: 0,
            from = LocalDate.parse(getString("from")),
            to = LocalDate.parse(getString("to")),
            days = days,
            recSig = (get("recSig") as? Map<*, *>).orEmpty().entries.associate { LocalDate.parse(it.key as String) to it.value as String },
            accSig = getString("accSig").orEmpty(),
            priceSig = (get("priceSig") as? Map<*, *>).orEmpty().entries.associate { it.key as String to it.value as String },
            anchor = getString("anchor")?.let(LocalDate::parse),
        )
    }.getOrNull()

    private companion object {
        const val TAG = "ChartRepository"
    }
}
