package br.com.estoque.rfid.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import br.com.estoque.rfid.R
import br.com.estoque.rfid.data.ItemRepository
import br.com.estoque.rfid.databinding.ActivityItemListBinding

class ItemListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityItemListBinding
    private lateinit var repository: ItemRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityItemListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = ItemRepository(this)

        binding.btSave.setOnClickListener { saveList() }
        binding.btGoScan.setOnClickListener {
            startActivity(Intent(this, ChecklistActivity::class.java))
        }
        binding.btClear.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        val items = repository.getItems()
        if (items.isEmpty()) {
            binding.tvCount.visibility = View.GONE
            binding.btGoScan.visibility = View.GONE
        } else {
            binding.tvCount.visibility = View.VISIBLE
            binding.tvCount.text = getString(R.string.list_count, items.size)
            binding.btGoScan.visibility = View.VISIBLE
            if (binding.etEpcs.text.isEmpty()) {
                binding.etEpcs.setText(items.joinToString("\n") { item ->
                    if (item.name.isBlank()) item.epc else "${item.epc},${item.name}"
                })
            }
        }
    }

    private fun saveList() {
        val (items, invalid) = ItemRepository.parseInput(binding.etEpcs.text.toString())
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.list_empty_warning, Toast.LENGTH_LONG).show()
            return
        }
        // Preserva o status "encontrado" de EPCs que já existiam
        val previousFound = repository.getItems().filter { it.found }.map { it.epc }.toHashSet()
        repository.saveItems(items.map { it.copy(found = it.epc in previousFound) })

        Toast.makeText(this, getString(R.string.list_saved, items.size), Toast.LENGTH_SHORT).show()
        if (invalid > 0) {
            Toast.makeText(this, getString(R.string.list_invalid_lines, invalid), Toast.LENGTH_LONG).show()
        }
        refreshState()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.list_clear_confirm_title)
            .setMessage(R.string.list_clear_confirm_msg)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                repository.clearAll()
                binding.etEpcs.setText("")
                refreshState()
            }
            .show()
    }

}
