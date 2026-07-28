package com.express.scanner.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.express.scanner.R
import com.express.scanner.databinding.ItemWaybillBinding
import com.express.scanner.model.Waybill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WaybillAdapter :
    ListAdapter<Waybill, WaybillAdapter.WaybillVH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Waybill>() {
            override fun areItemsTheSame(old: Waybill, new: Waybill) = old.number == new.number
            override fun areContentsTheSame(old: Waybill, new: Waybill) = old == new
        }
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WaybillVH {
        val binding = ItemWaybillBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return WaybillVH(binding)
    }

    override fun onBindViewHolder(holder: WaybillVH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class WaybillVH(private val binding: ItemWaybillBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Waybill) = with(binding) {
            tvNumber.text = item.number
            if (item.carrier.isNullOrEmpty()) {
                tvCarrier.visibility = android.view.View.GONE
            } else {
                tvCarrier.text = item.carrier
                tvCarrier.visibility = android.view.View.VISIBLE
            }
            if (item.scanned) {
                dotStatus.setBackgroundResource(R.drawable.bg_dot_green)
                val time = if (item.scanTime > 0) timeFmt.format(Date(item.scanTime)) else ""
                tvStatus.text = "已扫描 · $time"
            } else {
                dotStatus.setBackgroundResource(R.drawable.bg_dot_gray)
                tvStatus.text = "待扫描"
            }
            btnCopy.setOnClickListener {
                val cm = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("waybill", item.number))
                Toast.makeText(it.context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
