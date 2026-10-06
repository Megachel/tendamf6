package com.megachel.tendamf6.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.megachel.tendamf6.R
import com.megachel.tendamf6.data.Prefs
import com.megachel.tendamf6.databinding.ActivitySmsBinding
import com.megachel.tendamf6.net.SmsMessage
import com.megachel.tendamf6.net.TendaClient
import com.megachel.tendamf6.net.ModemNetwork
import com.megachel.tendamf6.net.modemNetwork
import com.megachel.tendamf6.widget.TendaWidgetProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Message list. Reading the list does NOT mark messages as read —
 * only an explicit viewSms call behind the button does.
 */
class SmsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmsBinding
    private var messages: List<SmsMessage> = emptyList()
    private val adapter = SmsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.list.adapter = adapter
        binding.markRead.setOnClickListener { markAllRead() }
        load()
    }

    private fun load() {
        binding.status.text = getString(R.string.loading)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val wifi = when (val network = modemNetwork(applicationContext, Prefs.host)) {
                    is ModemNetwork.Available -> network.network
                    ModemNetwork.NoWifi ->
                        return@withContext Result.failure(Exception(getString(R.string.no_wifi)))
                    ModemNetwork.WrongNetwork ->
                        return@withContext Result.failure(Exception(getString(R.string.wrong_network)))
                }
                runCatching { TendaClient(Prefs.host, Prefs.password, wifi, Prefs).fetchSms() }
            }
            result.onSuccess { list ->
                messages = list
                adapter.notifyDataSetChanged()
                val unread = list.count { !it.isRead }
                binding.status.text = getString(R.string.sms_summary, list.size, unread)
                binding.markRead.isEnabled = unread > 0
            }.onFailure { e ->
                binding.status.text = getString(R.string.error_prefix, e.message ?: "")
            }
        }
    }

    private fun markAllRead() {
        val ids = messages.filter { !it.isRead }.map { it.id }
        if (ids.isEmpty()) return
        binding.markRead.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val wifi = when (val network = modemNetwork(applicationContext, Prefs.host)) {
                    is ModemNetwork.Available -> network.network
                    ModemNetwork.NoWifi ->
                        return@withContext Result.failure(Exception(getString(R.string.no_wifi)))
                    ModemNetwork.WrongNetwork ->
                        return@withContext Result.failure(Exception(getString(R.string.wrong_network)))
                }
                runCatching { TendaClient(Prefs.host, Prefs.password, wifi, Prefs).markRead(ids) }
            }
            result.onFailure { e ->
                binding.status.text = getString(R.string.error_prefix, e.message ?: "")
            }
            Prefs.lastUnread = 0
            TendaWidgetProvider.refreshAll(this@SmsActivity)
            load()
        }
    }

    private inner class SmsAdapter : BaseAdapter() {
        private val stamp = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

        override fun getCount() = messages.size
        override fun getItem(position: Int) = messages[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_sms, parent, false)
            val message = messages[position]
            view.findViewById<TextView>(R.id.phone).text =
                if (message.isRead) message.phone else "● ${message.phone}"
            view.findViewById<TextView>(R.id.time).text =
                stamp.format(Date(message.time * 1000))
            view.findViewById<TextView>(R.id.text).text = message.text
            return view
        }
    }
}
