package com.example.protobuff.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.protobuff.R
import com.example.protobuff.data.model.FobDevice

/**
 * RecyclerView Adapter displaying scanned Keyfob devices.
 */
class DeviceAdapter(
    private val onItemClick: (FobDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<FobDevice>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newList: List<FobDevice>) {
        devices.clear()
        devices.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDeviceName: TextView = itemView.findViewById(R.id.tvDeviceName)
        private val tvDeviceAddress: TextView = itemView.findViewById(R.id.tvDeviceAddress)
        private val tvRssi: TextView = itemView.findViewById(R.id.tvRssi)

        @SuppressLint("SetTextI18n")
        fun bind(fobDevice: FobDevice) {
            tvDeviceName.text = fobDevice.name
            tvDeviceAddress.text = fobDevice.address
            tvRssi.text = "${fobDevice.rssi} dBm"

            itemView.setOnClickListener {
                onItemClick(fobDevice)
            }
        }
    }
}
