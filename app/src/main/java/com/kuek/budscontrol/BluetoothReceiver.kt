package com.kuek.budscontrol

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the link when the buds connect to the phone. */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val dev: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        val target = BudsService.findDevice(ctx) ?: return
        if (dev.address != target.address) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                BudsStore.log("ACL connected: ${dev.name}")
                try { BudsService.start(ctx, BudsService.ACTION_CONNECT) } catch (e: Exception) {
                    BudsStore.log("auto-start blocked: ${e.message}")
                }
                try { EqService.apply(ctx) } catch (e: Exception) { BudsStore.log("EQ auto-start blocked: ${e.message}") }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                BudsStore.log("ACL disconnected")
                BudsService.instance?.onAclGone()
                BudsStore.update(ctx) { it.copy(connected = false, connecting = false, finding = false) }
            }
        }
    }
}
