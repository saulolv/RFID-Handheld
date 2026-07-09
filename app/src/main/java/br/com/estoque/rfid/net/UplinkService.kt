package br.com.estoque.rfid.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Único ponto de contato com o servidor via WebSocket (OkHttp).
 * Envia as leituras capturadas para outro servidor. Mantém estado observável,
 * reconecta sozinho com backoff e enfileira mensagens enquanto estiver offline.
 * Todos os callbacks de estado chegam na main thread.
 */
object UplinkService {

    private const val TAG = "UplinkService"
    private const val PREFS = "uplink"
    private const val KEY_URL = "url"
    private const val KEY_DEVICE = "device"
    private const val KEY_TOKEN = "token"
    private const val KEY_AUTO = "auto_send"
    private const val KEY_DESIRED = "desired_connected"

    private const val MAX_QUEUE = 300
    private const val BACKOFF_START_MS = 1000L
    private const val BACKOFF_MAX_MS = 30_000L

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private var initialized = false

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val queue = ArrayDeque<String>()

    // Config
    var serverUrl: String = ""; private set
    var deviceName: String = ""; private set
    var authToken: String = ""; private set
    var autoSend: Boolean = true; private set

    /** true quando o usuário quer estar conectado (dispara reconexão automática). */
    private var desiredConnected = false
    private var backoffMs = BACKOFF_START_MS

    @Volatile
    var state: State = State.DISCONNECTED
        private set
    var lastError: String? = null
        private set

    /** Observador de mudança de estado (entregue na main thread). */
    var onStateChanged: ((State, String?) -> Unit)? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        val p = prefs()
        serverUrl = p.getString(KEY_URL, "") ?: ""
        deviceName = p.getString(KEY_DEVICE, "") ?: ""
        authToken = p.getString(KEY_TOKEN, "") ?: ""
        autoSend = p.getBoolean(KEY_AUTO, true)
        desiredConnected = p.getBoolean(KEY_DESIRED, false)
        // Reconecta ao abrir o app se o usuário havia deixado conectado
        if (desiredConnected && serverUrl.isNotBlank()) connect()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveConfig(url: String, device: String, token: String, auto: Boolean) {
        serverUrl = url.trim()
        deviceName = device.trim()
        authToken = token.trim()
        autoSend = auto
        prefs().edit()
            .putString(KEY_URL, serverUrl)
            .putString(KEY_DEVICE, deviceName)
            .putString(KEY_TOKEN, authToken)
            .putBoolean(KEY_AUTO, autoSend)
            .apply()
    }

    fun connect() {
        if (serverUrl.isBlank()) {
            setState(State.ERROR, "Endereço do servidor vazio")
            return
        }
        desiredConnected = true
        prefs().edit().putBoolean(KEY_DESIRED, true).apply()
        openSocket()
    }

    fun disconnect() {
        desiredConnected = false
        prefs().edit().putBoolean(KEY_DESIRED, false).apply()
        mainHandler.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "encerrado pelo usuário")
        webSocket = null
        setState(State.DISCONNECTED, null)
    }

    private fun openSocket() {
        setState(State.CONNECTING, null)
        val builder = Request.Builder().url(normalizeUrl(serverUrl))
        if (authToken.isNotBlank()) builder.addHeader("Authorization", "Bearer $authToken")
        try {
            webSocket = client.newWebSocket(builder.build(), listener)
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao abrir WebSocket", t)
            scheduleReconnect(t.message ?: "erro ao conectar")
        }
    }

    /** Aceita ws://, wss:// ou host:porta (assume ws://). */
    private fun normalizeUrl(url: String): String =
        if (url.startsWith("ws://") || url.startsWith("wss://")) url else "ws://$url"

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            backoffMs = BACKOFF_START_MS
            setState(State.CONNECTED, null)
            flushQueue()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            // Recebimento não é usado no fluxo atual; logado para depuração.
            Log.d(TAG, "msg do servidor: $text")
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            ws.close(1000, null)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (desiredConnected) scheduleReconnect("conexão fechada ($code)")
            else setState(State.DISCONNECTED, null)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket falhou: ${t.message}")
            if (desiredConnected) scheduleReconnect(t.message ?: "falha de conexão")
            else setState(State.ERROR, t.message)
        }
    }

    private val reconnectRunnable = Runnable { if (desiredConnected) openSocket() }

    private fun scheduleReconnect(error: String) {
        webSocket = null
        setState(State.ERROR, error)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
    }

    // ----- Envio -----

    /** Monta e envia (ou enfileira) um evento de leitura de etiqueta. */
    fun sendTagEvent(epc: String, rssi: Int?, mode: String, found: Boolean) {
        if (!autoSend) return
        val json = JSONObject()
            .put("type", "tag_read")
            .put("epc", epc)
            .put("mode", mode)
            .put("found", found)
            .put("device", deviceName)
            .put("ts", System.currentTimeMillis())
        if (rssi != null) json.put("rssi", rssi)
        send(json.toString())
    }

    /** Envia se conectado; senão enfileira (limitado a MAX_QUEUE, descarta o mais antigo). */
    fun send(payload: String) {
        val ws = webSocket
        if (state == State.CONNECTED && ws != null) {
            if (!ws.send(payload)) enqueue(payload)
        } else {
            enqueue(payload)
        }
    }

    private fun enqueue(payload: String) {
        if (queue.size >= MAX_QUEUE) queue.pollFirst()
        queue.offerLast(payload)
    }

    private fun flushQueue() {
        val ws = webSocket ?: return
        while (queue.isNotEmpty()) {
            val payload = queue.peekFirst() ?: break
            if (ws.send(payload)) queue.pollFirst() else break
        }
    }

    fun queuedCount(): Int = queue.size

    private fun setState(newState: State, error: String?) {
        state = newState
        lastError = error
        mainHandler.post { onStateChanged?.invoke(newState, error) }
    }
}
