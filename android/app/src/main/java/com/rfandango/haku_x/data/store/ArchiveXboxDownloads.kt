package com.rfandango.haku_x.data.store

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.rfandango.haku_x.data.AppPrefs
import com.rfandango.haku_x.data.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

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
) {
  enum class Status { QUEUED, DOWNLOADING, COMPLETED, CANCELLED, FAILED }
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
  private var activeJob: Job? = null

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

  fun enqueue(context: Context, identifier: String, title: String, option: ArchiveDownloadOption) {
    if (activeJob?.isActive == true) return
    val app = context.applicationContext
    val entry = ArchiveDownload(identifier, title, option.name, total = option.bytes)
    mutableDownloads.value = listOf(entry) + mutableDownloads.value.filterNot { it.identifier == identifier && it.fileName == option.name }
    activeJob = scope.launch {
      update(entry.copy(status = ArchiveDownload.Status.DOWNLOADING))
      var partial: DocumentFile? = null
      try {
        val prefs = AppPrefs(app)
        val folderUri = prefs.gamesFolders.firstOrNull()
          ?: error("Configure uma pasta de jogos na Biblioteca antes de baixar.")
        check(Storage.isFolderReady(app, folderUri)) { "A pasta de jogos não está disponível." }
        val hasWrite = app.contentResolver.persistedUriPermissions.any { it.uri == folderUri && it.isWritePermission }
        check(hasWrite) { "O HakuX não tem permissão de gravação na pasta de jogos. Selecione-a novamente nas configurações." }
        val root = DocumentFile.fromTreeUri(app, folderUri)
          ?: error("Não foi possível abrir a pasta de jogos.")
        check(root.canWrite()) { "A pasta de jogos não permite gravação." }
        val finalName = uniqueName(root, option.name)
        partial = root.createFile("application/octet-stream", ".$finalName.partial")
          ?: error("Não foi possível criar o arquivo temporário na pasta de jogos.")
        val connection = (URL(option.downloadUrl).openConnection() as HttpURLConnection).apply {
          connectTimeout = 15_000
          readTimeout = 30_000
          instanceFollowRedirects = true
          setRequestProperty("User-Agent", "HakuX-Store/1.0")
        }
        try {
          val status = connection.responseCode
          check(status in 200..299) { "Falha ao baixar o arquivo (HTTP $status)." }
          val reportedLength = connection.contentLengthLong
          app.contentResolver.openOutputStream(partial!!.uri, "w")?.use { output ->
            connection.inputStream.use { input ->
              val buffer = ByteArray(128 * 1024)
              var done = 0L
              var lastUpdate = 0L
              while (true) {
                kotlinx.coroutines.ensureActive()
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
              check(done == option.bytes) { "Download incompleto: esperado ${option.bytes} bytes, recebido $done." }
              update(entry.copy(done = done, total = done, status = ArchiveDownload.Status.DOWNLOADING))
            }
          } ?: error("Não foi possível gravar na pasta de jogos.")
        } finally {
          connection.disconnect()
        }
        check(partial!!.renameTo(finalName)) { "O arquivo foi baixado, mas não foi possível finalizar o nome. Verifique a pasta de jogos." }
        update(entry.copy(done = option.bytes, total = option.bytes, status = ArchiveDownload.Status.COMPLETED, fileName = finalName))
      } catch (cancelled: CancellationException) {
        partial?.delete()
        update(entry.copy(status = ArchiveDownload.Status.CANCELLED))
        throw cancelled
      } catch (error: Exception) {
        partial?.delete()
        update(entry.copy(status = ArchiveDownload.Status.FAILED, error = error.message ?: "Falha no download."))
      }
    }
  }

  fun cancel() {
    activeJob?.cancel()
  }

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

  private val GAME_EXTENSIONS = setOf("iso", "xiso", "cso", "cci")
}
