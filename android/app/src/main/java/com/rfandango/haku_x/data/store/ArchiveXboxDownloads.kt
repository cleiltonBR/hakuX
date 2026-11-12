package com.rfandango.haku_x.data.store

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.rfandango.haku_x.data.AppPrefs
import com.rfandango.haku_x.data.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

data class ArchiveDownloadOption(
  val name: String,
  val bytes: Long,
  val downloadUrl: String,
)

data class ArchiveDownload(
  val identifier: String,
  val title: String,
  val fileName: String,
  val done: Long = 0,
  val total: Long = 0,
  val status: Status = Status.QUEUED,
  val error: String? = null,
  val outputName: String? = null,
  val downloadUrl: String? = null,
) {
  enum class Status { QUEUED, DOWNLOADING, EXTRACTING, COMPLETED, CANCELLED, FAILED }
}

/**
 * Lists downloadable game files from an Archive.org item after checking its
 * declared licence metadata. Files are written into the configured Xbox games
 * folder so GameScanner can discover them after a library refresh.
 */
object ArchiveXboxDownloads {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutableDownloads = MutableStateFlow<List<ArchiveDownload>>(emptyList())
  val downloads: StateFlow<List<ArchiveDownload>> = mutableDownloads.asStateFlow()
  private val mutableLibraryRefresh = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
  val libraryRefresh = mutableLibraryRefresh
  private val requests = linkedMapOf<String, ArchiveDownloadOption>()
  private var queueWorker: Job? = null
  private var activeTransfer: Job? = null
  private var activeKey: String? = null
  private var appContext: Context? = null

  /** Restore interrupted transfers after process recreation; partial files stay in the selected SAF folder. */
  @Synchronized
  fun restore(context: Context) {
    appContext = context.applicationContext
    if (mutableDownloads.value.isNotEmpty()) return
    val saved = context.getSharedPreferences("hakux_store_downloads", Context.MODE_PRIVATE)
      .getString("items", null) ?: return
    try {
      val array = JSONArray(saved)
      val restored = buildList {
        for (index in 0 until array.length()) {
          val row = array.optJSONObject(index) ?: continue
          val statusName = row.optString("status", "FAILED")
          val priorStatus = runCatching { ArchiveDownload.Status.valueOf(statusName) }.getOrDefault(ArchiveDownload.Status.FAILED)
          val url = row.optString("url").takeIf { isAllowedDownloadUrl(it) }
          val identifier = row.optString("identifier")
          val fileName = row.optString("fileName")
          if (identifier.isBlank() || fileName.isBlank() || url == null) continue
          val item = ArchiveDownload(
            identifier = identifier,
            title = row.optString("title", fileName),
            fileName = fileName,
            done = row.optLong("done", 0L),
            total = row.optLong("total", 0L),
            status = if (priorStatus == ArchiveDownload.Status.COMPLETED) priorStatus else ArchiveDownload.Status.FAILED,
            error = if (priorStatus == ArchiveDownload.Status.COMPLETED) null else "Download interrompido. Toque em Continuar download para retomar do ponto salvo.",
            outputName = row.optString("outputName").takeIf { it.isNotBlank() },
            downloadUrl = url,
          )
          if (item.status != ArchiveDownload.Status.COMPLETED) {
            val folder = AppPrefs(context).downloadFolderUri?.let { DocumentFile.fromTreeUri(context, it) }
            val partial = folder?.findFile(".${fileName}.partial")
            val actualBytes = partial?.length()?.coerceAtLeast(0L) ?: 0L
            add(item.copy(done = actualBytes))
          } else add(item)
          requests[downloadKey(identifier, fileName)] = ArchiveDownloadOption(fileName, item.total, url)
        }
      }
      mutableDownloads.value = restored
    } catch (_: Exception) {
      mutableDownloads.value = emptyList()
    }
  }

  private fun persist() {
    val context = appContext ?: return
    val array = JSONArray()
    mutableDownloads.value.takeLast(40).forEach { item ->
      array.put(JSONObject().apply {
        put("identifier", item.identifier)
        put("title", item.title)
        put("fileName", item.fileName)
        put("done", item.done)
        put("total", item.total)
        put("status", item.status.name)
        put("outputName", item.outputName)
        put("url", item.downloadUrl)
      })
    }
    context.getSharedPreferences("hakux_store_downloads", Context.MODE_PRIVATE)
      .edit().putString("items", array.toString()).apply()
  }

