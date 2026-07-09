package br.com.estoque.rfid.rfid

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ubx.usdk.RFIDSDKManager
import com.ubx.usdk.listener.InitListener
import com.ubx.usdk.rfid.aidl.IRfidCallback

/**
 * Único ponto de contato com o SDK da Urovo (com.ubx.usdk).
 * Todos os callbacks são entregues já na main thread.
 * Nenhuma outra classe do app deve importar com.ubx.usdk.*.
 */
object RfidService {

    private const val TAG = "RfidService"

    /** Keycodes do gatilho físico nos coletores Urovo (DT50/DT50P). */
    val TRIGGER_KEYCODES = intArrayOf(515, 523)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var isInventoryRunning: Boolean = false
        private set

    private var onTagListener: ((epc: String, rssi: Int) -> Unit)? = null
    private var callbackRegistered = false

    private val sdkCallback = object : IRfidCallback {
        override fun onInventoryTag(epc: String?, data: String?, rssi: Int) {
            if (epc.isNullOrBlank()) return
            val normalized = epc.trim().uppercase()
            mainHandler.post { onTagListener?.invoke(normalized, rssi) }
        }

        override fun onInventoryTagEnd() {
            // Fim de um ciclo de inventário; nada a fazer no fluxo atual.
        }
    }

    /** Liga o módulo RFID. Assíncrono e lento (segundos). onReady é chamado na main thread. */
    fun init(context: Context, onReady: (Boolean) -> Unit) {
        if (isReady) {
            mainHandler.post { onReady(true) }
            return
        }
        try {
            RFIDSDKManager.getInstance().init(context.applicationContext, InitListener { status ->
                isReady = status
                if (status) {
                    safeSdkCall("setRssiInDbm") {
                        RFIDSDKManager.getInstance().rfidManager?.setRssiInDbm(false)
                    }
                }
                mainHandler.post { onReady(status) }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "init falhou", t)
            isReady = false
            mainHandler.post { onReady(false) }
        }
    }

    /** Inicia inventário contínuo. onTag(epc, rssi) chega na main thread, já normalizado (uppercase). */
    fun startInventory(onTag: (epc: String, rssi: Int) -> Unit): Boolean {
        if (!isReady) return false
        onTagListener = onTag
        val ok = safeSdkCall("startInventory") {
            val mgr = RFIDSDKManager.getInstance().rfidManager ?: return@safeSdkCall false
            if (!callbackRegistered) {
                mgr.registerCallback(sdkCallback)
                callbackRegistered = true
            }
            // timeout 0 = inventário contínuo até stopInventory() (padrão do demo oficial)
            mgr.startInventoryWithTimeout(0) == 0
        } ?: false
        isInventoryRunning = ok
        return ok
    }

    fun stopInventory() {
        if (!isReady) return
        safeSdkCall("stopInventory") {
            RFIDSDKManager.getInstance().rfidManager?.stopInventory()
        }
        isInventoryRunning = false
        onTagListener = null
    }

    /**
     * Uma rodada de busca da tag específica (modo Geiger). Chamada BLOQUEANTE —
     * usar em thread/coroutine de background, em loop. Retorna o RSSI (escala
     * positiva do SDK, decai a 0) ou null se a tag não foi vista nesta rodada.
     */
    fun findEpc(epc: String): Int? {
        if (!isReady) return null
        return safeSdkCall("findEpc") {
            RFIDSDKManager.getInstance().rfidManager?.findEpc(epc)?.rssi
        }
    }

    /**
     * Captura a etiqueta MAIS PRÓXIMA (maior RSSI) numa janela curta de inventário.
     * Serve para definir o alvo da busca sem digitar o EPC: o operador encosta o
     * leitor na etiqueta, puxa o gatilho e o app pega a de sinal mais forte.
     * onResult(epc|null) é entregue na main thread ao fim da janela.
     */
    fun captureNearestTag(windowMs: Long = 1200L, onResult: (epc: String?) -> Unit) {
        if (!isReady) {
            mainHandler.post { onResult(null) }
            return
        }
        var bestEpc: String? = null
        var bestRssi = Int.MIN_VALUE
        val started = startInventory { epc, rssi ->
            if (rssi > bestRssi) {
                bestRssi = rssi
                bestEpc = epc
            }
        }
        if (!started) {
            mainHandler.post { onResult(null) }
            return
        }
        mainHandler.postDelayed({
            stopInventory()
            onResult(bestEpc)
        }, windowMs)
    }

    /** Potência máxima suportada pelo leitor (dBm). -1 se indisponível. */
    fun getMaxPower(): Int =
        safeSdkCall("getSupportMaxOutputPower") {
            RFIDSDKManager.getInstance().rfidManager?.supportMaxOutputPower ?: -1
        } ?: -1

    /**
     * Define a potência de saída (dBm, 0..max). MENOR potência = alcance curto =
     * leitura mais "focada" (força apontar/chegar perto). Retorna true se aplicou.
     */
    fun setPower(dbm: Int): Boolean =
        safeSdkCall("setOutputPower") {
            RFIDSDKManager.getInstance().rfidManager?.setOutputPower(dbm) == 0
        } ?: false

    fun getPower(): Int =
        safeSdkCall("getOutputPower") {
            RFIDSDKManager.getInstance().rfidManager?.outputPower ?: -1
        } ?: -1

    fun setBeep(enabled: Boolean) {
        if (!isReady) return
        safeSdkCall("setBeepEnable") {
            RFIDSDKManager.getInstance().rfidManager?.setBeepEnable(enabled)
        }
    }

    /** Controle do gatilho pelo app (espelha o demo oficial: false em onResume, true em onPause). */
    fun enableScanHead(enabled: Boolean) {
        safeSdkCall("enableScanHead") {
            RFIDSDKManager.getInstance().enableScanHead(enabled)
        }
    }

    /** Libera o módulo. Chamar apenas ao sair do app de vez. */
    fun release() {
        stopInventory()
        safeSdkCall("release") {
            RFIDSDKManager.getInstance().release()
        }
        isReady = false
        callbackRegistered = false
    }

    private inline fun <T> safeSdkCall(name: String, block: () -> T): T? =
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "Chamada ao SDK falhou: $name", t)
            null
        }
}
