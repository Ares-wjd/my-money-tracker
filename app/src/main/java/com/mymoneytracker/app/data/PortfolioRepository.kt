package com.mymoneytracker.app.data

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.mymoneytracker.core.model.AssetType
import com.mymoneytracker.core.model.Currency
import com.mymoneytracker.core.model.Holding
import com.mymoneytracker.core.model.InvestmentAccount
import com.mymoneytracker.core.model.Market
import com.mymoneytracker.core.model.Record
import com.mymoneytracker.core.model.RecordType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.LocalDate

/** 사용자별 앱 설정 (클라우드에 저장되는 것만). */
data class AppSettings(
    /** 환율 API 연결 전까지 쓰는, 직접 입력한 원/달러 환율. */
    val manualUsdKrw: Double? = null,
)

/**
 * 투자 자산 데이터를 Firestore 에 저장한다.
 *
 * users/{uid}/accounts/{id}   투자 계좌
 * users/{uid}/holdings/{id}   보유 종목 (accountId 로 계좌와 연결)
 * users/{uid}/records/{id}    입출금·이체·환전·매매·배당·예수금 조정 기록
 * users/{uid}/settings/app    설정
 *
 * 쓰기는 기다리지 않는다. Firestore 가 로컬 캐시에 먼저 반영하고 온라인이 되면 동기화한다.
 */
