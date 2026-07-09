package br.com.estoque.rfid.data

data class StockItem(
    val epc: String,
    val name: String,
    val found: Boolean,
) {
    val displayName: String
        get() = name.ifBlank { epc }
}
