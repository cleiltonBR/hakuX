package com.rfandango.haku_x.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Imports user-authorized Xbox cover sources. Image filenames are normalized
 * so Hyperlist-style game names can be matched by XboxCoverCache.
 */
object CustomCoverSource {
  private const val MAX_TOTAL_IMAGE_BYTES = 1536L * 1024L * 1024L
  private const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L
  private const val MAX_IMAGES = 5000
  private const val MANIFEST_URL = "https://raw.githubusercontent.com/cleiltonBR/HakuX-X1-Covers-3D/main/manifest.json"
  private const val MANIFEST_TTL_MS = 24L * 60 * 60 * 1000

  /** Resolves one game against the shared manifest; the manifest itself is cached for 24 hours. */
  fun resolveUrl(context: Context, gameTitle: String): String? {
    val wanted = matchKey(gameTitle)
    if (wanted.isBlank()) return null
    val manifest = loadManifest(context) ?: return null
    val covers = manifest.optJSONObject("covers") ?: return null
    val keys = covers.keys()
    var regionlessMatch: String? = null
    while (keys.hasNext()) {
      val title = keys.next()
      val key = matchKey(title)
      val value = covers.optString(title).takeIf { it.startsWith("https://raw.githubusercontent.com/") } ?: continue
      if (key == wanted) return value
      if (regionlessMatch == null && key.removeRegion() == wanted.removeRegion()) regionlessMatch = value
    }
    return regionlessMatch
  }

  /** Returns a cached local bitmap or downloads only this game's matching cover. */
  fun load(context: Context, gameTitle: String): Bitmap? {
    val key = normalize(gameTitle)
    if (key.isBlank()) return null
    val dir = File(context.applicationContext.filesDir, "custom_xbox_covers")
    if (!dir.exists() && !dir.mkdirs()) return null
    val candidates = listOf(key, matchKey(gameTitle))
    val existing = candidates.asSequence().distinct().flatMap { candidate ->
      listOf("png", "jpg", "jpeg", "webp").asSequence().map { File(dir, "$candidate.$it") }
    }.firstOrNull { it.isFile }
    if (existing != null) return BitmapFactory.decodeFile(existing.absolutePath)

    val imageUrl = resolveUrl(context, gameTitle) ?: return null
    return runCatching {
      val (resolved, connection) = request(URL(imageUrl))
      try {
        val filename = URLDecoder.decode(resolved.path.substringAfterLast('/'), "UTF-8")
        val ext = extension(resolved.path, connection.contentType.orEmpty())
        val target = File(dir, "$key.$ext")
        connection.inputStream.use { copyImage(it, target) }
        BitmapFactory.decodeFile(target.absolutePath)
      } finally {
        connection.disconnect()
      }
    }.getOrNull()
  }

