package com.mymoneytracker.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API 키처럼 민감한 값을 이 기기에만 암호화해 저장한다.
 * 암호화 키는 Android Keystore 안에 있어 앱 밖으로 꺼낼 수 없다. 클라우드·백업에 올라가지 않는다.
 */
class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE)

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val (ivText, dataText) = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "복호화 실패: $name")
            null
        }
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(name, stored).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "SecureStore"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "my_money_tracker_secure_store"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val KIS_APP_KEY = "kis_app_key"
        const val KIS_APP_SECRET = "kis_app_secret"
        const val KIS_TOKEN = "kis_token"
        const val KIS_TOKEN_EXPIRES_AT = "kis_token_expires_at"
        const val KIS_SERVICE_EXPIRY = "kis_service_expiry"
        const val KIS_ACCOUNT_NO = "kis_account_no"
        const val KIS_ACCOUNT_PRODUCT = "kis_account_product"
        const val KIS_LINKED_ACCOUNT_ID = "kis_linked_account_id"
        const val KIS_SYNC_START = "kis_sync_start"
        const val KIS_LAST_SYNC = "kis_last_sync"
        const val EXIM_KEY = "exim_key"
    }
}
