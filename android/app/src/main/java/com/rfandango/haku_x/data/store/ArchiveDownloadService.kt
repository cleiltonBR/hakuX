package com.rfandango.haku_x.data.store

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Keeps store transfers alive while the activity is backgrounded or the screen is locked. */
class ArchiveDownloadService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    startForegroundCompat(buildNotification(null))
    serviceScope.launch {
      ArchiveXboxDownloads.downloads.collect { items ->
        val active = items.filter {
          it.status == ArchiveDownload.Status.QUEUED ||
            it.status == ArchiveDownload.Status.DOWNLOADING ||
            it.status == ArchiveDownload.Status.EXTRACTING
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(active.firstOrNull()))
        if (active.isEmpty()) {
          delay(1200)
          if (!ArchiveXboxDownloads.downloads.value.any {
              it.status == ArchiveDownload.Status.QUEUED ||
                it.status == ArchiveDownload.Status.DOWNLOADING ||
                it.status == ArchiveDownload.Status.EXTRACTING
            }) stopSelf()
        }
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForegroundCompat(buildNotification(ArchiveXboxDownloads.downloads.value.firstOrNull {
      it.status == ArchiveDownload.Status.DOWNLOADING || it.status == ArchiveDownload.Status.QUEUED
    }))
    return START_NOT_STICKY
  }

  private fun startForegroundCompat(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else startForeground(NOTIFICATION_ID, notification)
  }

  private fun buildNotification(item: ArchiveDownload?): Notification {
    val statusText = when (item?.status) {
      ArchiveDownload.Status.QUEUED -> "Aguardando na fila"
      ArchiveDownload.Status.DOWNLOADING -> if (item.total > 0L) {
        "${(item.done * 100L / item.total).coerceIn(0L, 100L)}% · ${formatBytes(item.done)} / ${formatBytes(item.total)}"
      } else "${formatBytes(item.done)} baixados"
      ArchiveDownload.Status.EXTRACTING -> "Extraindo arquivos…"
      else -> "Preparando downloads"
    }
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(item?.title ?: "Downloads do HakuX")
      .setContentText(statusText)
      .setOnlyAlertOnce(true)
      .setOngoing(item != null)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setPriority(NotificationCompat.PRIORITY_LOW)
    if (item?.status == ArchiveDownload.Status.DOWNLOADING && item.total > 0L) {
      builder.setProgress(100, (item.done * 100L / item.total).toInt().coerceIn(0, 100), false)
    } else if (item?.status == ArchiveDownload.Status.DOWNLOADING || item?.status == ArchiveDownload.Status.EXTRACTING) {
      builder.setProgress(0, 0, true)
    } else builder.setProgress(0, 0, false)
    return builder.build()
  }

  private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit++ }
    return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Downloads do HakuX", NotificationManager.IMPORTANCE_LOW),
      )
    }
  }

  override fun onDestroy() { serviceScope.cancel(); super.onDestroy() }
  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    private const val CHANNEL_ID = "hakux_downloads"
    private const val NOTIFICATION_ID = 420
  }
}
