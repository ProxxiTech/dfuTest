package com.example.bledfutesteractivity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceScanAdapter(
    private val onClickListener: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceScanAdapter.ViewHolder>() {

    private val scanResults = mutableListOf<ScanResult>()
    private val deviceAddresses = mutableSetOf<String>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val deviceName: TextView = view.findViewById(R.id.device_name)
        val deviceAddress: TextView = view.findViewById(R.id.device_address)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_device, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = scanResults[position]
        holder.deviceName.text = result.device.name?: "Unnamed Device"
        holder.deviceAddress.text = result.device.address
        holder.itemView.setOnClickListener { onClickListener(result.device) }
    }

    override fun getItemCount(): Int = scanResults.size

    @SuppressLint("MissingPermission")
    fun addDevice(result: ScanResult) {
        if (!deviceAddresses.contains(result.device.address)) {
            scanResults.add(result)
            deviceAddresses.add(result.device.address)
            notifyItemInserted(scanResults.size - 1)
        }
    }

    fun clearDevices() {
        val size = scanResults.size
        scanResults.clear()
        deviceAddresses.clear()
        notifyItemRangeRemoved(0, size)
    }
}