package br.com.estoque.rfid.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import br.com.estoque.rfid.databinding.ActivityHomeBinding
import br.com.estoque.rfid.rfid.RfidService

/**
 * Raiz da navegação: escolha entre os dois modos + gerenciar itens.
 * Como é a raiz, é aqui que o módulo RFID é liberado ao sair do app.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardSearch.setOnClickListener {
            startActivity(Intent(this, TargetSelectActivity::class.java))
        }
        binding.cardChecklist.setOnClickListener {
            startActivity(Intent(this, ChecklistActivity::class.java))
        }
        binding.cardManage.setOnClickListener {
            startActivity(Intent(this, ItemListActivity::class.java))
        }
    }

    override fun onDestroy() {
        if (isFinishing) RfidService.release()
        super.onDestroy()
    }
}
