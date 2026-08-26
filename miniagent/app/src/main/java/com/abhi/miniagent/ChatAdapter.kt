package com.abhi.miniagent

import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

enum class ChatRole { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    var text: String,
    val role: ChatRole,
    var label: String? = null,   // e.g. provider label, shown above the bubble text
    var bitmap: Bitmap? = null
)

/**
 * Renders the agent's log as chat bubbles instead of one long monospace TextView.
 * USER = right-aligned purple bubble. ASSISTANT = left-aligned dark bubble, normal text.
 * SYSTEM = left-aligned dark bubble, smaller monospace, dimmed - used for tool calls/results
 * and status lines, so it visually reads as "background noise" next to real replies.
 */
class ChatAdapter(private var items: MutableList<ChatMessage> = mutableListOf()) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val container: View = view.findViewById(R.id.bubbleContainer)
        val label: TextView = view.findViewById(R.id.tvBubbleLabel)
        val text: TextView = view.findViewById(R.id.tvBubbleText)
        val image: ImageView = view.findViewById(R.id.ivBubbleImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val params = holder.container.layoutParams as FrameLayout.LayoutParams

        when (item.role) {
            ChatRole.USER -> {
                params.gravity = Gravity.END
                holder.container.setBackgroundResource(R.drawable.bubble_user)
                holder.text.setTextColor(0xFFFFFFFF.toInt())
                holder.text.textSize = 14f
                holder.text.typeface = Typeface.DEFAULT
            }
            ChatRole.ASSISTANT -> {
                params.gravity = Gravity.START
                holder.container.setBackgroundResource(R.drawable.bubble_assistant)
                holder.text.setTextColor(0xFFEDEDF2.toInt())
                holder.text.textSize = 14f
                holder.text.typeface = Typeface.DEFAULT
            }
            ChatRole.SYSTEM -> {
                params.gravity = Gravity.START
                holder.container.setBackgroundResource(R.drawable.bubble_assistant)
                holder.text.setTextColor(0xFFA0A0AE.toInt())
                holder.text.textSize = 11f
                holder.text.typeface = Typeface.MONOSPACE
            }
        }
        holder.container.layoutParams = params

        if (!item.label.isNullOrBlank()) {
            holder.label.visibility = View.VISIBLE
            holder.label.text = item.label
        } else {
            holder.label.visibility = View.GONE
        }

        holder.text.text = item.text

        if (item.bitmap != null) {
            holder.image.visibility = View.VISIBLE
            holder.image.setImageBitmap(item.bitmap)
        } else {
            holder.image.visibility = View.GONE
        }
    }

    fun addMessage(msg: ChatMessage): Int {
        items.add(msg)
        val pos = items.size - 1
        notifyItemInserted(pos)
        return pos
    }

    /** Appends text to the bubble at [pos] (used for streaming-style incremental updates). */
    fun appendToMessage(pos: Int, extra: String) {
        if (pos !in items.indices) return
        items[pos].text += extra
        notifyItemChanged(pos)
    }

    /** Replaces the bubble's text outright - used to animate the typing/"..." indicator. */
    fun setText(pos: Int, text: String) {
        if (pos !in items.indices) return
        items[pos].text = text
        notifyItemChanged(pos)
    }

    fun removeAt(pos: Int) {
        if (pos !in items.indices) return
        items.removeAt(pos)
        notifyItemRemoved(pos)
    }

    fun attachImage(pos: Int, bitmap: Bitmap) {
        if (pos !in items.indices) return
        items[pos].bitmap = bitmap
        notifyItemChanged(pos)
    }

    fun clear() {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }

    /**
     * Swaps the adapter to a different session's message list. After this call, further
     * adds/removes mutate [newItems] directly - since ChatSession holds that same list
     * reference, switching sessions never requires copying messages back out.
     */
    fun replaceAll(newItems: MutableList<ChatMessage>) {
        items = newItems
        notifyDataSetChanged()
    }
}