  suspend fun options(identifier: String, targetFileName: String? = null): List<ArchiveDownloadOption> = withContext(Dispatchers.IO) {
    require(identifier.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Identificador inválido do Internet Archive." }
    val connection = (URL("https://archive.org/metadata/$identifier").openConnection() as HttpURLConnection).apply {
      connectTimeout = 12_000
      readTimeout = 20_000
      instanceFollowRedirects = true
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "HakuX-Store/1.0")
    }
    try {
      val status = connection.responseCode
      require(status in 200..299) { "Internet Archive respondeu HTTP $status." }
      val root = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
      val metadata = root.optJSONObject("metadata") ?: JSONObject()
      val license = metadata.optString("licenseurl").trim()
      require(isRecognizedOpenLicense(license)) {
        val reason = if (license.isBlank()) {
          "O Internet Archive não informou a licença deste item."
        } else {
          "A licença informada pelo Internet Archive não está entre as licenças abertas reconhecidas pelo HakuX."
        }
        "$reason O download foi bloqueado; verifique os metadados do item."
      }
      val files = root.optJSONArray("files") ?: JSONArray()
      buildList {
        for (index in 0 until files.length()) {
          val file = files.optJSONObject(index) ?: continue
          val rawName = file.optString("name")
          val name = rawName.substringAfterLast('/').trim()
          val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
          if (extension !in GAME_EXTENSIONS || name.isBlank() || name == "." || name == ".." || name.contains("..")) continue
          if (targetFileName != null && rawName != targetFileName && name != targetFileName) continue
          val size = file.optString("size").toLongOrNull() ?: 0L
          if (size <= 0L) continue
          val encodedId = encodePathSegment(identifier)
          val encodedName = rawName.split('/').joinToString("/") { encodePathSegment(it) }
          add(ArchiveDownloadOption(
            name = name,
            bytes = size,
            downloadUrl = "https://archive.org/download/$encodedId/$encodedName",
          ))
        }
      }.distinctBy { it.name.lowercase(Locale.ROOT) }
    } finally {
      connection.disconnect()
    }
  }

  /** Enqueue a direct file URL detected by the in-app browser. */
  fun enqueueDirect(context: Context, title: String, rawUrl: String, suggestedFileName: String?, totalBytes: Long = 0L) {
    require(isAllowedDownloadUrl(rawUrl)) { "Por segurança, só são aceitos downloads HTTP ou HTTPS de hosts públicos." }
    val parsed = URL(rawUrl)
    val candidate = suggestedFileName?.substringAfterLast('/')?.substringAfterLast('\\')
      ?: parsed.path.substringAfterLast('/').takeIf { it.isNotBlank() }
      ?: ""
    val safeName = candidate.substringBefore('?').replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.')
    require(safeName.isNotBlank() && safeName.contains('.')) {
      "O site não informou um nome de arquivo com extensão reconhecível."
    }
    val extension = safeName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    require(extension in GAME_EXTENSIONS) {
      "Tipo não suportado. São aceitos ISO, XISO, CSO, CCI, ZIP, RAR e 7z."
    }
    val identifier = "link-" + java.security.MessageDigest.getInstance("SHA-256")
      .digest(rawUrl.toByteArray(Charsets.UTF_8))
      .take(12).joinToString("") { "%02x".format(it) }
    enqueue(context, identifier, title.ifBlank { safeName.substringBeforeLast('.') },
      ArchiveDownloadOption(safeName, totalBytes.coerceAtLeast(0L), rawUrl))
  }