  private fun loadManifest(context: Context): JSONObject? {
    val file = File(context.applicationContext.filesDir, "custom_xbox_covers_manifest.json")
    if (file.isFile && System.currentTimeMillis() - file.lastModified() < MANIFEST_TTL_MS) {
      runCatching { JSONObject(file.readText()) }.getOrNull()?.let { return it }
    }
    return runCatching {
      val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "HakuX-Android/1.0")
      }
      try {
        require(connection.responseCode in 200..299) { "Manifesto de capas indisponível (HTTP ${connection.responseCode})." }
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        if (json.optInt("version") != 1 || json.optJSONObject("covers") == null) return@runCatching null
        file.writeText(json.toString())
        json
      } finally { connection.disconnect() }
    }.getOrNull()
  }

  /** Blocking; callers must use Dispatchers.IO. Returns the number of imported images. */
  fun download(context: Context, source: String, libraryTitles: Set<String>? = null): Int {
    val url = URL(source)
    require(url.protocol == "https" || url.protocol == "http") { "Use um link HTTP ou HTTPS." }
    val dir = File(context.applicationContext.filesDir, "custom_xbox_covers")
    if (!dir.exists() && !dir.mkdirs()) error("Não foi possível criar a pasta de capas.")
    val (finalUrl, connection) = request(url)
    try {
      val mime = connection.contentType.orEmpty().lowercase(Locale.ROOT)
      val path = finalUrl.path.lowercase(Locale.ROOT)
      return when {
        path.endsWith(".zip") || mime.contains("zip") ->
          connection.inputStream.use { importZip(it, dir) }
        path.endsWith(".json") || mime.contains("json") -> {
          val json = connection.inputStream.bufferedReader().use { it.readText() }
          importManifest(finalUrl, JSONObject(json), dir, libraryTitles)
        }
        path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
          path.endsWith(".webp") || mime.startsWith("image/") -> {
          val filename = URLDecoder.decode(finalUrl.path.substringAfterLast('/'), "UTF-8")
          val key = normalize(filename.substringBeforeLast('.', filename))
          require(key.isNotBlank()) { "O nome do arquivo não identifica um jogo." }
          val ext = extension(finalUrl.path, mime)
          connection.inputStream.use { copyImage(it, File(dir, "$key.$ext")) }
          1
        }
        mime.contains("html") || path.endsWith("/") || path.endsWith(".html") || path.endsWith(".htm") ->
          importPage(finalUrl, connection, context)
        else -> error("Formato não reconhecido. Use ZIP, PNG, JPG, JSON ou uma página pública com links diretos.")
      }
    } finally {
      connection.disconnect()
    }
  }

  private fun importPage(page: URL, connection: HttpURLConnection, context: Context): Int {
    val html = connection.inputStream.bufferedReader().use { it.readText() }
    val links = Regex("(?i)href\\s*=\\s*[\"']([^\"']+)[\"']")
      .findAll(html).map { it.groupValues[1] }
      .mapNotNull { runCatching { URL(page, it) }.getOrNull() }
      .filter { it.protocol == "http" || it.protocol == "https" }
      .filter {
        val name = it.path.substringAfterLast('/').lowercase(Locale.ROOT)
        name.endsWith(".zip") || name.endsWith(".json") || name.endsWith(".png") ||
          name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp")
      }.distinctBy { it.toString() }.take(100).toList()
    if (links.isEmpty()) {
      if (html.contains("sign in", true) || html.contains("log in", true) || html.contains("login", true))
        error("Esta página exige login. Entre no site e use um link de download autorizado.")
      error("A página não expôs links diretos para arquivos de capas.")
    }
    var imported = 0
    for (link in links) imported += download(context, link.toString())
    return imported
  }

  private fun importManifest(base: URL, json: JSONObject, dir: File, libraryTitles: Set<String>?): Int {
    val entries = json.optJSONObject("covers") ?: json
    val wanted = libraryTitles?.map(::matchKey)?.filter { it.isNotBlank() }?.toSet()
    if (wanted != null && wanted.isEmpty()) return 0
    val keys = entries.keys()
    var imported = 0
    while (keys.hasNext() && imported < MAX_IMAGES) {
      val title = keys.next()
      val value = entries.opt(title)
      val path = when (value) {
        is String -> value
        is JSONObject -> value.optString("url").ifBlank { value.optString("file") }
        else -> ""
      }
      if (path.isBlank()) continue
      val key = normalize(title)
      if (key.isBlank()) continue
      if (wanted != null && matchKey(title) !in wanted) continue
      val (resolved, conn) = request(URL(base, path))
      try {
        require(!conn.contentType.orEmpty().contains("html", true)) {
          "O manifesto aponta para uma página protegida, não para uma imagem."
        }
        val ext = extension(resolved.path, conn.contentType.orEmpty())
        conn.inputStream.use { copyImage(it, File(dir, "$key.$ext")) }
        imported++
      } finally {
        conn.disconnect()
      }
    }
    return imported
  }

  private fun importZip(input: InputStream, dir: File): Int {
    var imported = 0
    var total = 0L
    ZipInputStream(BufferedInputStream(input)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.isDirectory) continue
        val filename = entry.name.substringAfterLast('/').substringAfterLast('\\')
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in setOf("png", "jpg", "jpeg", "webp")) continue
        val key = normalize(filename.substringBeforeLast('.', filename))
        if (key.isBlank()) continue
        val bytes = copyImage(zip, File(dir, "$key.$ext"))
        total += bytes
        require(total <= MAX_TOTAL_IMAGE_BYTES) { "O pacote excede o limite de 1,5 GB descompactado." }
        imported++
        require(imported <= MAX_IMAGES) { "O pacote contém mais de $MAX_IMAGES imagens." }
        zip.closeEntry()
      }
    }
    return imported
  }

  private fun request(url: URL): Pair<URL, HttpURLConnection> {
    val connection = (url.openConnection() as HttpURLConnection).apply {
      connectTimeout = 15000
      readTimeout = 45000
      instanceFollowRedirects = true
      setRequestProperty("User-Agent", "hakuX-Android/1.0")
      setRequestProperty("Accept", "application/zip, application/json, image/*, text/html, */*")
    }
    val status = connection.responseCode
    if (status !in 200..299) {
      connection.disconnect()
      if (status == 401 || status == 403)
        error("Download bloqueado (HTTP $status). Entre no site e use um link autorizado.")
      error("Falha no servidor (HTTP $status).")
    }
    require(connection.contentLengthLong <= MAX_TOTAL_IMAGE_BYTES || connection.contentLengthLong < 0) {
      "O arquivo excede o limite de 1,5 GB."
    }
    return connection.url to connection
  }

  private fun copyImage(input: InputStream, target: File): Long {
    val temp = File(target.parentFile, target.name + ".part")
    var total = 0L
    try {
      temp.outputStream().buffered().use { output ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          total += read
          require(total <= MAX_IMAGE_BYTES) { "Uma imagem excede o limite de 20 MB." }
          output.write(buffer, 0, read)
        }
      }
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(temp.absolutePath, options)
      require(options.outWidth > 0 && options.outHeight > 0) { "O arquivo não é uma imagem válida." }
      if (!temp.renameTo(target)) {
        temp.copyTo(target, overwrite = true)
        temp.delete()
      }
      return total
    } catch (error: Exception) {
      temp.delete()
      throw error
    }
  }

  private fun matchKey(value: String): String = normalize(value)
  private fun String.removeRegion(): String = replace(Regex("_(usa|europe|world|japan|japanese|korea|france|germany|italy|spain)$"), "")

  private fun normalize(value: String): String = value.substringBeforeLast('.', value)
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), "_")
    .trim('_')
    .take(140)

  private fun extension(path: String, mime: String): String {
    val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return if (ext in setOf("png", "jpg", "jpeg", "webp")) ext else when {
      mime.contains("png") -> "png"
      mime.contains("webp") -> "webp"
      else -> "jpg"
    }
  }
}
