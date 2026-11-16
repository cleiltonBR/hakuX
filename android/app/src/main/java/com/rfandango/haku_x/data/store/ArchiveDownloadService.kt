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
      TorrentDownloadManager.downloads.collect { torrents ->
        val activeTorrentItem = torrents.firstOrNull {
          it.state == TorrentDownload.State.WAITING_METADATA ||
            it.state == TorrentDownload.State.CHECKING ||
            it.state == TorrentDownload.State.DOWNLOADING
        }
        val activeHttpItem = ArchiveXboxDownloads.downloads.value.firstOrNull {
          it.status == ArchiveDownload.Status.QUEUED ||
            it.status == ArchiveDownload.Status.DOWNLOADING ||
            it.status == ArchiveDownload.Status.EXTRACTING
        }
        getSystemService(NotificationManager::class.java).notify(
          NOTIFICATION_ID,
          buildNotification(activeHttpItem, activeTorrentItem),
        )
        val activeTorrent = torrents.any {
          it.state == TorrentDownload.State.WAITING_METADATA ||
            it.state == TorrentDownload.State.CHECKING ||
            it.state == TorrentDownload.State.DOWNLOADING
        }
        val activeHttp = ArchiveXboxDownloads.downloads.value.any {
          it.status == ArchiveDownload.Status.QUEUED ||
            it.status == ArchiveDownload.Status.DOWNLOADING ||
            it.status == ArchiveDownload.Status.EXTRACTING
        }
        if (!activeTorrent && !activeHttp) {
          delay(1200)
          val torrentStillActive = TorrentDownloadManager.downloads.value.any {
            it.state == TorrentDownload.State.WAITING_METADATA ||
              it.state == TorrentDownload.State.CHECKING ||
              it.state == TorrentDownload.State.DOWNLOADING
          }
          val httpStillActive = ArchiveXboxDownloads.downloads.value.any {
            it.status == ArchiveDownload.Status.QUEUED ||
              it.status == ArchiveDownload.Status.DOWNLOADING ||
              it.status == ArchiveDownload.Status.EXTRACTING
          }
          if (!torrentStillActive && !httpStillActive) stopSelf()
        }
      }
    }
    serviceScope.launch {
      ArchiveXboxDownloads.downloads.collect { items ->
        val active = items.filter {
          it.status == ArchiveDownload.Status.QUEUED ||
            it.status == ArchiveDownload.Status.DOWNLOADING ||
            it.status == ArchiveDownload.Status.EXTRACTING
        }
        val activeTorrent = TorrentDownloadManager.downloads.value.firstOrNull {
          it.state == TorrentDownload.State.WAITING_METADATA ||
            it.state == TorrentDownload.State.CHECKING ||
            it.state == TorrentDownload.State.DOWNLOADING
        }
        getSystemService(NotificationManager::class.java).notify(
          NOTIFICATION_ID,
          buildNotification(active.firstOrNull(), activeTorrent),
        )
        if (active.isEmpty() && TorrentDownloadManager.downloads.value.none {
            it.state == TorrentDownload.State.WAITING_METADATA ||
              it.state == TorrentDownload.State.CHECKING ||
              it.state == TorrentDownload.State.DOWNLOADING
          }) {
          delay(1200)
          val httpStillActive = ArchiveXboxDownloads.downloads.value.any {
            it.status == ArchiveDownload.Status.QUEUED ||
              it.status == ArchiveDownload.Status.DOWNLOADING ||
              it.status == ArchiveDownload.Status.EXTRACTING
          }
          val torrentStillActive = TorrentDownloadManager.downloads.value.any {
            it.state == TorrentDownload.State.WAITING_METADATA ||
              it.state == TorrentDownload.State.CHECKING ||
              it.state == TorrentDownload.State.DOWNLOADING
          }
          if (!httpStillActive && !torrentStillActive) stopSelf()
        }
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val activeHttp = ArchiveXboxDownloads.downloads.value.firstOrNull {
      it.status == ArchiveDownload.Status.DOWNLOADING || it.status == ArchiveDownload.Status.QUEUED
    }
    val activeTorrent = TorrentDownloadManager.downloads.value.firstOrNull {
      it.state == TorrentDownload.State.WAITING_METADATA ||
        it.state == TorrentDownload.State.CHECKING ||
        it.state == TorrentDownload.State.DOWNLOADING
    }
    startForegroundCompat(buildNotification(activeHttp, activeTorrent))
    return START_NOT_STICKY
  }

  private fun startForegroundCompat(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else startForeground(NOTIFICATION_ID, notification)
  }

  private fun buildNotification(
    item: ArchiveDownload?,
    torrent: TorrentDownload? = null,
  ): Notification {
    val statusText = when {
      torrent != null -> when (torrent.state) {
        TorrentDownload.State.WAITING_METADATA ->
          if (torrent.downloadRate > 0) "Recebendo metadados · ↓ ${formatRate(torrent.downloadRate)}"
          else "Conectado · aguardando metadados"
        TorrentDownload.State.CHECKING -> "Verificando arquivos"
        TorrentDownload.State.DOWNLOADING -> if (torrent.total > 0L) {
          "${(torrent.done * 100L / torrent.total).coerceIn(0L, 100L)}% · ${formatBytes(torrent.done)} / ${formatBytes(torrent.total)} · ↓ ${formatRate(torrent.downloadRate)}"
        } else "${formatBytes(torrent.done)} baixados · ↓ ${formatRate(torrent.downloadRate)}"
        else -> "Download BitTorrent"
      }
      item?.status == ArchiveDownload.Status.QUEUED -> "Aguardando na fila"
      item?.status == ArchiveDownload.Status.DOWNLOADING -> if (item.total > 0L) {
        "${(item.done * 100L / item.total).coerceIn(0L, 100L)}% · ${formatBytes(item.done)} / ${formatBytes(item.total)}"
      } else "${formatBytes(item.done)} baixados"
      item?.status == ArchiveDownload.Status.EXTRACTING -> "Extraindo arquivos…"
      else -> "Preparando downloads"
    }
    val title = torrent?.name ?: item?.title ?: "Downloads do HakuX"
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(title)
      .setContentText(statusText)
      .setOnlyAlertOnce(true)
      .setOngoing(torrent != null || item != null)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setPriority(NotificationCompat.PRIORITY_LOW)
    if (torrent != null && torrent.state == TorrentDownload.State.DOWNLOADING && torrent.total > 0L) {
      builder.setProgress(100, (torrent.done * 100L / torrent.total).toInt().coerceIn(0, 100), false)
    } else if (torrent != null && (torrent.state == TorrentDownload.State.WAITING_METADATA ||
        torrent.state == TorrentDownload.State.CHECKING || torrent.state == TorrentDownload.State.DOWNLOADING)) {
      builder.setProgress(0, 0, true)
    } else if (item?.status == ArchiveDownload.Status.DOWNLOADING && item.total > 0L) {
      builder.setProgress(100, (item.done * 100L / item.total).toInt().coerceIn(0, 100), false)
    } else if (item?.status == ArchiveDownload.Status.DOWNLOADING || item?.status == ArchiveDownload.Status.EXTRACTING) {
      builder.setProgress(0, 0, true)
    } else builder.setProgress(0, 0, false)
    return builder.build()
  }

  private fun formatRate(bytesPerSecond: Int): String =
    "${formatBytes(bytesPerSecond.toLong())}/s"

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
