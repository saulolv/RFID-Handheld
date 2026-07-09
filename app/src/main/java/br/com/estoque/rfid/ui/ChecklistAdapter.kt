package br.com.estoque.rfid.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import br.com.estoque.rfid.R
import br.com.estoque.rfid.data.StockItem
import br.com.estoque.rfid.databinding.ItemCardBinding

class ChecklistAdapter(
    private val onLongClickPending: (StockItem) -> Unit,
) : ListAdapter<StockItem, ChecklistAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: StockItem) {
            binding.tvName.text = item.displayName
            binding.tvEpc.text = item.epc
            if (item.found) {
                binding.cardRoot.setBackgroundResource(R.drawable.bg_card_found)
                binding.tvBadge.visibility = View.VISIBLE
            } else {
                binding.cardRoot.setBackgroundResource(R.drawable.bg_card_pending)
                binding.tvBadge.visibility = View.GONE
            }
            binding.root.setOnLongClickListener {
                if (!item.found) {
                    onLongClickPending(item)
                    true
                } else {
                    false
                }
            }
        }
    }

    /** Animação curta de "flash" no card recém-encontrado. */
    fun flashItem(recycler: RecyclerView, epc: String) {
        val index = currentList.indexOfFirst { it.epc == epc }
        if (index < 0) return
        val holder = recycler.findViewHolderForAdapterPosition(index) ?: return
        holder.itemView.animate()
            .scaleX(1.08f).scaleY(1.08f).setDuration(120)
            .withEndAction {
                holder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }

    private object Diff : DiffUtil.ItemCallback<StockItem>() {
        override fun areItemsTheSame(oldItem: StockItem, newItem: StockItem) =
            oldItem.epc == newItem.epc

        override fun areContentsTheSame(oldItem: StockItem, newItem: StockItem) =
            oldItem == newItem
    }
}
