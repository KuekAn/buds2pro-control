package com.kuek.budscontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Holds the RFCOMM link to the buds while they are connected to the phone,
 * keeps BudsStore up to date, and sends commands from the app, widgets and
 * notification. Stops itself when the link drops.
 */
class BudsService : Service() {

    companion object {
        const val ACTION_CONNECT = "connect"
        const val ACTION_DISCONNECT = "disconnect"
        const val ACTION_SET_MODE = "set_mode"
        const val ACTION_CYCLE_MODE = "cycle_mode"
        const val ACTION_FIND_START = "find_start"
        const val ACTION_FIND_STOP = "find_stop"
        const val ACTION_RESEAT = "reseat"
        const val ACTION_TOUCH = "touch"
        /** Send one raw message: EXTRA_HEX = "<id> <payload bytes...>" in hex. */
        const val ACTION_RAW = "raw"
        const val EXTRA_HEX = "hex"
        /** The buds' generic acknowledgement: [msg id, echoed payload...]. */
        private const val ACK = 0x42
        const val ACTION_SETTING = "setting"
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"
        const val SET_AMBIENT_LEVEL = "ambient_level"
        /** Test hook only: sets STREAM_MUSIC from the app and logs the result. */
        const val SET_MEDIA_VOLUME = "media_volume"
        const val SET_ONE_EARBUD = "one_earbud"
        const val SET_CUSTOM_AMBIENT = "custom_ambient"

        /** How long after the screen turns off the control link is paused. */
        private const val PARK_DELAY_MS = 20_000L

        /** The running service, so the Bluetooth receiver can tell it the buds left. */
        @Volatile var instance: BudsService? = null; private set

        fun setting(ctx: Context, key: String, value: Int) = ctx.startForegroundService(
            intent(ctx, ACTION_SETTING).putExtra(EXTRA_KEY, key).putExtra(EXTRA_VALUE, value))
        const val EXTRA_MODE = "mode"
        private const val CHANNEL = "buds"
        private const val NOTIF_ID = 1

        fun intent(ctx: Context, action: String, mode: Int = -1) =
            Intent(ctx, BudsService::class.java).setAction(action).putExtra(EXTRA_MODE, mode)

        fun start(ctx: Context, action: String, mode: Int = -1) {
            ctx.startForegroundService(intent(ctx, action, mode))
        }

        fun pending(ctx: Context, action: String, mode: Int = -1): PendingIntent =
            PendingIntent.getForegroundService(
                ctx, (action.hashCode() * 31) + mode, intent(ctx, action, mode),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Bonded Buds device: remembered address, else best name match. */
        fun findDevice(ctx: Context): BluetoothDevice? {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            val bonded = try { adapter.bondedDevices } catch (e: SecurityException) { null } ?: return null
            val saved = ctx.getSharedPreferences("buds", MODE_PRIVATE).getString("address", null)
            bonded.firstOrNull { it.address == saved }?.let { return it }
            val buds = bonded.filter { (it.name ?: "").contains("Buds", ignoreCase = true) }
            return buds.firstOrNull { (it.name ?: "").contains("Buds2 Pro", true) }
                ?: buds.firstOrNull { d -> d.uuids?.any { it.uuid == Protocol.SPP_UUID } == true }
                ?: buds.firstOrNull()
        }
    }

    private val writer = Executors.newSingleThreadExecutor()
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var stopping = false
    @Volatile private var pendingReseat = false
    /**
     * Link paused because the screen is off. EMUI's PowerGenie freezes background apps
     * soon after screen-off; a frozen app stops reading the RFCOMM socket while the buds
     * keep streaming debug packets, and the buds then drop the whole connection (music
     * too). So the control link is closed while the screen is off and reopened on wake.
     * Audio uses A2DP/HFP, which this does not touch.
     */
    @Volatile private var parked = false
    @Volatile private var finished = false
    private val main = Handler(Looper.getMainLooper())
    private val parkRunnable = Runnable { park() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> main.postDelayed(parkRunnable, PARK_DELAY_MS)
                Intent.ACTION_SCREEN_ON -> { main.removeCallbacks(parkRunnable); unpark() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BudsStore.logFile = getExternalFilesDir(null)?.let { java.io.File(it, "link.log") }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        startForeground(NOTIF_ID, buildNotification(BudsStore.get(this)))
        BudsStore.addListener(notifListener)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        })
        instance = this
    }

    private fun park() {
        if (parked || socket == null) return
        if (BudsStore.get(this).finding) { main.postDelayed(parkRunnable, PARK_DELAY_MS); return }
        BudsStore.log("screen off: pausing the control link")
        parked = true
        stopping = true
        try { socket?.close() } catch (_: IOException) {}
    }

