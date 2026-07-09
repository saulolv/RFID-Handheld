package br.com.estoque.rfid.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import br.com.estoque.rfid.R
import br.com.estoque.rfid.data.ItemRepository
import br.com.estoque.rfid.data.StockItem
import br.com.estoque.rfid.databinding.ActivityChecklistBinding
import br.com.estoque.rfid.rfid.RfidService

class ChecklistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChecklistBinding
    private lateinit var repository: ItemRepository
    private lateinit var adapter: ChecklistAdapter

    /** Estado em memória: EPC -> item. Fonte da verdade durante a sessão de leitura. */
    private val itemsByEpc = LinkedHashMap<String, StockItem>()
    private val foundSet = HashSet<String>()
    private val otherTags = HashSet<String>()

    private var scanning = false
    private var showOnlyPending = false
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChecklistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        repository = ItemRepository(this)
        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (_: RuntimeException) {
            null
        }

        adapter = ChecklistAdapter { item -> openFinding(item) }
        binding.recycler.layoutManager = GridLayoutManager(this, 2)
        binding.recycler.adapter = adapter

        binding.btToggle.setOnClickListener { toggleScan() }
        binding.btMenu.setOnClickListener { showMenu() }

        // O beep interno do módulo fica desligado: o app controla o próprio feedback
        RfidService.setBeep(false)
    }

    override fun onResume() {
        super.onResume()
        RfidService.enableScanHead(false)
        loadItems()
    }

    override fun onPause() {
        super.onPause()
        stopScan()
        RfidService.enableScanHead(true)
    }

    override fun onDestroy() {
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }

    // ----- Estado / lista -----

    private fun loadItems() {
        itemsByEpc.clear()
        foundSet.clear()
        repository.getItems().forEach { item ->
            itemsByEpc[item.epc] = item
            if (item.found) foundSet.add(item.epc)
        }
        render()
    }

    private fun render() {
        val all = itemsByEpc.values.toList()
        // Pendentes primeiro; encontrados vão para o fim
        val sorted = all.sortedBy { it.found }
        val visible = if (showOnlyPending) sorted.filter { !it.found } else sorted
        adapter.submitList(visible)

        val found = foundSet.size
        val total = all.size
        binding.tvProgress.text = getString(R.string.check_progress, found, total)
        binding.progressBar.max = total.coerceAtLeast(1)
        binding.progressBar.progress = found
        binding.tvBanner.visibility =
            if (total > 0 && found == total) View.VISIBLE else View.GONE

        if (otherTags.isNotEmpty()) {
            binding.tvOthers.visibility = View.VISIBLE
            binding.tvOthers.text = getString(R.string.check_others, otherTags.size)
        } else {
            binding.tvOthers.visibility = View.GONE
        }
    }

    // ----- Leitura RFID -----

    private fun toggleScan() {
        if (scanning) stopScan() else startScan()
    }

    private fun startScan() {
        if (scanning) return
        // Conferência de lote: potência máxima para ler tudo no alcance
        RfidService.getMaxPower().takeIf { it > 0 }?.let { RfidService.setPower(it) }
        val ok = RfidService.startInventory { epc, _ -> onTagRead(epc) }
        if (!ok) return
        scanning = true
        binding.btToggle.text = getString(R.string.check_stop)
        binding.btToggle.setBackgroundResource(R.drawable.bg_button_stop)
    }

    private fun stopScan() {
        if (!scanning) return
        RfidService.stopInventory()
        scanning = false
        binding.btToggle.text = getString(R.string.check_start)
        binding.btToggle.setBackgroundResource(R.drawable.bg_button_start)
    }

    /** Chega na main thread, EPC já normalizado. Inventário contínuo repete muito: dedup em O(1). */
    private fun onTagRead(epc: String) {
        val item = itemsByEpc[epc]
        if (item == null) {
            otherTags.add(epc)
            return
        }
        if (epc in foundSet) return

        foundSet.add(epc)
        itemsByEpc[epc] = item.copy(found = true)
        repository.markFound(epc)
        render()
        adapter.flashItem(binding.recycler, epc)

        val allFound = foundSet.size == itemsByEpc.size
        beep(if (allFound) 3 else 1)
        vibrate()
        if (allFound) stopScan()
    }

    // ----- Feedback -----

    private fun beep(times: Int) {
        val tg = toneGenerator ?: return
        repeat(times) { i ->
            binding.root.postDelayed({
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            }, i * 180L)
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(80)
        }
    }

    // ----- Menu / navegação -----

    private fun showMenu() {
        val popup = PopupMenu(this, binding.btMenu)
        popup.menu.add(0, MENU_RESET, 0, R.string.check_reset)
        popup.menu.add(
            0, MENU_FILTER, 1,
            if (showOnlyPending) R.string.check_filter_all else R.string.check_filter_pending
        )
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_RESET -> {
                    repository.resetAllFound()
                    otherTags.clear()
                    loadItems()
                    true
                }
                MENU_FILTER -> {
                    showOnlyPending = !showOnlyPending
                    render()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openFinding(item: StockItem) {
        stopScan()
        startActivity(
            Intent(this, FindingActivity::class.java)
                .putExtra(FindingActivity.EXTRA_EPC, item.epc)
                .putExtra(FindingActivity.EXTRA_NAME, item.displayName)
        )
    }

    // Gatilho físico do coletor: mesmo comportamento do botão na tela
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in RfidService.TRIGGER_KEYCODES && event.repeatCount == 0) {
            toggleScan()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val MENU_RESET = 1
        private const val MENU_FILTER = 2
    }
}
