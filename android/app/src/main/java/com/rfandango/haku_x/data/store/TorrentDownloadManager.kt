package com.rfandango.haku_x.data.store

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
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
import org.libtorrent4j.SessionParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

data class TorrentDownload(
  val id: String,
  val name: String,
  val source: String,
  val done: Long = 0L,
  val total: Long = 0L,
  val downloadRate: Int = 0,
  val uploadRate: Int = 0,
  val connectedPeers: Int = 0,
  val discoveredPeers: Int = 0,
  val seedCount: Int = 0,
  val activeTracker: String = "",
  val state: State = State.WAITING_METADATA,
  val error: String? = null,
  val libraryImported: Boolean = false,
) {
  enum class State { WAITING_METADATA, CHECKING, DOWNLOADING, PAUSED, COMPLETED, SEEDING, ERROR }
}

/** Native BitTorrent engine for user-supplied magnet URIs and .torrent files. */
object TorrentDownloadManager {
  private val engine = SessionManager()
  private val engineLock = Any()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val startupResults = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
  private val mutableDownloads = MutableStateFlow<List<TorrentDownload>>(emptyList())
  val downloads: StateFlow<List<TorrentDownload>> = mutableDownloads.asStateFlow()
  private val handles = ConcurrentHashMap<String, TorrentHandle>()
  private val sources = linkedMapOf<String, String>()
  private val pausedIds = ConcurrentHashMap.newKeySet<String>()
  private val selectedFileIndices = linkedMapOf<String, MutableSet<Int>>()
  private val appliedFileSelections = mutableMapOf<String, Set<Int>>()
  private val selectedFilePaths = linkedMapOf<String, MutableMap<Int, String>>()
  private val selectedFileTitles = linkedMapOf<String, MutableMap<Int, String>>()
  private val importAttempted = linkedMapOf<String, MutableSet<Int>>()
  private val importedFileIndices = linkedMapOf<String, MutableSet<Int>>()
  private var context: Context? = null
  private var polling = false
  private var alertListenerRegistered = false

  // Capture native libtorrent events instead of leaving failed metadata
  // exchanges indistinguishable from a torrent that is still waiting.
  private val torrentAlertListener = object : AlertListener {
    override fun types(): IntArray? = null

    override fun alert(alert: Alert<*>) {
      when (alert.type().name) {
        "METADATA_RECEIVED" -> {
          val hash = extractAlertInfoHash(alert) ?: return
          updateLocal(hash) { current ->
            current.copy(
              error = null,
              name = if (current.name == "Obtendo metadados…") "Metadados recebidos — verificando arquivos…" else current.name,
            )
          }
        }
        "METADATA_FAILED" -> {
          val hash = extractAlertInfoHash(alert) ?: return
          updateLocal(hash) {
            it.copy(state = TorrentDownload.State.ERROR, error = "Falha ao receber metadados do torrent: $alert")
          }
          persist()
        }
        "TORRENT_ERROR", "ADD_TORRENT_FAILED" -> {
          val hash = extractAlertInfoHash(alert) ?: return
          updateLocal(hash) {
            it.copy(state = TorrentDownload.State.ERROR, error = "Falha reportada pelo libtorrent: $alert")
          }
          persist()
        }
      }
    }
  }