    private fun unpark() {
        if (!parked) return
        parked = false
        Thread({
            val dev = findDevice(this)
            if (dev != null && Link.connectedHere(this, dev)) {
                BudsStore.log("screen on: reopening the control link")
                ensureConnected()
            } else stopSelfSafely()
        }, "buds-unpark").start()
    }

    /** Called by BluetoothReceiver when the buds disconnect from this phone. */
    fun onAclGone() { if (parked) { parked = false; stopSelfSafely() } }

    private val notifListener: (BudsStatus) -> Unit = { s ->
        if (!finished) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(BudsStore.get(this)))
        val mode = intent?.getIntExtra(EXTRA_MODE, -1) ?: -1
        // Any request (app opened, widget, notification) wakes a paused link.
        if (intent?.action != ACTION_DISCONNECT) { parked = false; main.removeCallbacks(parkRunnable) }
        when (intent?.action) {
            ACTION_DISCONNECT -> { shutdown(); return START_NOT_STICKY }
            ACTION_SET_MODE -> setMode(mode)
            ACTION_CYCLE_MODE -> {
                val next = when (BudsStore.get(this).mode) {
                    Protocol.MODE_ANC -> Protocol.MODE_AMBIENT
                    Protocol.MODE_AMBIENT -> Protocol.MODE_OFF
                    else -> Protocol.MODE_ANC
                }
                setMode(next)
            }
            ACTION_FIND_START -> {
                send(Protocol.encode(Protocol.FIND_START))
                BudsStore.update(this) { it.copy(finding = true) }
            }
            ACTION_RESEAT -> reseat()
            ACTION_RAW -> intent.getStringExtra(EXTRA_HEX)?.let { hex ->
                val bytes = hex.trim().split(Regex("[\\s,]+")).map { it.toInt(16).toByte() }
                send(Protocol.encode(bytes[0].toInt() and 0xFF, bytes.drop(1).toByteArray()))
            }
            ACTION_TOUCH -> Touch.packets(TouchConfig.load(this)).forEach { send(it) }
            ACTION_SETTING -> setting(intent.getStringExtra(EXTRA_KEY), intent.getIntExtra(EXTRA_VALUE, 0))
            ACTION_FIND_STOP -> {
                send(Protocol.encode(Protocol.FIND_STOP))
                BudsStore.update(this) { it.copy(finding = false) }
            }
        }
        ensureConnected()
        return START_NOT_STICKY
    }

    private fun setMode(mode: Int) {
        if (mode !in 0..2) return
        send(Protocol.encode(Protocol.NOISE_CONTROLS, byteArrayOf(mode.toByte())))
        BudsStore.update(this) { it.copy(mode = mode) }
    }

    /**
     * Software stand-in for physically re-seating the buds: switch noise
     * control off, then re-apply the current mode, so both buds re-apply it.
     */
    private fun reseat() {
        val s = BudsStore.get(this)
        if (s.reseating) return
        if (out == null) { pendingReseat = true; return } // runs once the link is up
        val mode = if (s.mode in 0..2) s.mode else Protocol.MODE_ANC
        val bounce = if (mode == Protocol.MODE_OFF) Protocol.MODE_ANC else Protocol.MODE_OFF
        BudsStore.update(this) { it.copy(reseating = true) }
        // One writer task with real pauses between steps. (Queueing the packets
        // separately let them go out back to back, which is why one tap used to
        // be not enough.) Two off/on cycles, matching what two taps did by hand.
        writer.execute {
            try {
                // Each step waits for the buds' ack, then lets them settle.
                // A command sent mid-transition is silently dropped.
                repeat(2) {
                    setModeAndWait(bounce)
                    setModeAndWait(mode)
                }
                // Make sure they really ended on the target mode.
                for (retry in 1..3) {
                    Thread.sleep(800)
                    if (BudsStore.get(this).mode == mode) break
                    BudsStore.log("reseat: buds report ${BudsStore.get(this).mode}, resending $mode")
                    setModeAndWait(mode)
                }
            } catch (e: Exception) {
                BudsStore.log("reseat failed: ${e.message}")
            }
            BudsStore.update(this) { it.copy(reseating = false) }
        }
    }

    private val ackLock = Object()
    @Volatile private var lastModeAck = -1

    /** Writer thread only: send a mode, wait up to 2 s for its ack, then 1 s to settle. */
    private fun setModeAndWait(m: Int) {
        synchronized(ackLock) { lastModeAck = -1 }
        writeNow(Protocol.encode(Protocol.NOISE_CONTROLS, byteArrayOf(m.toByte())))
        val deadline = System.currentTimeMillis() + 2000
        synchronized(ackLock) {
            while (lastModeAck != m) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) { BudsStore.log("no ack for mode $m"); break }
                ackLock.wait(left)
            }
        }
        Thread.sleep(1000)
    }

    /** Writes immediately on the writer thread. */
    private fun writeNow(p: ByteArray) {
        val o = out ?: throw IOException("not connected")
        o.write(p); o.flush()
        BudsStore.log("> ${Protocol.hex(p)}")
    }

    /** Sound settings from the app's sliders and switches. */
    private fun setting(key: String?, v: Int) {
        when (key) {
            SET_AMBIENT_LEVEL -> {
                // Buds2 Pro ignores AMBIENT_VOLUME (0x84) and NOISE_REDUCTION_LEVEL (0x83): they are
                // stored but have no effect. Its real ambient loudness is the custom ambient level per
                // side (0-4; "Extra loud" sets 4/4), so drive both sides together and keep the tone.
                val l = v.coerceIn(0, 4); val t = BudsStore.get(this).tone.coerceIn(0, 4)
                send(Protocol.encode(Protocol.CUSTOMIZE_AMBIENT,
                    byteArrayOf(1, l.toByte(), l.toByte(), t.toByte())))
                BudsStore.update(this) { it.copy(customOn = true, customL = l, customR = l, tone = t) }
            }
            SET_MEDIA_VOLUME -> {
                val am = getSystemService(android.media.AudioManager::class.java)
                val s = android.media.AudioManager.STREAM_MUSIC
                val before = am.getStreamVolume(s)
                runCatching { am.setStreamVolume(s, v, 0) }.onFailure { BudsStore.log("volume: $it") }
                BudsStore.log("volume $before -> asked $v -> now ${am.getStreamVolume(s)}")
            }
            SET_ONE_EARBUD -> {
                send(Protocol.encode(Protocol.ANC_WITH_ONE_EARBUD, byteArrayOf(v.toByte())))
                BudsStore.update(this) { it.copy(oneEarbud = v == 1) }
            }
            SET_CUSTOM_AMBIENT -> {
                // v packs on (bit 12), left (bits 8-11), right (4-7), tone (0-3).
                val on = (v shr 12) and 1; val l = (v shr 8) and 0xF; val r = (v shr 4) and 0xF; val t = v and 0xF
                send(Protocol.encode(Protocol.CUSTOMIZE_AMBIENT,
                    byteArrayOf(on.toByte(), l.toByte(), r.toByte(), t.toByte())))
                BudsStore.update(this) { it.copy(customOn = on == 1, customL = l, customR = r, tone = t) }
            }
        }
    }

    private fun send(packet: ByteArray) {
        queue.add(packet)
        flush()
    }

    private fun flush() {
        writer.execute {
            val o = out ?: return@execute
            while (true) {
                val p = queue.poll() ?: break
                try {
                    o.write(p); o.flush()
                    BudsStore.log("> ${Protocol.hex(p)}")
                } catch (e: IOException) {
                    BudsStore.log("write failed: ${e.message}")
                    break
                }
            }
        }
    }

    @Synchronized
    private fun ensureConnected() {
        if (worker?.isAlive == true) { flush(); return }
        stopping = false
        worker = Thread({ runLink() }, "buds-link").also { it.start() }
    }

    private fun runLink() {
        val device = findDevice(this)
        if (device == null) {
            BudsStore.log("No bonded Galaxy Buds found")
            BudsStore.update(this) { it.copy(connected = false, connecting = false) }
            stopSelfSafely(); return
        }
        // Sticky: never page buds that are with another device; that would pull them over.
        if (!Link.connectedHere(this, device)) {
            BudsStore.log("buds not connected to this phone: not opening the link")
            BudsStore.update(this) { it.copy(connected = false, connecting = false, finding = false, elsewhere = true) }
            queue.clear()
            stopSelfSafely(); return
        }
        BudsStore.update(this) { it.copy(connecting = true, elsewhere = false, name = device.name ?: it.name) }

        var sock: BluetoothSocket? = null
        for (attempt in 1..4) {
            if (stopping) return
            try {
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                sock = device.createRfcommSocketToServiceRecord(Protocol.SPP_UUID)
                sock.connect()
                break
            } catch (e: Exception) {
                BudsStore.log("connect $attempt failed: ${e.message}")
                try { sock?.close() } catch (_: IOException) {}
                sock = null
                Thread.sleep(2000L * attempt)
            }
        }
        if (sock == null) {
            BudsStore.update(this) { it.copy(connected = false, connecting = false, finding = false) }
            queue.clear()
            stopSelfSafely(); return
        }

        socket = sock
        out = sock.outputStream
        getSharedPreferences("buds", MODE_PRIVATE).edit().putString("address", device.address).apply()
        BudsStore.log("connected to ${device.name}")
        BudsStore.update(this) { it.copy(connected = true, connecting = false) }
        flush()
        if (pendingReseat) {
            pendingReseat = false
            // Give the buds a moment to report their current mode first.
            Thread { Thread.sleep(1500); reseat() }.start()
        }

        val parser = Protocol.Parser()
        val buf = ByteArray(1024)
        try {
            val input = sock.inputStream
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (p in parser.feed(buf, n)) handle(p)
            }
        } catch (e: IOException) {
            if (!stopping) BudsStore.log("link closed: ${e.message}")
        }
        out = null; socket = null
        try { sock.close() } catch (_: IOException) {}
        BudsStore.update(this) { it.copy(connected = false, connecting = false, finding = false) }
        if (!parked) stopSelfSafely()
    }

    private fun handle(p: Protocol.Packet) {
        BudsStore.log("< %02X%s %s".format(p.id, if (p.crcOk) "" else " (crc!)", Protocol.hex(p.payload)))
        val b = p.payload
        fun u(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        val before = BudsStore.get(this)
        handleStatus(p, b, ::u)
        // Ear safety: stop the find beep the moment a bud goes into an ear.
        val after = BudsStore.get(this)
        fun worn(s: BudsStatus) = s.placeL == 1 || s.placeR == 1
        if (after.finding && worn(after) && !worn(before)) {
            BudsStore.log("bud went in ear while beeping: stopping")
            send(Protocol.encode(Protocol.FIND_STOP))
            BudsStore.update(this) { it.copy(finding = false) }
        }
    }

    private fun handleStatus(p: Protocol.Packet, b: ByteArray, u: (Int) -> Int) {
        when (p.id) {
            Protocol.STATUS_UPDATED -> if (b.size >= 7) BudsStore.update(this) {
                it.copy(battL = u(1), battR = u(2), placeL = u(5) shr 4, placeR = u(5) and 0x0F,
                    battCase = u(6), updated = System.currentTimeMillis())
            }
            Protocol.EXTENDED_STATUS_UPDATED -> if (b.size >= 8) BudsStore.update(this) {
                // Byte 12 = noise control (0 off, 1 ANC, 2 ambient), verified on Buds2 Pro rev 0x0E.
                it.copy(battL = u(2), battR = u(3), placeL = u(6) shr 4, placeR = u(6) and 0x0F,
                    battCase = u(7), mode = if (u(12) in 0..2) u(12) else it.mode, ext = b,
                    ambientLevel = u(23), ancLevel = u(24), oneEarbud = u(28) == 1,
                    customOn = u(29) == 1, customL = u(30) shr 4, customR = u(30) and 0x0F, tone = u(31),
                    updated = System.currentTimeMillis())
            }
            Protocol.NOISE_CONTROLS_UPDATE -> if (b.isNotEmpty() && u(0) in 0..2) BudsStore.update(this) {
                it.copy(mode = u(0))
            }
            ACK -> if (u(0) == Protocol.NOISE_CONTROLS && u(1) in 0..2) {
                BudsStore.update(this) { it.copy(mode = u(1)) }
                synchronized(ackLock) { lastModeAck = u(1); ackLock.notifyAll() }
            }
        }
    }

    private fun shutdown() {
        stopping = true
        try { socket?.close() } catch (_: IOException) {}
        BudsStore.update(this) { it.copy(connected = false, connecting = false, finding = false) }
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        stopping = true
        finished = true
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        BudsStore.removeListener(notifListener)
        main.removeCallbacks(parkRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        if (instance === this) instance = null
        stopping = true
        try { socket?.close() } catch (_: IOException) {}
        writer.shutdown()
        super.onDestroy()
    }

    private fun buildNotification(s: BudsStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            s.connected -> "${Widgets.batt(s.battL, s.placeL)} L · ${Widgets.batt(s.battR, s.placeR)} R · ${s.modeLabel()}"
            s.connecting -> getString(R.string.connecting)
            else -> getString(R.string.disconnected)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_buds)
            .setContentTitle(title)
            .setContentText(if (s.battCase in 1..100) "Case ${s.battCase}%" else s.name)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "ANC", pending(this, ACTION_SET_MODE, Protocol.MODE_ANC)).build())
            .addAction(Notification.Action.Builder(null, "Ambient", pending(this, ACTION_SET_MODE, Protocol.MODE_AMBIENT)).build())
            .addAction(Notification.Action.Builder(null, "Off", pending(this, ACTION_SET_MODE, Protocol.MODE_OFF)).build())
            .build()
    }
}
