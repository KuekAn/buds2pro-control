package com.kuek.budscontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The app's only network use: checking the public GitHub repo for a newer APK, when
 * the user taps "Check for updates" or has turned on the once-a-day check.
 * Reads release/latest.json from raw.githubusercontent.com over HTTPS. Android only
 * installs an update signed with the same key as the installed app.
 */
object Updater {
    private const val BASE = "https://raw.githubusercontent.com/KuekAn/buds2pro-control/main/"
    private const val JOB_ID = 77
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 3
    private const val MAX_APK = 50L shl 20

    class Info(val code: Long, val name: String, val apk: String, val notes: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
    fun auto(ctx: Context) = prefs(ctx).getBoolean("auto", false)
    fun lastCheck(ctx: Context) = prefs(ctx).getLong("last_check", 0)
    fun lastError(ctx: Context): String? = prefs(ctx).getString("install_error", null)
    fun setInstallError(ctx: Context, e: String?) = prefs(ctx).edit().putString("install_error", e).apply()

    fun installedCode(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun open(path: String): HttpURLConnection =
        (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Cache-Control", "no-cache")
        }

    /** Blocking; call off the main thread. Throws on network or format errors. */
    fun fetch(ctx: Context): Info {
        val c = open("release/latest.json")
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub answered ${c.responseCode}")
            val text = c.inputStream.use { String(it.readBytes().take(64 shl 10).toByteArray()) }
            val j = JSONObject(text)
            prefs(ctx).edit().putLong("last_check", System.currentTimeMillis()).apply()
            return Info(j.getLong("versionCode"), j.getString("versionName"), j.getString("apk"), j.optString("notes"))
        } finally { c.disconnect() }
    }

    /** Blocking download into the app's cache; [progress] gets 0-100. */
    fun download(ctx: Context, info: Info, progress: (Int) -> Unit): File {
        require(info.apk.startsWith("release/") && info.apk.endsWith(".apk")) { "Unexpected APK path" }
        val out = File(ctx.cacheDir, "update.apk")
        val c = open(info.apk)
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub answered ${c.responseCode}")
            val total = c.contentLengthLong
            if (total > MAX_APK) throw IllegalStateException("APK too large")
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 shl 10); var done = 0L; var last = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        done += n; if (done > MAX_APK) throw IllegalStateException("APK too large")
                        o.write(buf, 0, n)
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; progress(p) } }
                    }
                }
            }
        } finally { c.disconnect() }
        return out
    }

    /** Hands the APK to Android's installer; it asks the user to confirm (InstallReceiver). */
    fun install(ctx: Context, apk: File) {
        setInstallError(ctx, null)
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(ctx.packageName) }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("update.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val status = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(status.intentSender)
        }
    }

    fun setAuto(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("auto", on).apply()
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (on) js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(24L * 60 * 60 * 1000)
            .setPersisted(true)
            .build())
        else js.cancel(JOB_ID)
    }

    /** Background check result: notify once per new version. */
    fun notifyIfNew(ctx: Context, info: Info) {
        if (info.code <= installedCode(ctx)) return
        if (prefs(ctx).getLong("notified", 0) == info.code) return
        prefs(ctx).edit().putLong("notified", info.code).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MoreActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_buds)
            .setContentTitle("Buds Control ${info.name} is available")
            .setContentText(info.notes.ifBlank { "Open More to update" })
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

/** Daily check, only when the user turned it on. */
class UpdateJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread {
            try { Updater.notifyIfNew(this, Updater.fetch(this)) } catch (_: Exception) {}
            jobFinished(p, false)
        }.start()
        return true
    }
    override fun onStopJob(p: JobParameters) = true
}

/** Status from the installer session: shows Android's confirm screen, or records the error. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.setInstallError(ctx, null)
            else -> Updater.setInstallError(ctx,
                "Install failed ($st): ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown"}")
        }
    }
}
