package com.tcam.tdlib

/**
 * Settings passed to TDLib's setTdlibParameters.
 *
 * [apiId] and [apiHash] identify the *application* to Telegram. Each developer
 * gets their own pair from https://my.telegram.org. They are not the user's
 * credentials; the user logs in with their phone number through the normal
 * Telegram flow.
 */
data class TdlibConfig(
    val apiId: Int,
    val apiHash: String,
    val databaseDirectory: String,
    val filesDirectory: String,
    val systemLanguageCode: String,
    val deviceModel: String,
    val systemVersion: String,
    val applicationVersion: String,
    val useTestDc: Boolean = false,
) {
    val isComplete: Boolean get() = apiId > 0 && apiHash.isNotBlank()
}

/** Supplies the key TDLib uses to encrypt its local database (kept in the Android Keystore by the app). */
fun interface DatabaseKeyProvider {
    fun databaseKey(): ByteArray
}
