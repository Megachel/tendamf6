package com.megachel.tendamf6.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.megachel.tendamf6.net.SessionStore
import com.megachel.tendamf6.net.TendaStatus
import org.json.JSONObject

private const val TAG = "Prefs"
private const val PLAIN = "tenda_prefs"
private const val SECURE = "tenda_secure"

object Prefs : SessionStore {
    const val DEFAULT_HOST = "192.168.0.1"
    const val DEFAULT_INTERVAL_MIN = 15L
    const val DEFAULT_OPACITY = 95

    private fun plain(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PLAIN, Context.MODE_PRIVATE)

    /**
     * The modem password is kept separately and encrypted. If the Keystore is
     * unavailable (rare, but it happens on broken ROMs) we must not crash —
     * the widget should at least be able to show an error.
     */
    private fun secure(context: Context): SharedPreferences = try {
        val app = context.applicationContext
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            SECURE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "Keystore unavailable, password not persisted", e)
        plain(context)
    }

    var host: String
        get() = plain(appContext).getString("host", DEFAULT_HOST) ?: DEFAULT_HOST
        set(v) = plain(appContext).edit().putString("host", v.trim()).apply()

    var password: String
        get() = secure(appContext).getString("password", "") ?: ""
        set(v) {
            // A cookie minted for the old password is useless — drop it so the
            // next call logs in instead of retrying a dead session.
            if (v != password) sessionCookie = null
            secure(appContext).edit().putString("password", v).apply()
        }

    /**
     * The session cookie is literally `password=<MD5 of the password><suffix>`,
     * so it is as sensitive as the password and lives in the encrypted store.
     */
    override var sessionCookie: String?
        get() = secure(appContext).getString("session_cookie", null)
        set(v) = secure(appContext).edit().putString("session_cookie", v).apply()

    var intervalMinutes: Long
        get() = plain(appContext).getLong("interval", DEFAULT_INTERVAL_MIN)
        set(v) = plain(appContext).edit().putLong("interval", v).apply()

    /** Widget background opacity in percent; 0 is fully transparent. */
    var backgroundOpacity: Int
        get() = plain(appContext).getInt("bg_opacity", DEFAULT_OPACITY)
        set(v) = plain(appContext).edit().putInt("bg_opacity", v.coerceIn(0, 100)).apply()

    var notifySms: Boolean
        get() = plain(appContext).getBoolean("notify_sms", true)
        set(v) = plain(appContext).edit().putBoolean("notify_sms", v).apply()

    /** Unread count from the previous poll — a notification fires when it grows. */
    var lastUnread: Int
        get() = plain(appContext).getInt("last_unread", 0)
        set(v) = plain(appContext).edit().putInt("last_unread", v).apply()

    /** Text of the last polling error; null means the last poll succeeded. */
    var lastError: String?
        get() = plain(appContext).getString("last_error", null)
        set(v) = plain(appContext).edit().putString("last_error", v).apply()

    val isConfigured: Boolean get() = password.isNotEmpty()

    /** Last successful snapshot — the widget draws it while a new poll runs. */
    var lastStatus: TendaStatus?
        get() {
            val raw = plain(appContext).getString("last_status", null) ?: return null
            return try {
                val o = JSONObject(raw)
                TendaStatus(
                    battery = o.getInt("battery"),
                    charging = o.getBoolean("charging"),
                    signal = o.getInt("signal"),
                    mode = o.getString("mode"),
                    online = o.getBoolean("online"),
                    operator = o.getString("operator"),
                    unread = o.getInt("unread"),
                    usedBytes = o.getLong("used"),
                    limitBytes = o.getLong("limit"),
                    flowMode = o.getInt("flowMode"),
                    fetchedAt = o.getLong("fetchedAt"),
                )
            } catch (e: Exception) {
                Log.e(TAG, "cached status is unreadable", e)
                null
            }
        }
        set(v) {
            if (v == null) return
            val o = JSONObject()
                .put("battery", v.battery)
                .put("charging", v.charging)
                .put("signal", v.signal)
                .put("mode", v.mode)
                .put("online", v.online)
                .put("operator", v.operator)
                .put("unread", v.unread)
                .put("used", v.usedBytes)
                .put("limit", v.limitBytes)
                .put("flowMode", v.flowMode)
                .put("fetchedAt", v.fetchedAt)
            plain(appContext).edit().putString("last_status", o.toString()).apply()
        }

    /** Assigned in Application.onCreate so the accessors need no parameter. */
    lateinit var appContext: Context
}
