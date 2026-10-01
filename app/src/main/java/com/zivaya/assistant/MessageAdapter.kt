package com.zivaya.assistant

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.zivaya.assistant.databinding.ItemMessageBinding

data class ChatMessage(val text: String, val isUser: Boolean)

class MessageAdapter(
    private val items: MutableList<ChatMessage>
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    class VH(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        val tv = holder.binding.txtMessage
        tv.text = msg.text
        tv.setTextColor(Color.BLACK)

        val bg = GradientDrawable()
        bg.cornerRadius = 32f
        bg.setColor(Color.parseColor(if (msg.isUser) "#CFE8FF" else "#EDE7F6"))
        tv.background = bg

        val lp = tv.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (msg.isUser) Gravity.END else Gravity.START
        tv.layoutParams = lp
    }

    fun addMessage(msg: ChatMessage) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
    }
}
