package com.example.veilark.update

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.veilark.BuildConfig
import com.example.veilark.MainActivity
import com.example.veilark.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Small signed-manifest check only; never downloads an APK or touches the VPN. */
class UpdateNoticeJob : JobService() {
  private var task: kotlinx.coroutines.Job? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  override fun onStartJob(params: JobParameters): Boolean {
    if (!BuildConfig.SELF_UPDATE_ENABLED || !NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
    task = scope.launch {
      try {
        val update = UpdateManager.check()
        ensureActive()
        if (update != null) postNotice(update)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // Retry on the next periodic run, not an aggressive immediate loop.
      }
      ensureActive()
      jobFinished(params, false)
    }
    return true
  }
  override fun onStopJob(params: JobParameters): Boolean {
    task?.cancel()
    return false
  }
  override fun onDestroy() { scope.cancel(); super.onDestroy() }

  private fun postNotice(update: AppUpdate) {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val preferences = getSharedPreferences("update_notices", MODE_PRIVATE)
    if (!shouldNotifyRelease(update.versionCode, preferences.getInt("last_attempted", 0))) return
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.updates), NotificationManager.IMPORTANCE_DEFAULT))
    if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return
    val action = PendingIntent.getActivity(this, ID,
      Intent(this, MainActivity::class.java).setAction(OPEN_UPDATES)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    manager.notify(ID, NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_vpn_status)
      .setContentTitle(getString(R.string.update_available_version, update.versionName))
      .setContentText(getString(R.string.update_notice_body))
      .setContentIntent(action).setAutoCancel(true).setOnlyAlertOnce(true).build())
    preferences.edit().putInt("last_attempted", update.versionCode).apply()
  }
  companion object {
    const val OPEN_UPDATES = "com.example.veilark.OPEN_UPDATES"
    private const val ID = 26420
    private const val CHANNEL = "app_updates"
    fun schedule(context: Context) {
      val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
      if (!BuildConfig.SELF_UPDATE_ENABLED) { scheduler.cancel(ID); return }
      if (scheduler.getPendingJob(ID) != null) return
      scheduler.schedule(JobInfo.Builder(ID, ComponentName(context, UpdateNoticeJob::class.java))
        .setPeriodic(6L * 60 * 60 * 1000)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setRequiresBatteryNotLow(true).setPersisted(true).build())
    }
  }
}
