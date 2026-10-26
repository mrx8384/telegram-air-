package com.tcam.app.data

import android.content.Context
import androidx.core.content.edit
import com.tcam.app.BuildConfig
import com.tcam.app.security.KeystoreCrypto
import com.tcam.core.model.AutomationMode
import com.tcam.core.model.ChannelAction
import com.tcam.tdlib.DatabaseKeyProvider
import java.security.SecureRandom

/** Small persistent settings. Secrets are encrypted with [KeystoreCrypto]. */
class SettingsStore(context: Context) : DatabaseKeyProvider {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiId: Int
        get() = prefs.getInt(KEY_API_ID, 0).takeIf { it > 0 } ?: BuildConfig.TELEGRAM_API_ID
        set(v) = prefs.edit { putInt(KEY_API_ID, v) }

    var apiHash: String
        get() = prefs.getString(KEY_API_HASH, null)?.let { runCatching { String(KeystoreCrypto.decrypt(it)) }.getOrNull() }
            ?: BuildConfig.TELEGRAM_API_HASH
        set(v) = prefs.edit { putString(KEY_API_HASH, KeystoreCrypto.encrypt(v.trim().toByteArray())) }

    var mode: AutomationMode
        get() = runCatching { AutomationMode.valueOf(prefs.getString(KEY_MODE, null)!!) }.getOrDefault(AutomationMode.AUTO)
        set(v) = prefs.edit { putString(KEY_MODE, v.name) }

    var action: ChannelAction
        get() = runCatching { ChannelAction.valueOf(prefs.getString(KEY_ACTION, null)!!) }.getOrDefault(ChannelAction.PREVIEW)
        set(v) = prefs.edit { putString(KEY_ACTION, v.name) }

    /** Dry run is on by default: nothing changes until the user turns it off. */
    var dryRun: Boolean
        get() = prefs.getBoolean(KEY_DRY_RUN, true)
        set(v) = prefs.edit { putBoolean(KEY_DRY_RUN, v) }

    var channelText: String
        get() = prefs.getString(KEY_CHANNELS, "") ?: ""
        set(v) = prefs.edit { putString(KEY_CHANNELS, v) }

    /** Random 32-byte key for TDLib's database, created once and kept encrypted. */
    override fun databaseKey(): ByteArray {
        prefs.getString(KEY_DB_KEY, null)?.let { stored ->
            runCatching { return KeystoreCrypto.decrypt(stored) }
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit { putString(KEY_DB_KEY, KeystoreCrypto.encrypt(key)) }
        return key
    }

    private companion object {
        const val KEY_API_ID = "api_id"
        const val KEY_API_HASH = "api_hash_enc"
        const val KEY_MODE = "mode"
        const val KEY_ACTION = "action"
        const val KEY_DRY_RUN = "dry_run"
        const val KEY_CHANNELS = "channels"
        const val KEY_DB_KEY = "tdlib_db_key_enc"
    }
}
