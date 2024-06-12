package net.ankio.bluetooth.adapter

import android.annotation.SuppressLint
import net.ankio.bluetooth.bluetooth.BleDevice
import net.ankio.bluetooth.databinding.ItemBluetoothBinding

/**
 * Ble设备适配器 传入ViewBinding
 * @description BleDeviceAdapter
 * @author llw
 * @date 2021/9/10 12:28
 */
class BleDeviceAdapter(data: MutableList<BleDevice>? = null) :
    ViewBindingAdapter<ItemBluetoothBinding, BleDevice>(data) {
    override fun onBindViewHolder(
        holder: ViewBindingHolder<ItemBluetoothBinding>,
        position: Int,
        item: BleDevice?
    ) {
        val binding = holder.vb
        if (item != null) {
            binding.tvDeviceName.text = item.company
            binding.tvMacAddress.text = item.address
            binding.tvData.text = item.data
            binding.tvRssi.text = "${item.rssi} dBm"
        }
        }

}