  private fun isAllowedDownloadUrl(value: String): Boolean {
    val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    if (scheme !in setOf("http", "https")) return false
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
    if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
      val parts = host.split(".").mapNotNull { it.toIntOrNull() }
      if (parts.size != 4 || parts.any { it !in 0..255 }) return false
      val (a, b) = parts
      if (a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) ||
        (a == 172 && b in 16..31) || (a == 192 && b == 168) || a >= 224) return false
    }
    return true
  }

  /** Adds a download to the in-memory FIFO queue. Only one transfer writes at a time. */
  @Synchronized
  fun enqueue(context: Context, identifier: String, title: String, option: ArchiveDownloadOption) {
    appContext = context.applicationContext
    val key = downloadKey(identifier, option.name)
    val existing = mutableDownloads.value.firstOrNull { downloadKey(it.identifier, it.fileName) == key }
    if (existing?.status in setOf(ArchiveDownload.Status.QUEUED, ArchiveDownload.Status.DOWNLOADING, ArchiveDownload.Status.COMPLETED)) return

    val entry = ArchiveDownload(identifier, title, option.name, total = option.bytes, downloadUrl = option.downloadUrl)
    requests[key] = option
    mutableDownloads.value = mutableDownloads.value.filterNot {
      downloadKey(it.identifier, it.fileName) == key
    } + entry
    persist()
    androidx.core.content.ContextCompat.startForegroundService(
      context.applicationContext,
      android.content.Intent(context.applicationContext, ArchiveDownloadService::class.java),
    )

    if (queueWorker?.isActive != true) {
      queueWorker = scope.launch { processQueue(context.applicationContext) }
    }
  }

  fun resume(context: Context, item: ArchiveDownload) {
    val url = item.downloadUrl ?: return
    enqueue(context, item.identifier, item.title, ArchiveDownloadOption(item.fileName, item.total, url))
  }

  private suspend fun processQueue(context: Context) {
    while (true) {
      val next = mutableDownloads.value.firstOrNull { it.status == ArchiveDownload.Status.QUEUED } ?: break
      val key = downloadKey(next.identifier, next.fileName)
      val option = requests[key]
      if (option == null) {
        update(next.copy(status = ArchiveDownload.Status.FAILED, error = "Não foi possível recuperar os dados deste download."))
        continue
      }
      activeKey = key
      activeTransfer = scope.launch { downloadOne(context, next, option) }
      activeTransfer?.join()
      activeTransfer = null
      activeKey = null
    }
  }

  private suspend fun downloadOne(app: Context, entry: ArchiveDownload, option: ArchiveDownloadOption) {
    update(entry.copy(status = ArchiveDownload.Status.DOWNLOADING))
    var partial: DocumentFile? = null
    var extractedFolder: DocumentFile? = null
    val stagingArchive = File(app.cacheDir, "hakux-${UUID.randomUUID()}." + entry.fileName.substringAfterLast(".", "archive"))
    val stagingDirectory = File(app.cacheDir, "hakux-extract-${UUID.randomUUID()}")
    try {
      val prefs = AppPrefs(app)
      val folderUri = prefs.downloadFolderUri
        ?: error("Escolha uma pasta de destino na tela Downloads antes de baixar.")
      check(Storage.isFolderReady(app, folderUri)) { "A pasta de destino não está disponível." }
      val hasWrite = app.contentResolver.persistedUriPermissions.any { it.uri == folderUri && it.isWritePermission }
      check(hasWrite) { "O HakuX não tem permissão de gravação na pasta de destino. Selecione-a novamente." }
      val root = DocumentFile.fromTreeUri(app, folderUri)
        ?: error("Não foi possível abrir a pasta de destino.")
      check(root.canWrite()) { "A pasta de destino não permite gravação." }

      val finalName = uniqueName(root, option.name)
      val partialName = ".$finalName.partial"
      partial = root.findFile(partialName)
        ?: root.createFile("application/octet-stream", partialName)
        ?: error("Não foi possível criar o arquivo temporário na pasta de destino.")
      val offset = partial!!.length().coerceAtLeast(0L)
      check(option.bytes <= 0L || offset <= option.bytes) { "O arquivo parcial é maior que o tamanho informado pelo servidor." }
      var completedBytes = offset
      val connection = (URL(option.downloadUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "HakuX-Store/1.0")
        if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
      }
      try {
        val status = connection.responseCode
        if (offset > 0L) {
          check(status == HttpURLConnection.HTTP_PARTIAL) {
            if (status == HttpURLConnection.HTTP_OK) "O servidor não aceitou a retomada HTTP. O arquivo parcial foi preservado; tente novamente mais tarde."
            else "Não foi possível retomar o download (HTTP $status). O progresso parcial foi preservado."
          }
          val contentRange = connection.getHeaderField("Content-Range") ?: ""
          check(contentRange.startsWith("bytes $offset-", ignoreCase = true)) {
            "O servidor retornou um intervalo diferente do solicitado. O arquivo parcial foi preservado."
          }
        } else {
          check(status in 200..299) { "Falha ao baixar o arquivo (HTTP $status)." }
        }
        val reportedLength = connection.contentLengthLong
        val expectedTotal = option.bytes.takeIf { it > 0L } ?: reportedLength.takeIf { it > 0L }?.let { it + offset } ?: 0L
        app.contentResolver.openOutputStream(partial!!.uri, if (offset > 0L) "wa" else "w")?.use { output ->
          connection.inputStream.use { input ->
            val buffer = ByteArray(128 * 1024)
            var done = offset
            var lastUpdate = 0L
            while (true) {
              currentCoroutineContext().ensureActive()
              val count = input.read(buffer)
              if (count < 0) break
              output.write(buffer, 0, count)
              done += count
              val now = System.currentTimeMillis()
              if (now - lastUpdate >= 300) {
                completedBytes = done
                update(entry.copy(done = done, total = expectedTotal, status = ArchiveDownload.Status.DOWNLOADING))
                lastUpdate = now
              }
            }
            output.flush()
            check(done > 0L) { "O Internet Archive retornou um arquivo vazio." }
            check(option.bytes <= 0L || done == option.bytes) { "Download incompleto: esperado ${option.bytes} bytes, recebido $done. O progresso foi preservado para continuar." }
            completedBytes = done
            update(entry.copy(done = done, total = expectedTotal.takeIf { it > 0L } ?: done, status = ArchiveDownload.Status.DOWNLOADING))
          }
        } ?: error("Não foi possível gravar na pasta de destino.")
      } finally {
        connection.disconnect()
      }

      check(partial!!.renameTo(finalName)) { "O download terminou, mas não foi possível finalizar o arquivo." }
      val downloaded = root.findFile(finalName)
        ?: error("O arquivo baixado não foi localizado após a gravação.")
      partial = null

      val extension = finalName.substringAfterLast('.', "").lowercase(Locale.ROOT)
      if (extension in ARCHIVE_EXTENSIONS) {
        update(entry.copy(done = completedBytes, total = completedBytes, status = ArchiveDownload.Status.EXTRACTING))
        app.contentResolver.openInputStream(downloaded.uri)?.use { input ->
          FileOutputStream(stagingArchive).use { output -> input.copyTo(output, 128 * 1024) }
        } ?: error("Não foi possível abrir o arquivo compactado para extração.")

        GameArchiveExtractor.extract(stagingArchive, stagingDirectory)
        currentCoroutineContext().ensureActive()
        val folderName = uniqueName(root, safeFolderName(entry.title))
        extractedFolder = root.createDirectory(folderName)
          ?: error("Não foi possível criar a pasta do jogo extraído.")
        copyExtractedTree(app, stagingDirectory, extractedFolder!!)
        currentCoroutineContext().ensureActive()
        downloaded.delete()
        update(entry.copy(done = completedBytes, total = completedBytes, status = ArchiveDownload.Status.COMPLETED, outputName = folderName))
        mutableLibraryRefresh.tryEmit(Unit)
      } else {
        update(entry.copy(done = completedBytes, total = completedBytes, status = ArchiveDownload.Status.COMPLETED, outputName = finalName))
        mutableLibraryRefresh.tryEmit(Unit)
      }
    } catch (cancelled: CancellationException) {
      extractedFolder?.let { deleteTree(it) }
      val saved = partial?.length()?.coerceAtLeast(0L) ?: entry.done
      update(entry.copy(done = saved, status = ArchiveDownload.Status.CANCELLED, error = if (saved > 0L) "Download interrompido. É possível continuar do ponto salvo." else "Download cancelado."))
    } catch (error: Exception) {
      extractedFolder?.let { deleteTree(it) }
      val saved = partial?.length()?.coerceAtLeast(0L) ?: entry.done
      val reason = when (error) {
        is java.net.UnknownHostException -> "Sem resolução DNS para archive.org. Verifique a conexão e tente continuar."
        is java.net.SocketException -> "A conexão foi interrompida. O progresso foi preservado para continuar."
        else -> error.message ?: "Falha no download ou na extração. O progresso parcial foi preservado."
      }
      update(entry.copy(done = saved, status = ArchiveDownload.Status.FAILED, error = reason))
    } finally {
      stagingArchive.delete()
      stagingDirectory.deleteRecursively()
    }
  }

  private suspend fun copyExtractedTree(app: Context, source: File, target: DocumentFile, stagingRoot: File = source) {
    val safeRoot = stagingRoot.canonicalFile.path + File.separator
    source.listFiles()?.forEach { child ->
      currentCoroutineContext().ensureActive()
      check(child.canonicalFile.path.startsWith(safeRoot)) { "O arquivo extraído aponta para fora da pasta temporária." }
      if (child.isDirectory) {
        val directory = target.createDirectory(child.name)
          ?: error("Não foi possível criar a subpasta ${child.name}.")
        copyExtractedTree(app, child, directory, stagingRoot)
      } else if (child.isFile) {
        val outputFile = target.createFile("application/octet-stream", child.name)
          ?: error("Não foi possível criar o arquivo extraído ${child.name}.")
        app.contentResolver.openOutputStream(outputFile.uri, "w")?.use { output ->
          child.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
              currentCoroutineContext().ensureActive()
              val count = input.read(buffer)
              if (count < 0) break
              output.write(buffer, 0, count)
            }
            output.flush()
          }
        } ?: error("Não foi possível gravar o arquivo extraído ${child.name}.")
      }
    }
  }

  private fun deleteTree(file: DocumentFile) {
    if (file.isDirectory) file.listFiles().forEach(::deleteTree)
    file.delete()
  }

  private fun safeFolderName(value: String): String =
    value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.').take(80).ifBlank { "Jogo Xbox" }

  @Synchronized
  fun cancel(identifier: String, fileName: String) {
    val key = downloadKey(identifier, fileName)
    val item = mutableDownloads.value.firstOrNull { downloadKey(it.identifier, it.fileName) == key } ?: return
    if (activeKey == key) activeTransfer?.cancel()
    else if (item.status == ArchiveDownload.Status.QUEUED) {
      update(item.copy(status = ArchiveDownload.Status.CANCELLED, error = "Download cancelado antes de iniciar."))
    }
  }

  @Deprecated("Use cancel(identifier, fileName) to cancel a specific queue item.")
  fun cancel() {
    mutableDownloads.value.firstOrNull { it.status == ArchiveDownload.Status.DOWNLOADING || it.status == ArchiveDownload.Status.EXTRACTING }
      ?.let { cancel(it.identifier, it.fileName) }
  }

  private fun downloadKey(identifier: String, fileName: String): String =
    "${identifier.lowercase(Locale.ROOT)}:${fileName.lowercase(Locale.ROOT)}"

  private fun update(item: ArchiveDownload) {
    mutableDownloads.value = mutableDownloads.value.map {
      if (it.identifier == item.identifier && it.fileName == item.fileName) item else it
    }
    persist()
  }

  private fun uniqueName(root: DocumentFile, requested: String): String {
    if (root.findFile(requested) == null) return requested
    val base = requested.substringBeforeLast('.', requested)
    val extension = requested.substringAfterLast('.', "")
    var index = 2
    while (true) {
      val candidate = if (extension.isBlank()) "$base ($index)" else "$base ($index).$extension"
      if (root.findFile(candidate) == null) return candidate
      index++
    }
  }

  private fun isRecognizedOpenLicense(value: String): Boolean {
    if (value.isBlank()) return false
    val normalized = value
      .lowercase(Locale.ROOT)
      .substringBefore('#')
      .substringBefore('?')
      .trimEnd('/')
    // Accept canonical HTTP/HTTPS Creative Commons URLs and common version
    // variants, while deliberately excluding NC/ND licences.
    return Regex(
      """https?://(?:www\.)?creativecommons\.org/(?:licenses/(?:by|by-sa)/(?:2\.0|2\.5|3\.0|4\.0)|publicdomain/(?:zero/1\.0|mark/1\.0))"""
    ).matches(normalized)
  }

  private fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

  private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z")
  private val GAME_EXTENSIONS = setOf("iso", "xiso", "cso", "cci") + ARCHIVE_EXTENSIONS
}
