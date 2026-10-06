package com.megachel.tendamf6.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megachel.tendamf6.R
import com.megachel.tendamf6.data.Prefs
import com.megachel.tendamf6.net.TendaClient
import com.megachel.tendamf6.net.ModemNetwork
import com.megachel.tendamf6.net.modemNetwork
import com.megachel.tendamf6.widget.TendaWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PollWorker"

/**
 * One poll of the modem: fetch the status, cache it, redraw the widget and
 * notify when the unread counter grows.
 */
class PollWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!Prefs.isConfigured) {
            Prefs.lastError = "No password set — open the app"
            TendaWidgetProvider.refreshAll(applicationContext)
            return@withContext Result.success()
        }

        // Not a configuration problem, so no retry and no alarm: the phone is
        // simply somewhere else. Checking beats waiting out the connect timeout.
        Log.d(TAG, "poll starting, host=${Prefs.host}")
        val wifi = when (val network = modemNetwork(applicationContext, Prefs.host)) {
            is ModemNetwork.Available -> network.network
            ModemNetwork.NoWifi -> {
                Prefs.lastError = applicationContext.getString(R.string.no_wifi)
                TendaWidgetProvider.refreshAll(applicationContext)
                return@withContext Result.success()
            }
            ModemNetwork.WrongNetwork -> {
                Prefs.lastError = applicationContext.getString(R.string.wrong_network)
                TendaWidgetProvider.refreshAll(applicationContext)
                return@withContext Result.success()
            }
        }

        try {
            val client = TendaClient(Prefs.host, Prefs.password, wifi, Prefs)
            val status = client.fetchStatus()

            val previous = Prefs.lastUnread
            Prefs.lastStatus = status
            Prefs.lastUnread = status.unread
            Prefs.lastError = null

            if (Prefs.notifySms && status.unread > previous) {
                notifyNewSms(applicationContext, status.unread - previous, status.unread)
            }

            TendaWidgetProvider.refreshAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "poll failed", e)
            Prefs.lastError = e.message ?: "Connection error"
            TendaWidgetProvider.refreshAll(applicationContext)
            // A one-shot run is worth retrying, but only briefly: a manual refresh
            // that finally succeeds hours later is useless. Measured once at
            // run_attempt_count=11, still retrying four and a half hours after the
            // tap. The periodic run just comes back on schedule instead.
            if (isPeriodic() || runAttemptCount >= MAX_RETRIES) Result.success()
            else Result.retry()
        }
    }

    private fun isPeriodic(): Boolean = tags.contains(Scheduler.TAG_PERIODIC)

    companion object {
        /** Attempts 0, 1 and 2 — three tries in about a minute, then give up. */
        private const val MAX_RETRIES = 2
    }
}
