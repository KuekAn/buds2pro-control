package com.kuek.budscontrol

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Sticky" two-device behaviour: the buds stay with whichever device the user last
 * connected them to. Opening an RFCOMM socket to buds that are with another device
 * pages them and can pull them over, so the app only opens its control link when the
 * buds are already connected to this phone.
 */
object Link {

    /** True when the buds have a Bluetooth connection to this phone right now. */
    fun connectedHere(ctx: Context, device: BluetoothDevice): Boolean {
        // Hidden but long-standing BluetoothDevice.isConnected() (ACL state).
        try {
            return BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean
        } catch (_: Throwable) {}
        // Fallback: ask the audio profiles. Blocks briefly; call off the main thread.
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            val latch = CountDownLatch(1)
            var found = false
            val ok = adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    found = try { proxy.connectedDevices.any { it.address == device.address } } catch (_: Exception) { false }
                    adapter.closeProfileProxy(p, proxy)
                    latch.countDown()
                }
                override fun onServiceDisconnected(p: Int) {}
            }, profile)
            if (ok) latch.await(2, TimeUnit.SECONDS)
            if (found) return true
        }
        return false
    }

    /**
     * Asks this phone's audio profiles to connect the buds (what tapping them in the
     * Bluetooth panel does). Uses hidden APIs that some phones refuse; returns false
     * then, and the caller opens Bluetooth settings instead.
     */
    fun takeOver(ctx: Context, device: BluetoothDevice, done: (Boolean) -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return done(false)
        val ok = adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                val r = try {
                    proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, device) as Boolean
                } catch (e: Throwable) {
                    BudsStore.log("take over refused: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}")
                    false
                }
                adapter.closeProfileProxy(p, proxy)
                done(r)
            }
            override fun onServiceDisconnected(p: Int) {}
        }, BluetoothProfile.A2DP)
        if (!ok) done(false)
    }
}
