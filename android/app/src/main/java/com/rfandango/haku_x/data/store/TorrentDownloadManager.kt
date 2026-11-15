package com.rfandango.haku_x.data.store

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class TorrentDownload(
  val id: String,
  val name: String,
  val source: String,
  val done: Long = 0L,
  val total: Long = 0L,
  val downloadRate: Int = 0,
  val uploadRate: Int = 0,
  val state: State = State.WAITING_METADATA,
  val error: String? = null,
) {
  enum class State { WAITING_METADATA, CHECKING, DOWNLOADING, PAUSED, COMPLETED, SEEDING, ERROR }
}

/** Native BitTorrent engine for user-supplied magnet URIs and .torrent files. */
object TorrentDownloadManager {
  private val engine = SessionManager()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutableDownloads = MutableStateFlow<List<TorrentDownload>>(emptyList())
  val downloads: StateFlow<List<TorrentDownload>> = mutableDownloads.asStateFlow()
  private val handles = ConcurrentHashMap<String, TorrentHandle>()
  private val sources = linkedMapOf<String, String>()
  private val pausedIds = ConcurrentHashMap.newKeySet<String>()
  private var context: Context? = null
  private var polling = false

  @Synchronized
  fun restore(appContext: Context) {
    context = appContext.applicationContext
    if (mutableDownloads.value.isNotEmpty()) return
    val saved = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, null) ?: return
    runCatching {
      val rows = JSONArray(saved)
      for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        val source = row.optString("source")
        if (source.isBlank()) continue
        if (source.startsWith("magnet:")) addMagnetInternal(appContext, source, persist = false)
        else {
          val file = File(source)
          if (file.isFile) addTorrentFileInternal(appContext, file, persist = false)
        }
      }
    }
  }

  fun addMagnet(appContext: Context, rawMagnet: String, displayName: String? = null) {
    addMagnetInternal(appContext.applicationContext, rawMagnet.trim(), persist = true, displayName = displayName)
  }

  @Synchronized
  private fun addMagnetInternal(
    appContext: Context,
    magnet: String,
    persist: Boolean,
    displayName: String? = null,
  ) {
    require(magnet.startsWith("magnet:?", ignoreCase = true)) { "Informe um link magnet válido." }
    val hash = extractBtih(magnet)
    require(hash != null) { "O magnet precisa conter um identificador btih hexadecimal válido." }
    if (sources.containsKey(hash)) return
    ensureEngine(appContext)
    engine.download(magnet, downloadDirectory(appContext), TorrentFlags.AUTO_MANAGED)
    sources[hash] = magnet
    mutableDownloads.value = mutableDownloads.value.filterNot { it.id == hash } +
      TorrentDownload(hash, displayName?.takeIf { it.isNotBlank() } ?: "Obtendo metadados…", magnet)
    if (persist) persist()
    startService(appContext)
    startPolling()
  }

  fun addTorrentFile(appContext: Context, file: File) {
    addTorrentFileInternal(appContext.applicationContext, file, persist = true)
  }

  @Synchronized
  private fun addTorrentFileInternal(appContext: Context, file: File, persist: Boolean) {
    require(file.isFile && file.length() > 0L) { "O arquivo .torrent está vazio ou não existe." }
    val info = TorrentInfo(file)
    require(info.isValid()) { "O arquivo .torrent é inválido." }
    val hash = info.infoHash().toHex()
    if (sources.containsKey(hash)) return
    ensureEngine(appContext)
    engine.download(info, downloadDirectory(appContext))
    sources[hash] = file.absolutePath
    mutableDownloads.value = mutableDownloads.value.filterNot { it.id == hash } +
      TorrentDownload(hash, file.nameWithoutExtension, file.absolutePath)
    if (persist) persist()
    startService(appContext)
    startPolling()
  }

  @Synchronized
  fun pause(id: String) {
    handles[id]?.takeIf { it.isValid() }?.pause()
    pausedIds.add(id)
    updateLocal(id) { it.copy(state = TorrentDownload.State.PAUSED, downloadRate = 0) }
  }

  @Synchronized
  fun resume(id: String) {
    pausedIds.remove(id)
    handles[id]?.takeIf { it.isValid() }?.resume()
    updateLocal(id) { it.copy(state = TorrentDownload.State.DOWNLOADING) }
  }

  @Synchronized
  fun remove(id: String) {
    handles.remove(id)?.takeIf { it.isValid() }?.let { engine.remove(it) }
    pausedIds.remove(id)
    sources.remove(id)
    mutableDownloads.value = mutableDownloads.value.filterNot { it.id == id }
    persist()
  }

  private fun ensureEngine(appContext: Context) {
    context = appContext.applicationContext
    if (!engine.isRunning) engine.start()
  }

  private fun downloadDirectory(appContext: Context): File =
    File(appContext.filesDir, "torrent-downloads").apply { mkdirs() }

  private fun startService(appContext: Context) {
    ContextCompat.startForegroundService(appContext, Intent(appContext, ArchiveDownloadService::class.java))
  }

  @Synchronized
  private fun startPolling() {
    if (polling) return
    polling = true
    scope.launch {
      try {
        while (isActive && synchronized(this@TorrentDownloadManager) { sources.isNotEmpty() }) {
          val snapshot = synchronized(this@TorrentDownloadManager) { sources.toMap() }
          snapshot.forEach { (id, _) ->
            runCatching {
              val handle = handles[id] ?: engine.find(Sha1Hash.parseHex(id))?.also { handles[id] = it }
              if (handle == null || !handle.isValid()) {
                updateLocal(id) { it.copy(state = TorrentDownload.State.WAITING_METADATA) }
              } else {
                val status = handle.status()
                val nativeState = status.state().name
                val state = when {
                  id in pausedIds -> TorrentDownload.State.PAUSED
                  nativeState == "CHECKING_FILES" || nativeState == "CHECKING_RESUME_DATA" -> TorrentDownload.State.CHECKING
                  nativeState == "DOWNLOADING_METADATA" -> TorrentDownload.State.WAITING_METADATA
                  nativeState == "SEEDING" -> TorrentDownload.State.SEEDING
                  nativeState == "FINISHED" -> TorrentDownload.State.COMPLETED
                  nativeState == "DOWNLOADING" -> TorrentDownload.State.DOWNLOADING
                  else -> TorrentDownload.State.DOWNLOADING
                }
                updateLocal(id) {
                  it.copy(
                    name = status.name().takeIf(String::isNotBlank) ?: it.name,
                    done = status.totalDone().coerceAtLeast(0L),
                    total = status.total().coerceAtLeast(0L),
                    downloadRate = status.downloadRate().coerceAtLeast(0),
                    uploadRate = status.uploadRate().coerceAtLeast(0),
                    state = state,
                    error = status.errorCode().getMessage().takeIf(String::isNotBlank),
                  )
                }
              }
            }.onFailure { error ->
              updateLocal(id) { it.copy(state = TorrentDownload.State.ERROR, error = error.message ?: "Falha no torrent.") }
            }
          }
          delay(1000)
        }
      } finally { polling = false }
    }
  }

  private inline fun updateLocal(id: String, transform: (TorrentDownload) -> TorrentDownload) {
    mutableDownloads.value = mutableDownloads.value.map { if (it.id == id) transform(it) else it }
  }

  private fun persist() {
    val appContext = context ?: return
    val array = JSONArray()
    synchronized(this) { sources.forEach { (id, source) -> array.put(JSONObject().put("id", id).put("source", source)) } }
    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, array.toString()).apply()
  }

  private fun extractBtih(magnet: String): String? =
    Regex("(?i)xt=urn:btih:([a-f0-9]{40})").find(magnet)?.groupValues?.get(1)?.lowercase()

  private const val PREFS = "hakux_torrent_downloads"
  private const val KEY_ITEMS = "sources"
}
