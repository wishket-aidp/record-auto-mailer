package com.melt.callrecmailer.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.melt.callrecmailer.core.SentKeyRepository

class EncryptedSentKeyRepository(context: Context) : SentKeyRepository {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "sent_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun isSent(key: String): Boolean =
        prefs.getStringSet(KEY_SET, emptySet())!!.contains(key)

    override fun markSent(key: String) {
        val updated = HashSet(prefs.getStringSet(KEY_SET, emptySet())!!)
        updated.add(key)
        prefs.edit().putStringSet(KEY_SET, updated).apply()
    }

    companion object {
        private const val KEY_SET = "sent_keys"
    }
}
