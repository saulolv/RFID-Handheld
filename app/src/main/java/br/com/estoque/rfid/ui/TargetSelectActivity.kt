package br.com.estoque.rfid.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import br.com.estoque.rfid.R
import br.com.estoque.rfid.data.ItemRepository
import br.com.estoque.rfid.data.StockItem
import br.com.estoque.rfid.databinding.ActivityTargetSelectBinding
import br.com.estoque.rfid.net.UplinkService
import br.com.estoque.rfid.rfid.RfidService

/**
 * Define o alvo da busca: escaneando a etiqueta mais próxima (sem digitar o EPC)
 * ou escolhendo um item já cadastrado. Em seguida abre o Localizador.
 */
class TargetSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTargetSelectBinding
    private lateinit var repository: ItemRepository
    private var capturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTargetSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = ItemRepository(this)

        RfidService.setBeep(false)
        binding.btScan.setOnClickListener { startCapture() }
    }

    override fun onResume() {
        super.onResume()
        RfidService.enableScanHead(false)
        renderList()
    }

    override fun onPause() {
        super.onPause()
        RfidService.enableScanHead(true)
    }

    // ----- Lista de itens cadastrados -----

    private fun renderList() {
        val items = repository.getItems()
        binding.listContainer.removeAllViews()
        binding.tvListEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        items.forEach { item ->
            val row = TextView(this).apply {
                text = if (item.name.isBlank()) item.epc else "${item.displayName}\n${item.epc}"
                setTextColor(ContextCompat.getColor(this@TargetSelectActivity, R.color.signal_frost))
                textSize = 17f
                setBackgroundResource(R.drawable.bg_button_surface)
                val pad = dp(18)
                setPadding(pad, pad, pad, pad)
                setOnClickListener { goFind(item.epc, item.displayName) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            binding.listContainer.addView(row, lp)
        }
    }

    // ----- Captura por escaneamento -----

    private fun startCapture() {
        if (capturing) return
        capturing = true
        // Captura: potência máxima para detectar a etiqueta encostada de forma confiável
        RfidService.getMaxPower().takeIf { it > 0 }?.let { RfidService.setPower(it) }
        binding.tvScanLabel.text = getString(R.string.target_scan_reading)
        binding.btScan.isEnabled = false

        RfidService.captureNearestTag { epc ->
            capturing = false
            binding.tvScanLabel.text = getString(R.string.target_scan)
            binding.btScan.isEnabled = true
            if (epc == null) {
                Toast.makeText(this, R.string.target_scan_none, Toast.LENGTH_LONG).show()
            } else {
                onTagCaptured(epc)
            }
        }
    }

    private fun onTagCaptured(epc: String) {
        UplinkService.sendTagEvent(epc, null, "capture", found = false)
        val existing = repository.getItems().firstOrNull { it.epc == epc }
        AlertDialog.Builder(this)
            .setTitle(R.string.target_captured_title)
            .setMessage(existing?.displayName?.let { "$it\n\n$epc" } ?: epc)
            .setPositiveButton(R.string.target_search_now) { _, _ ->
                goFind(epc, existing?.displayName.orEmpty().ifBlank { epc })
            }
            .setNeutralButton(R.string.target_save_named) { _, _ -> promptSaveAndFind(epc) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun promptSaveAndFind(epc: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.target_name_hint)
            setText(repository.getItems().firstOrNull { it.epc == epc }?.name.orEmpty())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.target_save_named)
            .setView(container)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                val name = input.text.toString().trim()
                saveItem(epc, name)
                Toast.makeText(this, R.string.target_saved, Toast.LENGTH_SHORT).show()
                goFind(epc, name.ifBlank { epc })
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** Adiciona (ou atualiza o nome de) um item na lista, preservando os demais. */
    private fun saveItem(epc: String, name: String) {
        val items = repository.getItems().toMutableList()
        val idx = items.indexOfFirst { it.epc == epc }
        if (idx >= 0) {
            items[idx] = items[idx].copy(name = name)
        } else {
            items.add(StockItem(epc = epc, name = name, found = false))
        }
        repository.saveItems(items)
    }

    private fun goFind(epc: String, name: String) {
        startActivity(
            Intent(this, FindingActivity::class.java)
                .putExtra(FindingActivity.EXTRA_EPC, epc)
                .putExtra(FindingActivity.EXTRA_NAME, name)
        )
    }

    // Gatilho físico: dispara a captura (encostar + puxar)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in RfidService.TRIGGER_KEYCODES && event.repeatCount == 0) {
            startCapture()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
