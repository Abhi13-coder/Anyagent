package com.abhi.miniagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.abhi.miniagent.databinding.ActivityProvidersBinding
import com.abhi.miniagent.databinding.DialogEditProviderBinding

class ProvidersActivity : AppCompatActivity() {

    private lateinit var b: ActivityProvidersBinding
    private lateinit var store: ProviderStore
    private lateinit var items: MutableList<ProviderConfig>
    private lateinit var adapter: ProvidersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProvidersBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "Providers & models"

        store = ProviderStore(this)
        items = store.load()

        adapter = ProvidersAdapter(
            items,
            onChanged = { store.save(items) },
            onEdit = { showEditDialog(it) },
            onCopy = { label, value -> copyToClipboard(label, value) }
        )
        b.rvProviders.layoutManager = LinearLayoutManager(this)
        b.rvProviders.adapter = adapter

        b.btnAdd.setOnClickListener {
            showEditDialog(null)
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun pasteFromClipboard(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    }

    private fun showEditDialog(existing: ProviderConfig?) {
        val dialogBinding = DialogEditProviderBinding.inflate(LayoutInflater.from(this))
        existing?.let {
            dialogBinding.etLabel.setText(it.label)
            dialogBinding.etBaseUrl.setText(it.baseUrl)
            dialogBinding.etApiKey.setText(it.apiKey)
            dialogBinding.etModel.setText(it.model)
        }
        dialogBinding.btnPasteKey.setOnClickListener {
            dialogBinding.etApiKey.setText(pasteFromClipboard())
        }
        dialogBinding.btnPasteModel.setOnClickListener {
            dialogBinding.etModel.setText(pasteFromClipboard())
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add provider" else "Edit provider")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val label = dialogBinding.etLabel.text.toString().trim()
                val baseUrl = dialogBinding.etBaseUrl.text.toString().trim()
                val apiKey = dialogBinding.etApiKey.text.toString().trim()
                val model = dialogBinding.etModel.text.toString().trim()
                if (label.isEmpty() || baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
                    Toast.makeText(this, "All fields required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (existing != null) {
                    existing.label = label
                    existing.baseUrl = baseUrl
                    existing.apiKey = apiKey
                    existing.model = model
                    adapter.notifyDataSetChanged()
                } else {
                    items.add(ProviderConfig(label = label, baseUrl = baseUrl, apiKey = apiKey, model = model))
                    adapter.notifyItemInserted(items.size - 1)
                }
                store.save(items)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
