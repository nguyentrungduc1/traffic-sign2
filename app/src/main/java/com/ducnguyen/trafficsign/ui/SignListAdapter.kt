package com.ducnguyen.trafficsign.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ducnguyen.trafficsign.R
import com.ducnguyen.trafficsign.model.TrafficSign

class SignListAdapter(private val context: Context) : RecyclerView.Adapter<SignListAdapter.ViewHolder>() {

    private val items = mutableListOf<TrafficSign>()
    private val maxItems = 20

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_icon)
        val tvCode: TextView  = view.findViewById(R.id.tv_code)
        val tvName: TextView  = view.findViewById(R.id.tv_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_sign, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sign = items[position]
        holder.tvCode.text = sign.id
        holder.tvName.text = sign.name

        // Tên file: lowercase, bỏ dấu chấm và dấu gạch dưới
        // Ví dụ: P.124A_1 → p124a1.png
        val iconName = sign.id.lowercase().replace(".", "").replace("_", "")
        try {
            val bitmap = context.assets.open("templates/$iconName.png")
                .use { BitmapFactory.decodeStream(it) }
            holder.ivIcon.setImageBitmap(bitmap)
        } catch (e: Exception) {
            holder.ivIcon.setImageDrawable(null)
        }
    }

    override fun getItemCount() = items.size

    fun addSign(sign: TrafficSign) {
        if (items.firstOrNull()?.id == sign.id) return
        items.add(0, sign)
        notifyItemInserted(0)
        if (items.size > maxItems) {
            items.removeAt(items.size - 1)
            notifyItemRemoved(items.size)
        }
    }
}
