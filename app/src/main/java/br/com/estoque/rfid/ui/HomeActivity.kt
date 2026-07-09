package br.com.estoque.rfid.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import br.com.estoque.rfid.R
import br.com.estoque.rfid.databinding.ActivityHomeBinding
import br.com.estoque.rfid.net.UplinkService
import br.com.estoque.rfid.rfid.RfidService

/**
 * Raiz da navegação: escolha entre os dois modos + gerenciar itens + conexão.
 * Como é a raiz, é aqui que o módulo RFID é liberado ao sair do app.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UplinkService.init(applicationContext)

        binding.cardSearch.setOnClickListener {
            startActivity(Intent(this, TargetSelectActivity::class.java))
        }
        binding.cardChecklist.setOnClickListener {
            startActivity(Intent(this, ChecklistActivity::class.java))
        }
        binding.cardManage.setOnClickListener {
            startActivity(Intent(this, ItemListActivity::class.java))
        }
        binding.cardConnection.setOnClickListener {
            startActivity(Intent(this, ConnectionActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        UplinkService.onStateChanged = { state, _ -> renderConnDot(state) }
        renderConnDot(UplinkService.state)
    }

    override fun onPause() {
        super.onPause()
        UplinkService.onStateChanged = null
    }

    private fun renderConnDot(state: UplinkService.State) {
        val (label, color) = when (state) {
            UplinkService.State.CONNECTED -> getString(R.string.conn_status_connected) to "#14C77B"
            UplinkService.State.CONNECTING -> getString(R.string.conn_status_connecting) to "#E9A21B"
            UplinkService.State.ERROR -> getString(R.string.conn_status_error, "") to "#F0531F"
            UplinkService.State.DISCONNECTED -> getString(R.string.home_connection_sub) to "#7C8B92"
        }
        binding.tvConnStatus.text = label
        binding.connDot.background.setTint(Color.parseColor(color))
    }

    override fun onDestroy() {
        if (isFinishing) RfidService.release()
        super.onDestroy()
    }
}
