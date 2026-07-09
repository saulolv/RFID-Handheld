package br.com.estoque.rfid.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import br.com.estoque.rfid.R
import br.com.estoque.rfid.databinding.ActivityConnectionBinding
import br.com.estoque.rfid.net.UplinkService
import org.json.JSONObject

/**
 * Configura e monitora a conexão com o servidor (WebSocket). O usuário informa o
 * endereço, conecta e vê o status ao vivo. Também dá atalho para o Wi-Fi do Android,
 * já que entrar numa rede é feito pelas configurações do sistema.
 */
class ConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UplinkService.init(applicationContext)

        binding.etUrl.setText(UplinkService.serverUrl)
        binding.etDevice.setText(UplinkService.deviceName)
        binding.etToken.setText(UplinkService.authToken)
        binding.switchAutoSend.isChecked = UplinkService.autoSend

        binding.btConnect.setOnClickListener { toggleConnection() }
        binding.btTest.setOnClickListener { sendTest() }
        binding.btWifi.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        binding.switchAutoSend.setOnCheckedChangeListener { _, _ -> persistConfig() }
    }

    override fun onResume() {
        super.onResume()
        UplinkService.onStateChanged = { state, error -> renderState(state, error) }
        renderState(UplinkService.state, UplinkService.lastError)
    }

    override fun onPause() {
        super.onPause()
        UplinkService.onStateChanged = null
        persistConfig()
    }

    private fun persistConfig() {
        UplinkService.saveConfig(
            url = binding.etUrl.text.toString(),
            device = binding.etDevice.text.toString(),
            token = binding.etToken.text.toString(),
            auto = binding.switchAutoSend.isChecked,
        )
    }

    private fun toggleConnection() {
        val connected = UplinkService.state == UplinkService.State.CONNECTED ||
            UplinkService.state == UplinkService.State.CONNECTING
        if (connected) {
            UplinkService.disconnect()
        } else {
            if (binding.etUrl.text.isBlank()) {
                Toast.makeText(this, R.string.conn_url_needed, Toast.LENGTH_LONG).show()
                return
            }
            persistConfig()
            UplinkService.connect()
        }
    }

    private fun sendTest() {
        if (binding.etUrl.text.isBlank()) {
            Toast.makeText(this, R.string.conn_url_needed, Toast.LENGTH_LONG).show()
            return
        }
        persistConfig()
        val json = JSONObject()
            .put("type", "test")
            .put("device", UplinkService.deviceName)
            .put("ts", System.currentTimeMillis())
        UplinkService.send(json.toString())
        Toast.makeText(this, R.string.conn_test_sent, Toast.LENGTH_SHORT).show()
        renderState(UplinkService.state, UplinkService.lastError)
    }

    private fun renderState(state: UplinkService.State, error: String?) {
        val (text, color) = when (state) {
            UplinkService.State.DISCONNECTED ->
                getString(R.string.conn_status_disconnected) to Color.parseColor("#7C8B92")
            UplinkService.State.CONNECTING ->
                getString(R.string.conn_status_connecting) to Color.parseColor("#E9A21B")
            UplinkService.State.CONNECTED ->
                getString(R.string.conn_status_connected) to Color.parseColor("#14C77B")
            UplinkService.State.ERROR ->
                getString(R.string.conn_status_error, error ?: "") to Color.parseColor("#F0531F")
        }
        binding.tvStatus.text = text
        binding.statusDot.background.setTint(color)

        val connected = state == UplinkService.State.CONNECTED || state == UplinkService.State.CONNECTING
        binding.btConnect.text = getString(
            if (connected) R.string.conn_disconnect else R.string.conn_connect
        )
        binding.btConnect.setBackgroundResource(
            if (connected) R.drawable.bg_button_stop else R.drawable.bg_button_start
        )

        val queued = UplinkService.queuedCount()
        binding.tvQueued.text = if (queued > 0) getString(R.string.conn_queued, queued) else ""
    }
}
