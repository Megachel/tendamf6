package com.megachel.tendamf6.net

import android.net.Network
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.random.Random
import org.json.JSONObject

private const val TAG = "TendaClient"

/** The firmware returns this errCode instead of data when the session is not authorized. */
const val ERR_NOT_LOGGED_IN = 1000

class TendaException(message: String) : IOException(message)

/**
 * The modem answers 302 to /login.html instead of JSON.
 *
 * This does not always mean the session is gone: /login/loginInfo returns such a
 * redirect simply because a session is already active, and /login/Auth sometimes
 * redirects for no visible reason while an immediate retry succeeds. So a
 * redirect is cured by retrying, and only a persistent one is treated as
 * "log in again".
 */
class RedirectedToLogin(message: String) : IOException(message)

/**
 * Somewhere to keep the session cookie between client instances.
 *
 * Without it every poll would log in from scratch, and since a new login kills
 * the previous session that means one dead session per poll — enough to keep
 * kicking you out of the modem's web interface.
 */
interface SessionStore {
    var sessionCookie: String?
}

/**
 * HTTP client for the Tenda MF6 web interface.
 *
 * Important: every successful login kills this client's previous session —
 * verified against the real device. That is why one instance keeps a single
 * session and re-logins only on errCode=1000, not before every request.
 */
class TendaClient(
    private val host: String,
    private val password: String,
    /** Wi-Fi network to send requests through. null means the default system route. */
    private val network: Network? = null,
    /** Persists the session across instances. null keeps it in memory only. */
    private val session: SessionStore? = null,
) {
    private var sessionCookie: String? = session?.sessionCookie
    // A stored cookie is assumed good; if it is stale the first call gets
    // errCode=1000 and re-logins, which costs one request instead of every poll
    // paying for a login.
    private var loggedIn = sessionCookie != null

    // --- transport -------------------------------------------------------

    private fun raw(path: String, body: String? = null): String {
        val url = URL("http://$host$path")
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.setRequestProperty("Referer", "http://$host/index.html")
        sessionCookie?.let { conn.setRequestProperty("Cookie", it) }

        try {
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            if (code in 300..399) {
                throw RedirectedToLogin(conn.getHeaderField("Location") ?: "no Location header")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw TendaException("HTTP $code from $path")
            }

            // The modem hands out the session as Set-Cookie: password=<MD5><6 chars>
            conn.headerFields["Set-Cookie"]?.forEach { header ->
                val pair = header.substringBefore(';')
                if (pair.startsWith("password=")) {
                    sessionCookie = pair
                    session?.sessionCookie = pair
                }
            }

            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Request with a redirect retry — cures the firmware's one-off 302s. */
    private fun json(path: String, body: String? = null, tries: Int = 3): JSONObject {
        var last: RedirectedToLogin? = null
        repeat(tries) { attempt ->
            try {
                val text = raw(path, body)
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (e: RedirectedToLogin) {
                last = e
                Log.d(TAG, "redirect on attempt ${attempt + 1}/$tries: ${e.message}")
                Thread.sleep(400)
            }
        }
        throw RedirectedToLogin("modem redirected $tries times in a row (${last?.message})")
    }

    /** errCode arrives as a number in getModules and as a string in setModules. */
    private fun JSONObject.errCode(): Int = optString("errCode", "0").toIntOrNull() ?: 0

    // --- authentication --------------------------------------------------

    private fun md5Upper(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    fun login() {
        // The browser loads login.html and calls loginInfo before signing in.
        // This warm-up absorbs the redirect and reports the session limit.
        try {
            val info = json("/login/loginInfo?modules=loginInfo&rand=${Random.nextDouble()}")
                .optJSONObject("loginInfo")
            if (info?.optBoolean("isLimit") == true) {
                throw TendaException(
                    "modem busy: session limit reached (${info.optInt("maxLimit")})"
                )
            }
        } catch (_: RedirectedToLogin) {
            // Expected when a session is already active.
        }

        val payload = JSONObject()
            .put("userName", "admin")
            .put("password", md5Upper(password))
            .toString()
        val res = json("/login/Auth", payload)
        val code = res.errCode()
        if (code != 0) {
            throw TendaException("login rejected (errCode=$code) — check the web UI password")
        }
        loggedIn = true
    }

    /** Request with re-login, both on errCode=1000 and on a persistent redirect. */
    private fun call(path: String, body: String? = null): JSONObject {
        if (!loggedIn) login()

        try {
            val res = json(path, body)
            if (res.errCode() != ERR_NOT_LOGGED_IN) return res
            Log.d(TAG, "errCode=1000, logging in again")
        } catch (e: RedirectedToLogin) {
            Log.d(TAG, "persistent redirect, logging in again: ${e.message}")
        }

        loggedIn = false
        sessionCookie = null
        session?.sessionCookie = null
        login()
        val res = json(path, body)
        if (res.errCode() == ERR_NOT_LOGGED_IN) {
            throw TendaException("session does not stick: errCode=1000 after re-login")
        }
        return res
    }

    // --- API -------------------------------------------------------------

    fun getModules(modules: String): JSONObject =
        call("/goform/getModules?rand=${Random.nextDouble()}&modules=$modules")

    fun setModules(modules: String, payload: JSONObject): JSONObject =
        call("/goform/setModules?modules=$modules", payload.toString())

    /** Everything the widget needs in a single request (~600 bytes of response). */
    fun fetchStatus(): TendaStatus = TendaStatus.parse(getModules(WIDGET_MODULES))

    fun fetchSms(): List<SmsMessage> = SmsMessage.parse(getModules("smsList"))

    /** Marks messages as read. The only call that changes state on the modem. */
    fun markRead(ids: List<String>) {
        if (ids.isEmpty()) return
        val body = JSONObject().put(
            "viewSms",
            JSONObject().put("id", org.json.JSONArray(ids))
        )
        setModules("viewSms", body)
    }

    companion object {
        const val WIDGET_MODULES =
            "batteryInfo,networkStatus,simStatus,unreadMessage,usedFlow,flowData"
    }
}
