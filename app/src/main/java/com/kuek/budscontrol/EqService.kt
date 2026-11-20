package com.kuek.budscontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.sqrt

/**
 * Holds the EQ effects while the EQ is on (an effect only lives while this process
 * holds it, hence a foreground service). Two methods:
 *  - per app (default, what Wavelet does): music apps announce their audio session with
 *    ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, and each session gets its own effect.
 *  - all audio: one effect on the global output mix (session 0). Covers every app, but
 *    is deprecated by Android and sounded slightly less clean on the test phone.
 * With "only when buds are playing" the effects bypass unless A2DP is an output.
 */
class EqService : Service() {

    companion object {
        private const val CHANNEL = "eq"
        private const val NOTIF_ID = 2
        const val ACTION_APPLY = "apply"
        const val ACTION_OFF = "off"
        /** Test hook (adb, DUMP-protected): rebuild the effect with --ei frame_ms / bands / limiter. */
        const val ACTION_TUNE = "tune"

        /** The running service, so the sliders can update the effect without an intent per move. */
        @Volatile var live: EqService? = null; private set
        @Volatile var active: Boolean = false; private set
        /** Package names of the apps currently EQ'd in per-app mode. */
        @Volatile var apps: List<String> = emptyList(); private set

        fun apply(ctx: Context) {
            if (Eq.enabled(ctx)) ctx.startForegroundService(Intent(ctx, EqService::class.java).setAction(ACTION_APPLY))
            else if (live != null) ctx.startService(Intent(ctx, EqService::class.java).setAction(ACTION_OFF))
        }
    }

    /** Announced sessions (session id -> package), remembered even in all-audio mode. */
    private val sessions = LinkedHashMap<Int, String>()
    /** Live effects by session id (0 = global). */
    private val effects = HashMap<Int, DynamicsProcessing>()
    private var global = false
    private lateinit var audio: AudioManager

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>) = updateEnabled()
        override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>) = updateEnabled()
    }

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioManager.ERROR)
            if (id <= 0) return
            val pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "?"
            when (i.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> { sessions[id] = pkg; BudsStore.log("EQ: $pkg opened session $id") }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    sessions.remove(id); effects.remove(id)?.release(); BudsStore.log("EQ: $pkg closed session $id")
                }
            }
            sync()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Equaliser", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        startForeground(NOTIF_ID, notification())
        audio.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        registerReceiver(sessionReceiver, IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        })
        live = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification())
        if (intent?.action == ACTION_OFF || !Eq.enabled(this)) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_TUNE) {
            Eq.tune(this, intent.getIntExtra("frame_ms", -1), intent.getIntExtra("bands", -1), intent.getIntExtra("limiter", -1))
            releaseAll()
        }
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        live = null
        active = false
        apps = emptyList()
        audio.unregisterAudioDeviceCallback(devices)
        unregisterReceiver(sessionReceiver)
        releaseAll()
        super.onDestroy()
    }

    private fun releaseAll() {
        effects.values.forEach { it.release() }
        effects.clear()
    }

    /** Re-reads the method and profile and pushes them to every effect. */
    fun refresh() {
        if (Eq.global(this) != global) { releaseAll(); global = Eq.global(this) }
        Eq.setError(this, null)
        sync()
        effects.values.forEach { configure(it) }
        updateEnabled()
    }

    /** Makes the set of effects match the method: session 0 alone, or one per announced session. */
    private fun sync() {
        val wanted = if (global) setOf(0) else sessions.keys.toSet()
        (effects.keys - wanted).forEach { effects.remove(it)?.release() }
        for (id in wanted - effects.keys) create(id)?.let { configure(it); effects[id] = it }
        apps = if (global) emptyList() else sessions.filterKeys { it in effects }.values.distinct()
        updateEnabled()
    }

    private fun configure(effect: DynamicsProcessing) {
        val prof = Eq.current(this)
        val g = prof.gains
        try {
            // Android's DynamicsProcessing EQ works in FFT blocks and applies each band as a
            // flat step. Ten hard steps sounded smeared ("lossy"), so the 10-point curve is
            // spread smoothly over many narrow bands instead.
            val n = Eq.engineBands(this)
            val eq = DynamicsProcessing.Eq(true, true, n)
            for (i in 0 until n) {
                val lo = Eq.bandEdge(i, n); val hi = Eq.bandEdge(i + 1, n)
                eq.setBand(i, DynamicsProcessing.EqBand(true, hi.toFloat(), Eq.curveAt(g, sqrt(lo * hi))))
            }
            effect.setPreEqAllChannelsTo(eq)
            effect.setInputGainAllChannelsTo(Eq.preampOf(prof))
        } catch (e: Exception) {
            Eq.setError(this, "Could not set EQ: ${e.message}")
        }
    }

    private fun create(session: Int): DynamicsProcessing? = try {
        val frame = Eq.frameMs(this)
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            true, Eq.engineBands(this), false, 0, false, 0, Eq.limiter(this),
        ).setPreferredFrameDuration(frame).build()
        DynamicsProcessing(Int.MAX_VALUE, session, cfg).also { d ->
            if (Eq.limiter(this)) d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 5f, 100f, 4f, -1f, 0f))
            BudsStore.log("EQ effect on session $session: ${Eq.engineBands(this)} bands, $frame ms")
        }
    } catch (e: Throwable) {
        val what = if (session == 0) "the all-audio EQ" else "the EQ for ${sessions[session] ?: "session $session"}"
        Eq.setError(this, "This phone refused $what: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        BudsStore.log("EQ create failed on session $session: $e")
        null
    }

    private fun budsPlaying() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

    private fun updateEnabled() {
        val on = !Eq.budsOnly(this) || budsPlaying()
        var any = false
        for (d in effects.values) {
            try { d.enabled = on; any = any || d.enabled } catch (e: Exception) { Eq.setError(this, "Could not enable EQ: ${e.message}") }
        }
        active = any
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, EqActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when {
            active -> "${Eq.current(this).name} · on"
            !global && effects.isEmpty() -> "Waiting for a music app"
            else -> "Waiting for the buds"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_buds)
            .setContentTitle("Equaliser")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
