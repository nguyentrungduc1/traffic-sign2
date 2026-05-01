package com.ducnguyen.trafficsign.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ducnguyen.trafficsign.R
import com.ducnguyen.trafficsign.model.TrafficSign

class SignListAdapter : RecyclerView.Adapter<SignListAdapter.ViewHolder>() {

    private val items = mutableListOf<TrafficSign>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCode: TextView = view.findViewById(R.id.tv_code)
        val tvName: TextView = view.findViewById(R.id.tv_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_sign, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.tvCode.text = items[position].id
        holder.tvName.text = items[position].name
    }

    override fun getItemCount() = items.size

    fun addSign(sign: TrafficSign) {
        // Không thêm trùng liên tiếp
        if (items.firstOrNull()?.id == sign.id) return
        items.add(0, sign)
        notifyItemInserted(0)
    }
}
