package br.com.estoque.rfid.ui

import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import br.com.estoque.rfid.R
import br.com.estoque.rfid.databinding.ActivityFindingBinding
import br.com.estoque.rfid.rfid.RfidService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Localizador de um único item ("quente/frio"). Loop contínuo em background
 * chamando RfidService.findEpc(epc): o RSSI vira um sinal 0-100 suavizado que
 * pinta a tela inteira de frio -> morno -> quente. Ao cruzar o limiar de mira,
 * a tela INUNDA de verde ("AQUI!"). Nunca marca como encontrado nem para sozinho;
 * ao desviar o leitor, o verde recua.
 */
class FindingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFindingBinding
    private var epc: String = ""

    private var findJob: Job? = null
    private var toneGenerator: ToneGenerator? = null
    private var lastBeepAt = 0L
    private var locked = false

    private var maxPower = 30
    private var focusPower = DEFAULT_FOCUS

    // Cores da escala térmica, resolvidas uma vez
    private var cInk = 0
    private var cAbyss = 0
    private var cEmber = 0
    private var cFlare = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFindingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cInk = ContextCompat.getColor(this, R.color.signal_ink)
        cAbyss = ContextCompat.getColor(this, R.color.signal_abyss)
        cEmber = ContextCompat.getColor(this, R.color.signal_ember)
        cFlare = ContextCompat.getColor(this, R.color.signal_flare)

        epc = intent.getStringExtra(EXTRA_EPC).orEmpty()
        binding.tvName.text = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { epc }
        binding.tvEpc.text = epc
        binding.tvLockName.text = binding.tvName.text

        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (_: RuntimeException) {
            null
        }

        RfidService.setBeep(false)
        binding.btToggle.setOnClickListener { toggleFinding() }

        val prefs = getSharedPreferences("finder", MODE_PRIVATE)
        maxPower = RfidService.getMaxPower().takeIf { it > 0 } ?: 30
        focusPower = prefs.getInt(PREF_FOCUS, DEFAULT_FOCUS).coerceIn(1, maxPower)
        binding.btFocusDown.setOnClickListener { changeFocus(-1) }
        binding.btFocusUp.setOnClickListener { changeFocus(1) }
        updateFocusUi()

        renderSignal(0)
    }

    private fun changeFocus(delta: Int) {
        focusPower = (focusPower + delta).coerceIn(1, maxPower)
        getSharedPreferences("finder", MODE_PRIVATE).edit().putInt(PREF_FOCUS, focusPower).apply()
        RfidService.setPower(focusPower)
        updateFocusUi()
    }

    private fun updateFocusUi() {
        binding.tvFocus.text = getString(R.string.find_focus_value, focusPower, maxPower)
    }

    override fun onResume() {
        super.onResume()
        RfidService.enableScanHead(false)
        startFinding() // tela dedicada: já começa a varrer
    }

    override fun onPause() {
        super.onPause()
        stopFinding()
        RfidService.enableScanHead(true)
    }

    override fun onDestroy() {
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }

    private fun toggleFinding() {
        if (findJob != null) stopFinding() else startFinding()
    }

    private fun startFinding() {
        if (findJob != null || epc.isBlank()) return
        RfidService.setPower(focusPower) // bolha curta: força apontar/chegar perto
        binding.btToggle.text = getString(R.string.find_stop)
        binding.btToggle.setBackgroundResource(R.drawable.bg_button_stop)

        findJob = lifecycleScope.launch(Dispatchers.IO) {
            var smoothed = 0f
            while (isActive) {
                val rssi = RfidService.findEpc(epc)
                val target = if (rssi != null && rssi > 0) rssiToProximity(rssi).toFloat() else 0f
                // Média móvel exponencial: sobe rápido, mas sem tremer o número
                smoothed += SMOOTHING * (target - smoothed)
                val proximity = smoothed.toInt().coerceIn(0, 100)
                withContext(Dispatchers.Main) {
                    renderSignal(proximity)
                    maybeBeep(proximity)
                }
                delay(30)
            }
        }
    }

    private fun stopFinding() {
        findJob?.cancel()
        findJob = null
        binding.btToggle.text = getString(R.string.find_start)
        binding.btToggle.setBackgroundResource(R.drawable.bg_button_start)
        setLocked(false)
        renderSignal(0)
    }

    /** Mapeia o RSSI do SDK (RSSI_MIN..RSSI_MAX) para 0-100. Calibrar no dispositivo. */
    private fun rssiToProximity(rssi: Int): Int {
        if (rssi <= RSSI_MIN) return 0
        val ratio = (rssi - RSSI_MIN).toFloat() / (RSSI_MAX - RSSI_MIN)
        return (ratio * 100).toInt().coerceIn(0, 100)
    }

    private fun renderSignal(proximity: Int) {
        binding.tvSignal.text = proximity.toString()
        binding.root.setBackgroundColor(thermalColor(proximity))

        val (labelRes, labelColor) = when {
            proximity <= 3 -> R.string.find_no_signal to ContextCompat.getColor(this, R.color.signal_haze)
            proximity < 35 -> R.string.find_cold to COLD_LABEL
            proximity < 65 -> R.string.find_warm to cEmber
            else -> R.string.find_hot to cFlare
        }
        binding.tvState.setText(labelRes)
        binding.tvState.setTextColor(labelColor)

        // Trava/destrava com histerese para não piscar no limiar
        if (!locked && proximity >= LOCK_ON) setLocked(true)
        else if (locked && proximity <= LOCK_OFF) setLocked(false)
    }

    /** Interpola a cor de fundo: frio (ink/abyss) -> morno (ember) -> quente (flare). */
    private fun thermalColor(proximity: Int): Int {
        val p = proximity.coerceIn(0, 100)
        return when {
            p <= 30 -> ColorUtils.blendARGB(cInk, cAbyss, p / 30f)
            p <= 60 -> ColorUtils.blendARGB(cAbyss, cEmber, (p - 30) / 30f)
            else -> ColorUtils.blendARGB(cEmber, cFlare, (p - 60) / 40f)
        }
    }

    private fun setLocked(value: Boolean) {
        if (locked == value) return
        locked = value
        if (value) {
            binding.lockOverlay.visibility = View.VISIBLE
            binding.lockOverlay.animate().alpha(1f).setDuration(150).start()
            vibrate()
        } else {
            binding.lockOverlay.animate().alpha(0f).setDuration(200)
                .withEndAction { binding.lockOverlay.visibility = View.GONE }
                .start()
        }
    }

    /** Cadência do beep: mais rápido perto; contínuo/agudo quando travado. */
    private fun maybeBeep(proximity: Int) {
        if (proximity <= 3) return
        val interval = if (locked) 90L else (1000 - (1000 - 160) * (proximity / 100f)).toLong()
        val now = SystemClock.elapsedRealtime()
        if (now - lastBeepAt >= interval) {
            lastBeepAt = now
            val tone = if (locked) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP
            toneGenerator?.startTone(tone, 60)
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(140, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(140)
        }
    }

    // Gatilho físico: mesmo comportamento do botão
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in RfidService.TRIGGER_KEYCODES && event.repeatCount == 0) {
            toggleFinding()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_EPC = "extra_epc"
        const val EXTRA_NAME = "extra_name"

        /** Faixa típica do RSSI positivo do SDK Urovo; ajustar após teste no DT50P. */
        private const val RSSI_MIN = 0
        private const val RSSI_MAX = 80

        /** Suavização do sinal (0-1): maior = mais responsivo, menor = mais estável. */
        private const val SMOOTHING = 0.35f

        private const val PREF_FOCUS = "focus_power"

        /** Potência inicial de busca (dBm). Baixa de propósito: bolha pequena e focada. */
        private const val DEFAULT_FOCUS = 6

        /** Limiares de "mira travada" com histerese (evita piscar no limite). */
        private const val LOCK_ON = 92
        private const val LOCK_OFF = 75

        private val COLD_LABEL = Color.parseColor("#6FB2C9")
    }
}
