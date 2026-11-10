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
 * Downloads only files exposed by Archive.org items with an explicit,
 * recognized open licence. Files are written into the configured Xbox games
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

  suspend fun options(identifier: String): List<ArchiveDownloadOption> = withContext(Dispatchers.IO) {
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
      val license = metadata.optString("licenseurl").lowercase(Locale.ROOT)
      require(isRecognizedOpenLicense(license)) {
        "Este item não informa uma licença aberta compatível. O HakuX não libera o download deste conteúdo."
      }
      val files = root.optJSONArray("files") ?: JSONArray()
      buildList {
        for (index in 0 until files.length()) {
          val file = files.optJSONObject(index) ?: continue
          val rawName = file.optString("name")
          val name = rawName.substringAfterLast('/').trim()
          val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
          if (extension !in GAME_EXTENSIONS || name.isBlank() || name == "." || name == ".." || name.contains("..")) continue
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

  /** Adds a download to the in-memory FIFO queue. Only one transfer writes at a time. */
  @Synchronized
  fun enqueue(context: Context, identifier: String, title: String, option: ArchiveDownloadOption) {
    val key = downloadKey(identifier, option.name)
    val existing = mutableDownloads.value.firstOrNull { downloadKey(it.identifier, it.fileName) == key }
    if (existing?.status in setOf(ArchiveDownload.Status.QUEUED, ArchiveDownload.Status.DOWNLOADING, ArchiveDownload.Status.COMPLETED)) return

    val entry = ArchiveDownload(identifier, title, option.name, total = option.bytes, downloadUrl = option.downloadUrl)
    requests[key] = option
    mutableDownloads.value = mutableDownloads.value.filterNot {
      downloadKey(it.identifier, it.fileName) == key
    } + entry

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
      activeTransfer = scope.launch { downloadOne(context, next, option) }
      activeTransfer?.join()
      activeTransfer = null
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
      check(offset <= option.bytes) { "O arquivo parcial é maior que o tamanho informado pelo Internet Archive." }
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
                update(entry.copy(done = done, total = option.bytes.takeIf { it > 0 } ?: reportedLength, status = ArchiveDownload.Status.DOWNLOADING))
                lastUpdate = now
              }
            }
            output.flush()
            check(done > 0L) { "O Internet Archive retornou um arquivo vazio." }
            check(done == option.bytes) { "Download incompleto: esperado ${option.bytes} bytes, recebido $done. O progresso foi preservado para continuar." }
            update(entry.copy(done = done, total = done, status = ArchiveDownload.Status.DOWNLOADING))
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
        update(entry.copy(done = option.bytes, total = option.bytes, status = ArchiveDownload.Status.EXTRACTING))
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
        update(entry.copy(done = option.bytes, total = option.bytes, status = ArchiveDownload.Status.COMPLETED, outputName = folderName))
        mutableLibraryRefresh.tryEmit(Unit)
      } else {
        update(entry.copy(done = option.bytes, total = option.bytes, status = ArchiveDownload.Status.COMPLETED, outputName = finalName))
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

  fun cancel() {
    activeTransfer?.cancel()
  }

  private fun downloadKey(identifier: String, fileName: String): String =
    "${identifier.lowercase(Locale.ROOT)}:${fileName.lowercase(Locale.ROOT)}"

  private fun update(item: ArchiveDownload) {
    mutableDownloads.value = mutableDownloads.value.map {
      if (it.identifier == item.identifier && it.fileName == item.fileName) item else it
    }
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

  private fun isRecognizedOpenLicense(value: String): Boolean =
    value.contains("creativecommons.org/licenses/by/4.0") ||
      value.contains("creativecommons.org/licenses/by-sa/4.0") ||
      value.contains("creativecommons.org/licenses/by/3.0") ||
      value.contains("creativecommons.org/licenses/by-sa/3.0") ||
      value.contains("creativecommons.org/publicdomain/zero/1.0") ||
      value.contains("creativecommons.org/publicdomain/mark/1.0")

  private fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

  private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z")
  private val GAME_EXTENSIONS = setOf("iso", "xiso", "cso", "cci") + ARCHIVE_EXTENSIONS
}
