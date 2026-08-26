package com.abhi.miniagent

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import java.util.UUID

/**
 * One "chat" in the hamburger drawer. Deliberately in-memory only (List<ChatSession> lives
 * in MainActivity's RAM) - switching sessions costs nothing on disk, and everything is gone
 * on app restart. If you want sessions to survive a restart later, that's what the
 * git-backed memory (GitMemory.kt) was heading toward - not wired together yet.
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New chat",
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val conversations: MutableMap<String, JSONArray> = mutableMapOf()
)

class SessionsAdapter(
    private val sessions: MutableList<ChatSession>,
    private val onClick: (ChatSession) -> Unit
) : RecyclerView.Adapter<SessionsAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvSessionTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return VH(v)
    }

    override fun getItemCount() = sessions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val session = sessions[position]
        holder.title.text = session.title
        holder.itemView.setOnClickListener { onClick(session) }
    }
}
