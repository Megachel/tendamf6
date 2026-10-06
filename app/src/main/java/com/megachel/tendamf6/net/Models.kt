package com.megachel.tendamf6.net

import org.json.JSONObject

/** Widget summary. Every field is exactly what getModules returns. */
data class TendaStatus(
    val battery: Int,
    val charging: Boolean,
    /** 0..3, same as the web interface (four icon steps). */
    val signal: Int,
    /** Ready to display as is: "4G", "3G", "EDGE". */
    val mode: String,
    /** networkStatus.status == 0. Otherwise the modem shows "No Service". */
    val online: Boolean,
    val operator: String,
    val unread: Int,
    val usedBytes: Long,
    /** Limit from flowData.monthData; only meaningful when flowMode == 1. */
    val limitBytes: Long,
    /** 0 — no limit, 1 — limit set, 2 — statistics only. */
    val flowMode: Int,
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    val hasLimit: Boolean get() = flowMode == 1 && limitBytes > 0
    val remainingBytes: Long get() = (limitBytes - usedBytes).coerceAtLeast(0)

    companion object {
        fun parse(root: JSONObject): TendaStatus {
            val battery = root.optJSONObject("batteryInfo") ?: JSONObject()
            val net = root.optJSONObject("networkStatus") ?: JSONObject()
            val msg = root.optJSONObject("unreadMessage") ?: JSONObject()
            val used = root.optJSONObject("usedFlow") ?: JSONObject()
            val flow = root.optJSONObject("flowData") ?: JSONObject()
            return TendaStatus(
                battery = battery.optInt("battery", -1),
                charging = battery.optBoolean("isCharge", false),
                signal = net.optInt("signal", 0),
                mode = net.optString("mode", ""),
                online = net.optInt("status", -1) == 0,
                operator = net.optString("profileName", ""),
                unread = msg.optInt("count", 0),
                // Counters arrive as strings — large values do not fit into an int.
                usedBytes = used.optString("usedData", "0").toLongOrNull() ?: 0L,
                limitBytes = flow.optString("monthData", "0").toLongOrNull() ?: 0L,
                flowMode = flow.optInt("mode", 0),
            )
        }
    }
}

data class SmsMessage(
    val id: String,
    val phone: String,
    val text: String,
    /** Unix time in seconds. */
    val time: Long,
    val isRead: Boolean,
) {
    companion object {
        fun parse(root: JSONObject): List<SmsMessage> {
            val threads = root.optJSONArray("smsList") ?: return emptyList()
            val out = mutableListOf<SmsMessage>()
            for (i in 0 until threads.length()) {
                val thread = threads.optJSONObject(i) ?: continue
                val phone = thread.optString("phone", "")
                val list = thread.optJSONArray("list") ?: continue
                for (j in 0 until list.length()) {
                    val m = list.optJSONObject(j) ?: continue
                    out += SmsMessage(
                        id = m.optString("id", ""),
                        phone = phone,
                        text = decodeContent(m.optString("content", "")),
                        time = m.optString("time", "0").toLongOrNull() ?: 0L,
                        isRead = m.optBoolean("isRead", true),
                    )
                }
            }
            return out.sortedByDescending { it.time }
        }

        /** Message bodies arrive as a UTF-16BE hex string. */
        fun decodeContent(content: String): String {
            val s = content.trim()
            if (s.length < 4 || s.length % 4 != 0) return content
            if (!s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return content
            return try {
                val bytes = ByteArray(s.length / 2) { i ->
                    ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16))
                        .toByte()
                }
                String(bytes, Charsets.UTF_16BE)
            } catch (_: Exception) {
                content
            }
        }
    }
}

/** Bytes in human-readable form with a 1024 divisor — the same math the web UI uses. */
fun formatBytes(bytes: Long): String {
    var value = bytes.toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (i <= 1) "%.0f %s".format(value, units[i]) else "%.1f %s".format(value, units[i])
}
