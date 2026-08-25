package com.abhi.miniagent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class ProvidersAdapter(
    private val items: MutableList<ProviderConfig>,
    private val onChanged: () -> Unit,
    private val onEdit: (ProviderConfig) -> Unit,
    private val onCopy: (String, String) -> Unit // (label, value) -> toast/clipboard
) : RecyclerView.Adapter<ProvidersAdapter.VH>() {

    class VH(val binding: com.abhi.miniagent.databinding.ItemProviderBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = com.abhi.miniagent.databinding.ItemProviderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        b.tvLabel.text = item.label
        b.tvModel.text = item.model
        b.tvBaseUrl.text = item.baseUrl
        b.tbActive.isChecked = item.active

        b.tbActive.setOnCheckedChangeListener { _, checked ->
            item.active = checked
            onChanged()
        }
        b.btnEdit.setOnClickListener { onEdit(item) }
        b.btnCopyKey.setOnClickListener { onCopy("API key", item.apiKey) }
        b.btnCopyModel.setOnClickListener { onCopy("Model", item.model) }
        b.btnDelete.setOnClickListener {
            items.removeAt(holder.bindingAdapterPosition)
            notifyItemRemoved(position)
            onChanged()
        }
    }
}