  private fun extractAlertInfoHash(alert: Alert<*>): String? =
    runCatching {
      val torrentAlert = alert as? org.libtorrent4j.alerts.TorrentAlert<*> ?: return null
      val handle = torrentAlert.handle()
      if (!handle.isValid()) return null
      handle.infoHash().toHex().lowercase(Locale.ROOT)
    }.getOrNull()

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
        if (source.startsWith("magnet:")) {
          val pathsObject = row.optJSONObject("paths") ?: JSONObject()
          val paths = linkedMapOf<Int, String>()
          val pathKeys = pathsObject.keys()
          while (pathKeys.hasNext()) {
            val key = pathKeys.next()
            val index = key.toIntOrNull()
            val value = pathsObject.optString(key)
            if (index != null && value.isNotBlank()) paths[index] = value
          }
          val titlesObject = row.optJSONObject("titles") ?: JSONObject()
          val titles = linkedMapOf<Int, String>()
          val titleKeys = titlesObject.keys()
          while (titleKeys.hasNext()) {
            val key = titleKeys.next()
            val index = key.toIntOrNull()
            val value = titlesObject.optString(key)
            if (index != null && value.isNotBlank()) titles[index] = value
          }
          val importedArray = row.optJSONArray("imported") ?: JSONArray()
          val imported = buildSet {
            for (index in 0 until importedArray.length()) importedArray.optInt(index, -1).takeIf { it >= 0 }?.let { add(it) }
          }
          addMagnetInternal(
            appContext,
            source,
            persist = false,
            displayName = row.optString("name").takeIf { it.isNotBlank() },
            restoredPaths = paths,
            restoredTitles = titles,
            restoredImported = imported,
          )
        } else {
          val file = File(source)
          if (file.isFile) addTorrentFileInternal(appContext, file, persist = false)
        }
      }
    }
  }

  suspend fun addMagnetAndAwaitEngine(
    appContext: Context,
    rawMagnet: String,
    displayName: String? = null,
    relativePath: String? = null,
  ): String {
    val magnet = rawMagnet.trim()
    val hash = requireNotNull(extractBtih(magnet)) {
      "O magnet não contém um BTIH hexadecimal válido."
    }
    addMagnet(appContext, magnet, displayName, relativePath)
    val startup = startupResults[hash]
      ?: throw IllegalStateException("O torrent foi encontrado na fila, mas a inicialização não foi confirmada.")
    withTimeout(90_000) { startup.await() }
    check(mutableDownloads.value.any { it.id == hash }) {
      "O motor BitTorrent não manteve este torrent na fila."
    }
    return hash
  }

  fun addMagnet(
    appContext: Context,
    rawMagnet: String,
    displayName: String? = null,
    relativePath: String? = null,
  ) {
    addMagnetInternal(
      appContext.applicationContext,
      rawMagnet.trim(),
      persist = true,
      displayName = displayName,
      relativePath = relativePath,
    )
  }

  @Synchronized
  private fun addMagnetInternal(
    appContext: Context,
    magnet: String,
    persist: Boolean,
    displayName: String? = null,
    relativePath: String? = null,
    restoredPaths: Map<Int, String> = emptyMap(),
    restoredTitles: Map<Int, String> = emptyMap(),
    restoredImported: Set<Int> = emptySet(),
  ) {
    require(magnet.startsWith("magnet:?", ignoreCase = true)) { "Informe um link magnet válido." }
    val hash = extractBtih(magnet)
    require(hash != null) { "O magnet precisa conter um identificador btih hexadecimal válido." }
    val requestedFiles = extractSelectedFileIndices(magnet)
    if (sources.containsKey(hash)) {
      if (requestedFiles.isNotEmpty()) {
        val selected = selectedFileIndices.getOrPut(hash) { linkedSetOf() }
        val selectionChanged = selected.addAll(requestedFiles)
        if (!relativePath.isNullOrBlank()) {
          val paths = selectedFilePaths.getOrPut(hash) { linkedMapOf() }
          requestedFiles.forEach { paths[it] = relativePath }
          val titles = selectedFileTitles.getOrPut(hash) { linkedMapOf() }
          requestedFiles.forEach { index -> displayName?.let { titles[index] = it } }
        }
        if (selectionChanged) {
          importAttempted[hash] = importedFileIndices[hash]?.toMutableSet() ?: linkedSetOf()
          val mergedMagnet = magnetWithSelectedFiles(sources[hash] ?: magnet, selected)
          sources[hash] = mergedMagnet
          mutableDownloads.value = mutableDownloads.value.map {
            if (it.id == hash) it.copy(source = mergedMagnet) else it
          }
          val existingHandle = handles[hash] ?: engine.find(Sha1Hash.parseHex(hash))?.also { handles[hash] = it }
          if (existingHandle != null && existingHandle.isValid()) {
            applySelectedFiles(hash, existingHandle)
            pausedIds.remove(hash)
            existingHandle.resume()
            updateLocal(hash) { it.copy(state = TorrentDownload.State.DOWNLOADING, libraryImported = false, error = null) }
            startService(appContext)
            startPolling()
          }
        }
        if (persist) persist()
      }
      return
    }
    // Publish and persist the queue item BEFORE entering libtorrent's native
    // startup/add path. SessionManager.start()/download() may block while the
    // native session initializes; the UI must not hide the selected game until
    // those calls return.
    context = appContext.applicationContext
    sources[hash] = magnet
    if (requestedFiles.isNotEmpty()) selectedFileIndices[hash] = requestedFiles.toMutableSet()
    if (!relativePath.isNullOrBlank()) {
      // A negative placeholder keeps the selected torrent file path until torrent
      // metadata arrives and the real libtorrent file index can be resolved.
      val pathKeys = requestedFiles.ifEmpty { setOf(-1) }
      selectedFilePaths[hash] = pathKeys.associateWith { relativePath }.toMutableMap()
      displayName?.let { title ->
        selectedFileTitles[hash] = pathKeys.associateWith { title }.toMutableMap()
      }
    }
    if (restoredPaths.isNotEmpty()) selectedFilePaths[hash] = restoredPaths.toMutableMap()
    if (restoredTitles.isNotEmpty()) selectedFileTitles[hash] = restoredTitles.toMutableMap()
    if (restoredImported.isNotEmpty()) {
      importedFileIndices[hash] = restoredImported.toMutableSet()
      importAttempted[hash] = restoredImported.toMutableSet()
    }
    val alreadyImported = selectedFilePaths[hash]?.let { paths ->
      paths.isNotEmpty() && importedFileIndices[hash]?.containsAll(paths.keys) == true
    } ?: false
    mutableDownloads.value = mutableDownloads.value.filterNot { it.id == hash } +
      TorrentDownload(
        hash,
        displayName?.takeIf { it.isNotBlank() } ?: "Obtendo metadados…",
        magnet,
        libraryImported = alreadyImported,
      )
    if (persist) persist()

    // Queue insertion alone is not proof that libtorrent accepted the magnet.
    val startup = CompletableDeferred<Unit>()
    startupResults[hash] = startup
    scope.launch {
      try {
        synchronized(engineLock) {
          ensureEngine(appContext)
          engine.download(magnet, downloadDirectory(appContext), TorrentFlags.AUTO_MANAGED)
        }
        startService(appContext)
        startPolling()
        startup.complete(Unit)
      } catch (error: Exception) {
        startup.completeExceptionally(error)
        updateLocal(hash) {
          it.copy(state = TorrentDownload.State.ERROR, error = error.message ?: "Não foi possível inicializar o motor BitTorrent.")
        }
        if (persist) persist()
      }
    }
  }

  data class TorrentFileEntry(val index: Int, val path: String)

  fun inspectTorrentFile(file: File): List<TorrentFileEntry> {
    require(file.isFile && file.length() > 0L) { "O arquivo .torrent está vazio ou não existe." }
    val info = TorrentInfo(file)
    require(info.isValid()) { "O arquivo .torrent é inválido." }
    return (0 until info.numFiles())
      .map { index -> TorrentFileEntry(index, info.files().filePath(index)) }
      .filter { it.path.endsWith(".zip", ignoreCase = true) }
  }

  fun addTorrentFile(appContext: Context, file: File) {
    addTorrentFileInternal(appContext.applicationContext, file, persist = true)
  }

  fun addTorrentFile(appContext: Context, file: File, selectedFiles: List<TorrentFileEntry>) {
    require(selectedFiles.isNotEmpty()) { "Selecione ao menos um arquivo ZIP para baixar." }
    addTorrentFileInternal(appContext.applicationContext, file, persist = true, selectedFiles = selectedFiles)
  }

  @Synchronized
  private fun addTorrentFileInternal(
    appContext: Context,
    file: File,
    persist: Boolean,
    selectedFiles: List<TorrentFileEntry> = emptyList(),
  ) {
    require(file.isFile && file.length() > 0L) { "O arquivo .torrent está vazio ou não existe." }
    val info = TorrentInfo(file)
    require(info.isValid()) { "O arquivo .torrent é inválido." }
    val hash = info.infoHash().toHex()
    if (sources.containsKey(hash)) return
    if (selectedFiles.isNotEmpty()) {
      val validEntries = selectedFiles.filter { entry ->
        entry.index in 0 until info.numFiles() &&
          normalizeTorrentPath(info.files().filePath(entry.index)) == normalizeTorrentPath(entry.path)
      }
      require(validEntries.isNotEmpty()) { "A seleção não corresponde aos arquivos deste torrent." }
      selectedFilePaths[hash] = validEntries.associate { it.index to it.path }.toMutableMap()
      selectedFileTitles[hash] = validEntries.associate { it.index to File(it.path).nameWithoutExtension }.toMutableMap()
    }
    ensureEngine(appContext)
    engine.download(info, downloadDirectory(appContext))
    sources[hash] = file.absolutePath
    val handle = engine.find(Sha1Hash.parseHex(hash))
    if (selectedFiles.isNotEmpty() && handle != null && handle.isValid()) applySelectedFiles(hash, handle)
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
    selectedFileIndices.remove(id)
    appliedFileSelections.remove(id)
    selectedFilePaths.remove(id)
    selectedFileTitles.remove(id)
    importAttempted.remove(id)
    importedFileIndices.remove(id)
    mutableDownloads.value = mutableDownloads.value.filterNot { it.id == id }
    persist()
  }

  private fun ensureEngine(appContext: Context) {
    context = appContext.applicationContext
    if (!engine.isRunning) {
      // Large collection torrents contain large info dictionaries.
      // libtorrent4j defaults max_metadata_size to 2 MiB, which rejects
      // the ~21 MiB Xbox collection metadata before file sizes are known.
      // Raise the cap to 64 MiB so metadata exchange can complete.
      val sessionParams = SessionParams()
      sessionParams.getSettings().setMaxMetadataSize(64 * 1024 * 1024)
      engine.start(sessionParams)
    }
    if (!alertListenerRegistered) {
      engine.addListener(torrentAlertListener)
      alertListenerRegistered = true
    }
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
                applySelectedFiles(id, handle)
                val status = handle.status()
                val nativeState = status.state().name
                val state = when {
                  id in pausedIds && mutableDownloads.value.firstOrNull { it.id == id }?.libraryImported == true -> TorrentDownload.State.COMPLETED
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
                    name = if (it.name == "Obtendo metadados…") status.name().takeIf(String::isNotBlank) ?: it.name else it.name,
                    done = status.totalDone().coerceAtLeast(0L),
                    total = status.total().coerceAtLeast(0L),
                    downloadRate = status.downloadRate().coerceAtLeast(0),
                    uploadRate = status.uploadRate().coerceAtLeast(0),
                    connectedPeers = status.numPeers().coerceAtLeast(0),
                    discoveredPeers = status.listPeers().coerceAtLeast(0),
                    seedCount = status.numSeeds().coerceAtLeast(0),
                    activeTracker = status.currentTracker(),
                    state = state,
                    // libtorrent reports the zero error code as the text "Success".
                    // That is a healthy status, not an error to show in the download card.
                    error = status.errorCode().getMessage().takeUnless {
                      it.isBlank() || it.equals("Success", ignoreCase = true)
                    },
                  )
                }
                if (state == TorrentDownload.State.SEEDING || state == TorrentDownload.State.COMPLETED) {
                  importSelectedFiles(id)
                  val hasLibraryTarget = synchronized(this@TorrentDownloadManager) {
                    !selectedFilePaths[id].isNullOrEmpty()
                  }
                  if (hasLibraryTarget) {
                    handle.pause()
                    pausedIds.add(id)
                    updateLocal(id) {
                      it.copy(state = TorrentDownload.State.COMPLETED, downloadRate = 0, uploadRate = 0)
                    }
                  }
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

  @Synchronized
  fun retryLibraryImport(id: String) {
    val imported = importedFileIndices[id]?.toSet().orEmpty()
    importAttempted[id] = imported.toMutableSet()
    scope.launch { importSelectedFiles(id) }
  }

  private suspend fun importSelectedFiles(id: String) {
    val appContext = context ?: return
    val paths = synchronized(this) { selectedFilePaths[id]?.toMap().orEmpty() }
    if (paths.isEmpty()) return
    val titles = synchronized(this) { selectedFileTitles[id]?.toMap().orEmpty() }
    for ((index, relativePath) in paths) {
      val shouldImport = synchronized(this) {
        if (index in importedFileIndices[id].orEmpty()) false
        else importAttempted.getOrPut(id) { linkedSetOf() }.add(index)
      }
      if (!shouldImport) continue
      try {
        val file = locateDownloadedFile(relativePath, downloadDirectory(appContext))
          ?: error("O torrent terminou, mas o arquivo selecionado não foi localizado no armazenamento.")
        val title = titles[index]?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        ArchiveXboxDownloads.importTorrentFile(appContext, title, file)
        synchronized(this) { importedFileIndices.getOrPut(id) { linkedSetOf() }.add(index) }
        persist()
      } catch (error: Exception) {
        updateLocal(id) {
          it.copy(error = "Torrent baixado, mas não foi possível adicionar à Biblioteca: ${error.message ?: "verifique a pasta de destino"}")
        }
      }
    }
    val allImported = synchronized(this) {
      importedFileIndices[id]?.containsAll(paths.keys) == true
    }
    if (allImported) {
      updateLocal(id) { it.copy(libraryImported = true, error = null) }
      persist()
    }
  }

  private fun locateDownloadedFile(relativePath: String, directory: File): File? {
    val root = directory.canonicalFile
    val safePath = relativePath.replace('\\', '/').removePrefix("./").trimStart('/')
    val candidate = File(root, safePath).canonicalFile
    if (!candidate.path.startsWith(root.path + File.separator)) return null
    if (candidate.isFile) return candidate
    val expectedName = safePath.substringAfterLast('/')
    return root.walkTopDown().firstOrNull { it.isFile && it.name.equals(expectedName, ignoreCase = true) }
  }

  private fun persist() {
    val appContext = context ?: return
    val array = JSONArray()
    synchronized(this) {
      sources.forEach { (id, source) ->
        val paths = JSONObject().apply { selectedFilePaths[id]?.forEach { (index, path) -> put(index.toString(), path) } }
        val titles = JSONObject().apply { selectedFileTitles[id]?.forEach { (index, title) -> put(index.toString(), title) } }
        val imported = JSONArray().apply { importedFileIndices[id]?.forEach { put(it) } }
        val name = mutableDownloads.value.firstOrNull { it.id == id }?.name.orEmpty()
        array.put(JSONObject().put("id", id).put("source", source).put("name", name)
          .put("paths", paths).put("titles", titles).put("imported", imported))
      }
    }
    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, array.toString()).apply()
  }

  @Synchronized
  private fun applySelectedFiles(id: String, handle: TorrentHandle) {
    val info = handle.torrentFile() ?: return
    val paths = selectedFilePaths[id].orEmpty().values
      .map(::normalizeTorrentPath)
      .filter(String::isNotBlank)
      .toSet()
    if (paths.isEmpty()) return

    // The selected file identifier is not assumed to be a verified
    // libtorrent file index. Resolve the selected archive by its full path
    // against the actual torrent metadata instead.
    val selected = (0 until info.numFiles()).filter { index ->
      val torrentPath = normalizeTorrentPath(info.files().filePath(index))
      paths.any { requested ->
        torrentPath == requested || torrentPath.endsWith("/$requested") ||
          requested.endsWith("/$torrentPath")
      }
    }.toSet()
    require(selected.isNotEmpty()) {
      "O arquivo ${paths.first()} não foi encontrado nos metadados do torrent."
    }
    if (appliedFileSelections[id] == selected) return
    val priorities = Priority.array(Priority.IGNORE, info.numFiles())
    selected.forEach { priorities[it] = Priority.DEFAULT }
    handle.prioritizeFiles(priorities)
    selectedFileIndices[id] = selected.toMutableSet()
    appliedFileSelections[id] = selected
  }

  private fun normalizeTorrentPath(path: String): String =
    path.replace('\\', '/').trim().trimStart('/').removePrefix("./").lowercase(Locale.ROOT)

  private fun extractSelectedFileIndices(magnet: String): Set<Int> =
    Regex("(?i)(?:[?&])so=([^&]+)").find(magnet)?.groupValues?.get(1)
      ?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty()

  private fun magnetWithSelectedFiles(magnet: String, selected: Set<Int>): String {
    val withoutSelection = magnet.replace(Regex("(?i)([?&])so=[^&]*"), "")
    val separator = if (withoutSelection.contains("?")) "&" else "?"
    return "$withoutSelection${separator}so=${selected.sorted().joinToString(",")}"
  }

  private fun extractBtih(magnet: String): String? =
    Regex("(?i)xt=urn:btih:([a-f0-9]{40})").find(magnet)?.groupValues?.get(1)?.lowercase()

  private const val PREFS = "hakux_torrent_downloads"
  private const val KEY_ITEMS = "sources"
}