class PortfolioRepository(
    private val db: FirebaseFirestore,
    uid: String,
    private val onError: (String) -> Unit,
) {
    private val userDoc = db.collection("users").document(uid)
    private val accountsCol = userDoc.collection("accounts")
    private val holdingsCol = userDoc.collection("holdings")
    private val recordsCol = userDoc.collection("records")
    private val settingsDoc = userDoc.collection("settings").document("app")

    fun accounts(): Flow<List<InvestmentAccount>> = accountsCol.orderBy(F_CREATED_AT).listen { it.toAccount() }

    fun holdings(): Flow<List<Holding>> = holdingsCol.orderBy(F_CREATED_AT).listen { it.toHolding() }

    fun records(): Flow<List<Record>> = recordsCol.listen { it.toRecord() }

    fun settings(): Flow<AppSettings> = callbackFlow {
        val registration = settingsDoc.addSnapshotListener { snapshot, error ->
            if (error != null) {
                report("설정 불러오기", error)
                return@addSnapshotListener
            }
            trySend(AppSettings(manualUsdKrw = snapshot?.getDouble("manualUsdKrw")))
        }
        awaitClose { registration.remove() }
    }

    /** 계좌를 저장하고 문서 ID 를 돌려준다. */
    fun saveAccount(account: InvestmentAccount): String {
        val doc = if (account.id.isEmpty()) accountsCol.document() else accountsCol.document(account.id)
        val data = mapOf(
            "name" to account.name,
            "memo" to account.memo,
            F_CREATED_AT to if (account.createdAt > 0) account.createdAt else System.currentTimeMillis(),
        )
        doc.set(data).logFailure("계좌 저장")
        return doc.id
    }

    /** 계좌와 그 계좌의 종목·기록(이 계좌가 받는 이체 포함)을 함께 삭제한다. */
    fun deleteAccount(accountId: String, holdings: List<Holding>, records: List<Record>) {
        val batch = db.batch()
        batch.delete(accountsCol.document(accountId))
        holdings.filter { it.accountId == accountId }.forEach { batch.delete(holdingsCol.document(it.id)) }
        records.filter { it.accountId == accountId || it.toAccountId == accountId }
            .forEach { batch.delete(recordsCol.document(it.id)) }
        batch.commit().logFailure("계좌 삭제")
    }

    /** 종목을 저장하고 문서 ID 를 돌려준다. */
    fun saveHolding(holding: Holding): String {
        val doc = if (holding.id.isEmpty()) holdingsCol.document() else holdingsCol.document(holding.id)
        val data = mapOf(
            "accountId" to holding.accountId,
            "name" to holding.name,
            "code" to holding.code,
            "market" to holding.market.name,
            "assetType" to holding.assetType.name,
            "manualPrice" to holding.manualPrice,
            "manualPriceDate" to holding.manualPriceDate?.toString(),
            F_CREATED_AT to if (holding.createdAt > 0) holding.createdAt else System.currentTimeMillis(),
        )
        doc.set(data).logFailure("종목 저장")
        return doc.id
    }

    /** 종목과 그 종목의 매매·배당 기록을 함께 삭제한다. */
    fun deleteHolding(holdingId: String, records: List<Record>) {
        val batch = db.batch()
        batch.delete(holdingsCol.document(holdingId))
        records.filter { it.holdingId == holdingId }.forEach { batch.delete(recordsCol.document(it.id)) }
        batch.commit().logFailure("종목 삭제")
    }

    fun saveRecord(record: Record) {
        val doc = if (record.id.isEmpty()) recordsCol.document() else recordsCol.document(record.id)
        val data = mapOf(
            "accountId" to record.accountId,
            "type" to record.type.name,
            "date" to record.date.toString(),
            "currency" to record.currency.name,
            "amount" to record.amount,
            "krwAmount" to record.krwAmount,
            "toAccountId" to record.toAccountId,
            "holdingId" to record.holdingId,
            "quantity" to record.quantity,
            "price" to record.price,
            "fee" to record.fee,
            "tax" to record.tax,
            "initial" to record.initial,
            "memo" to record.memo,
            F_CREATED_AT to if (record.createdAt > 0) record.createdAt else System.currentTimeMillis(),
        )
        doc.set(data).logFailure("기록 저장")
    }

    fun deleteRecord(recordId: String) {
        recordsCol.document(recordId).delete().logFailure("기록 삭제")
    }

    fun saveManualUsdKrw(rate: Double?) {
        settingsDoc.set(mapOf("manualUsdKrw" to rate), SetOptions.merge()).logFailure("환율 저장")
    }

    private fun <T> Query.listen(mapper: (DocumentSnapshot) -> T?): Flow<List<T>> = callbackFlow {
        val registration = addSnapshotListener { snapshot, error ->
            if (error != null) {
                report("데이터 불러오기", error)
                return@addSnapshotListener
            }
            if (snapshot != null) trySend(snapshot.documents.mapNotNull(mapper))
        }
        awaitClose { registration.remove() }
    }

    private fun Task<*>.logFailure(action: String) {
        addOnFailureListener { report(action, it) }
    }

    private fun report(action: String, error: Exception) {
        Log.w(TAG, "$action 실패", error)
        onError("$action 실패: ${error.localizedMessage}")
    }

    private fun DocumentSnapshot.toAccount(): InvestmentAccount? {
        val name = getString("name") ?: return null
        return InvestmentAccount(
            id = id,
            name = name,
            memo = getString("memo").orEmpty(),
            createdAt = getLong(F_CREATED_AT) ?: 0L,
        )
    }

    private fun DocumentSnapshot.toHolding(): Holding? {
        val accountId = getString("accountId") ?: return null
        return Holding(
            id = id,
            accountId = accountId,
            name = getString("name").orEmpty(),
            code = getString("code").orEmpty(),
            market = enumOf(getString("market"), Market.KR),
            assetType = enumOf(getString("assetType"), AssetType.STOCK),
            manualPrice = getDouble("manualPrice"),
            manualPriceDate = getString("manualPriceDate")?.let(::parseDate),
            createdAt = getLong(F_CREATED_AT) ?: 0L,
        )
    }

    private fun DocumentSnapshot.toRecord(): Record? {
        val accountId = getString("accountId") ?: return null
        val type = getString("type")?.let { name -> RecordType.entries.firstOrNull { it.name == name } } ?: return null
        val date = getString("date")?.let(::parseDate) ?: return null
        return Record(
            id = id,
            accountId = accountId,
            type = type,
            date = date,
            currency = enumOf(getString("currency"), Currency.KRW),
            amount = getDouble("amount") ?: 0.0,
            krwAmount = getDouble("krwAmount") ?: 0.0,
            toAccountId = getString("toAccountId"),
            holdingId = getString("holdingId"),
            quantity = getDouble("quantity") ?: 0.0,
            price = getDouble("price") ?: 0.0,
            fee = getDouble("fee") ?: 0.0,
            tax = getDouble("tax") ?: 0.0,
            initial = getBoolean("initial") ?: false,
            memo = getString("memo").orEmpty(),
            createdAt = getLong(F_CREATED_AT) ?: 0L,
        )
    }

    private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val TAG = "PortfolioRepository"
        const val F_CREATED_AT = "createdAt"
    }
}
