package com.github.brugapp.brug

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// Adapter that binds the list of messages to the instances of ChatItemModel
class ChatMessagesListAdapter(private val messageList: ArrayList<ChatItemModel>) :
    RecyclerView.Adapter<ChatMessagesListAdapter.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMessagesListAdapter.ViewHolder {
        val itemView =
            LayoutInflater.from(parent.context).inflate(R.layout.chat_item_layout, parent, false)
        return ViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ChatMessagesListAdapter.ViewHolder, position: Int) {
        val message: ChatItemModel = messageList[position]
        holder.sender.text = message.sender
        holder.content.text = message.content
        holder.datetime.text = message.datetime
    }

    override fun getItemCount(): Int {
        return messageList.size
    }

    public class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val sender: TextView = itemView.findViewById(R.id.chat_item_sender)
        val datetime: TextView = itemView.findViewById(R.id.chat_item_datetime)
        val content: TextView = itemView.findViewById(R.id.chat_item_content)
    }
}