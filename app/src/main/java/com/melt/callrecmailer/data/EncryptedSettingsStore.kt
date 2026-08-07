package com.melt.callrecmailer.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.melt.callrecmailer.core.Settings
import com.melt.callrecmailer.core.SettingsStore
import com.melt.callrecmailer.core.SmtpConfig

class EncryptedSettingsStore(context: Context) : SettingsStore {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): Settings {
        val from = prefs.getString(KEY_FROM, Settings.DEFAULT_FROM)!!
        val pw = prefs.getString(KEY_APP_PW, "")!!
        val to = prefs.getString(KEY_TO, Settings.DEFAULT_TO)!!
        val dir = prefs.getString(KEY_DIR, Settings.DEFAULT_WATCH_DIR)!!
        return Settings(
            smtp = SmtpConfig(Settings.SMTP_HOST, Settings.SMTP_PORT, from, pw, from),
            toAddress = to,
            watchDir = dir,
            extensions = Settings.DEFAULT_EXTENSIONS,
            maxAttachmentBytes = Settings.DEFAULT_MAX_BYTES,
        )
    }

    override fun saveAppPassword(appPassword: String) {
        prefs.edit().putString(KEY_APP_PW, appPassword.replace(" ", "")).apply()
    }

    override fun isConfigured(): Boolean = !prefs.getString(KEY_APP_PW, "").isNullOrBlank()

    fun saveAddresses(from: String, to: String, dir: String) {
        prefs.edit()
            .putString(KEY_FROM, from)
            .putString(KEY_TO, to)
            .putString(KEY_DIR, dir)
            .apply()
    }

    companion object {
        private const val KEY_FROM = "from"
        private const val KEY_APP_PW = "app_pw"
        private const val KEY_TO = "to"
        private const val KEY_DIR = "dir"
    }
}
