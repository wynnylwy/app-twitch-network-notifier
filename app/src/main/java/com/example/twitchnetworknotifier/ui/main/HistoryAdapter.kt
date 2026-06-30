package com.example.twitchnetworknotifier.ui.main

import android.annotation.SuppressLint
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.twitchnetworknotifier.R
import com.example.twitchnetworknotifier.monitor.model.StatusEvent
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var items: List<StatusEvent> = emptyList()

    fun submitList(newList: List<StatusEvent>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_status_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.text_event)

        fun bind(event: StatusEvent) {
            val date = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date(event.timestampMillis))
            val time = DateFormat.getTimeFormat(itemView.context).format(Date(event.timestampMillis))
            val description = when (event.toState) {
                StreamStatus.LIVE -> "back online"
                StreamStatus.OFFLINE -> "went offline"
                StreamStatus.CONNECTION_ISSUE -> "connection issue"
                StreamStatus.UNKNOWN -> "status unknown"
            }
            textView.text = "$date | $time — $description"
        }
    }
}
