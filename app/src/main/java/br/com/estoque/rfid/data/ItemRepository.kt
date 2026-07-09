package br.com.estoque.rfid.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistência simples da lista de itens em SharedPreferences (JSON).
 * EPCs são sempre normalizados (trim + uppercase, sem espaços internos)
 * tanto na gravação quanto na comparação.
 */
class ItemRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("stock_items", Context.MODE_PRIVATE)

    fun getItems(): List<StockItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                StockItem(
                    epc = obj.getString("epc"),
                    name = obj.optString("name", ""),
                    found = obj.optBoolean("found", false),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveItems(items: List<StockItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("epc", normalizeEpc(item.epc))
                    .put("name", item.name.trim())
                    .put("found", item.found)
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    /** Marca o item como encontrado. Retorna true se algum item mudou de estado. */
    fun markFound(epc: String): Boolean {
        val normalized = normalizeEpc(epc)
        val items = getItems()
        if (items.none { it.epc == normalized && !it.found }) return false
        saveItems(items.map { if (it.epc == normalized) it.copy(found = true) else it })
        return true
    }

    fun resetAllFound() {
        saveItems(getItems().map { it.copy(found = false) })
    }

    fun clearAll() {
        prefs.edit().remove(KEY_ITEMS).apply()
    }

    companion object {
        private const val KEY_ITEMS = "items_json"

        fun normalizeEpc(epc: String): String =
            epc.trim().replace(" ", "").uppercase()

        /** EPC válido: hexadecimal com pelo menos 8 caracteres. */
        fun isValidEpc(epc: String): Boolean =
            epc.length >= 8 && epc.all { it in '0'..'9' || it in 'A'..'F' }

        /**
         * Interpreta o texto colado pelo usuário: uma entrada por linha,
         * formato "EPC" ou "EPC,Nome do item". Deduplica por EPC.
         * Retorna (itens válidos, quantidade de linhas inválidas).
         */
        fun parseInput(text: String): Pair<List<StockItem>, Int> {
            val items = LinkedHashMap<String, StockItem>()
            var invalid = 0
            text.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val epc = normalizeEpc(trimmed.substringBefore(',', trimmed))
                val name = if (trimmed.contains(',')) trimmed.substringAfter(',').trim() else ""
                if (isValidEpc(epc)) {
                    items[epc] = StockItem(epc = epc, name = name, found = false)
                } else {
                    invalid++
                }
            }
            return items.values.toList() to invalid
        }
    }
}
