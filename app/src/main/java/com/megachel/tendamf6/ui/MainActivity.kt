package com.megachel.tendamf6.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.megachel.tendamf6.R
import com.megachel.tendamf6.data.Prefs
import com.megachel.tendamf6.databinding.ActivityMainBinding
import com.megachel.tendamf6.net.TendaClient
import com.megachel.tendamf6.net.ModemNetwork
import com.megachel.tendamf6.net.modemNetwork
import com.megachel.tendamf6.net.formatBytes
import com.megachel.tendamf6.widget.TendaWidgetProvider
import com.megachel.tendamf6.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val INTERVALS = listOf(15L, 30L, 60L, 180L)

/**
 * How often to refresh while the screen is actually in front of the user.
 * This costs nothing extra in battery terms — the phone is awake anyway — and
 * it is the only place where sub-minute latency is worth having. Background
 * polling stays on WorkManager's 15-minute floor.
 */
private const val LIVE_INTERVAL_MS = 5_000L

/**
 * Host and password are stored once typing pauses, not on every key: each
 * stored password drops the session cookie, and the live poll would log in
 * with half a password in the meantime.
 */
private const val TEXT_SAVE_DELAY_MS = 600L

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Pending debounced save of the text fields, see [TEXT_SAVE_DELAY_MS]. */
    private var textSave: Job? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.host.setText(Prefs.host)
        binding.password.setText(Prefs.password)
        binding.notify.isChecked = Prefs.notifySms

        binding.interval.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            INTERVALS.map { getString(R.string.every_n_minutes, it) },
        )
        binding.interval.setSelection(INTERVALS.indexOf(Prefs.intervalMinutes).coerceAtLeast(0))

        binding.opacity.progress = Prefs.backgroundOpacity
        showOpacity(Prefs.backgroundOpacity)
        binding.opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) =
                showOpacity(value)

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            // Applied on release rather than on every pixel of the drag, so the
            // widget is redrawn once instead of a hundred times.
            override fun onStopTrackingTouch(bar: SeekBar) {
                Prefs.backgroundOpacity = bar.progress
                TendaWidgetProvider.refreshAll(this@MainActivity)
            }
        })

        // There is no Save button: every control stores its value as it changes.
        binding.host.doAfterTextChanged { scheduleTextSave() }
        binding.password.doAfterTextChanged { scheduleTextSave() }
        binding.notify.setOnCheckedChangeListener { _, checked -> Prefs.notifySms = checked }
        binding.interval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                // The spinner also reports its initial selection, which changes nothing.
                val minutes = INTERVALS[pos]
                if (minutes == Prefs.intervalMinutes) return
                Prefs.intervalMinutes = minutes
                Scheduler.schedulePeriodic(this@MainActivity)
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        binding.update.setOnClickListener {
            saveText()
            lifecycleScope.launch { poll() }
        }
        binding.openSms.setOnClickListener {
            startActivity(Intent(this, SmsActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        startLiveUpdates()
    }

    /** Polls while the activity is in the foreground; stops the moment it is not. */
    private fun startLiveUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    if (Prefs.isConfigured) poll()
                    delay(LIVE_INTERVAL_MS)
                }
            }
        }
    }

    private fun showOpacity(value: Int) {
        binding.opacityValue.text = getString(R.string.percent_value, value)
    }

    /** Leaving the screen must not lose the last few characters typed. */
    override fun onPause() {
        if (textSave?.isActive == true) saveText()
        super.onPause()
    }

    private fun scheduleTextSave() {
        textSave?.cancel()
        textSave = lifecycleScope.launch {
            delay(TEXT_SAVE_DELAY_MS)
            saveText()
        }
    }

    private fun saveText() {
        textSave?.cancel()
        val host = binding.host.text.toString().trim()
        val password = binding.password.text.toString()
        if (host == Prefs.host && password == Prefs.password) return
        Prefs.host = host
        Prefs.password = password
        // The widget shows "set the password" until there is one.
        TendaWidgetProvider.refreshAll(this)
    }

    private suspend fun poll() {
        val text = withContext(Dispatchers.IO) {
            // Every exit from here has to leave Prefs.lastError truthful: the widget
            // draws its warning marker from it, and a stale value made it flag
            // trouble long after the trouble was over.
            val wifi = when (val network = modemNetwork(applicationContext, Prefs.host)) {
                is ModemNetwork.Available -> network.network
                ModemNetwork.NoWifi -> {
                    Prefs.lastError = getString(R.string.no_wifi)
                    return@withContext Prefs.lastError
                }
                ModemNetwork.WrongNetwork -> {
                    Prefs.lastError = getString(R.string.wrong_network)
                    return@withContext Prefs.lastError
                }
            }
            try {
                // Prefs doubles as the session store, so this reuses the cookie
                // the background worker already obtained instead of logging in.
                val status = TendaClient(Prefs.host, Prefs.password, wifi, Prefs).fetchStatus()
                Prefs.lastStatus = status
                Prefs.lastUnread = status.unread
                Prefs.lastError = null
                buildString {
                    appendLine(
                        getString(
                            R.string.res_battery, status.battery,
                            if (status.charging) getString(R.string.charging) else ""
                        )
                    )
                    appendLine(getString(R.string.res_signal, status.signal))
                    appendLine(
                        if (status.online)
                            getString(R.string.res_network, status.mode, status.operator)
                        else getString(R.string.no_service)
                    )
                    appendLine(getString(R.string.res_unread, status.unread))
                    append(
                        if (status.hasLimit)
                            getString(R.string.res_traffic_limit, formatBytes(status.remainingBytes))
                        else getString(R.string.res_traffic, formatBytes(status.usedBytes))
                    )
                }
            } catch (e: Exception) {
                Prefs.lastError = e.message ?: e.javaClass.simpleName
                getString(R.string.error_prefix, Prefs.lastError)
            }
        }
        binding.result.text = text
        TendaWidgetProvider.refreshAll(this)
    }
}
