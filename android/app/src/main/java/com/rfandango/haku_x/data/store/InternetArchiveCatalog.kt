package com.rfandango.haku_x.data.store

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Search adapter for Internet Archive's existing public item catalogue.
 *
 * Unlike a storage-provider profile, this source discovers already-published items,
 * reads their file manifests, and returns direct URLs for supported game-image files.
 */
object InternetArchiveCatalog {
  private const val CONNECT_TIMEOUT_MS = 12_000
  private const val READ_TIMEOUT_MS = 25_000
  private const val MAX_JSON_BYTES = 8 * 1024 * 1024
  private val supportedExtensions = setOf(
    "iso", "xiso", "rom", "cso", "cci", "chd", "bin", "cue", "gdi", "zip", "7z", "rar"
  )

  fun search(query: String, page: Int = 1, rows: Int = 20): List<StoreGame> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(8)
    require(terms.isNotEmpty()) { "Digite o nome de um jogo para pesquisar" }
    val luceneTerms = terms.joinToString(" AND ") { term ->
      val safe = term.replace(Regex("[^\\p{L}\\p{N}_-]"), "")
      require(safe.isNotBlank()) { "Termo de pesquisa inválido" }
      "(title:$safe OR description:$safe OR subject:$safe)"
    }
    val expression = "mediatype:software AND $luceneTerms"
    val url = "https://archive.org/advancedsearch.php?q=${encode(expression)}" +
      "&fl[]=identifier&fl[]=title&fl[]=description&fl[]=subject&fl[]=year&fl[]=downloads" +
      "&rows=${rows.coerceIn(1, 40)}&page=${page.coerceAtLeast(1)}&output=json"
    val root = JSONObject(readHttps(url))
    val docs = root.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
    return buildList {
      for (index in 0 until docs.length()) {
        val doc = docs.optJSONObject(index) ?: continue
        val identifier = doc.optString("identifier").trim()
        if (identifier.isBlank()) continue
        runCatching { loadItem(identifier, doc) }.getOrNull()?.let(::add)
      }
    }
  }

  private fun loadItem(identifier: String, searchDoc: JSONObject): StoreGame? {
    val metadata = JSONObject(readHttps("https://archive.org/metadata/${encode(identifier)}"))
    val files = metadata.optJSONArray("files") ?: return null
    val mirrors = buildList {
      for (index in 0 until files.length()) {
        val file = files.optJSONObject(index) ?: continue
        val name = file.optString("name").trim()
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in supportedExtensions || name.startsWith("__")) continue
        val encodedPath = name.split('/').joinToString("/") { encode(it) }
        val fileUrl = "https://archive.org/download/${encode(identifier)}/$encodedPath"
        add(
          StoreMirror(
            id = "${identifier}-${index}",
            name = name,
            url = fileUrl,
            provider = "Internet Archive",
            expectedBytes = file.optString("size").toLongOrNull()?.takeIf { it > 0 },
            sha256 = file.optString("sha256").takeIf { it.matches(Regex("(?i)[a-f0-9]{64}")) },
            supportsRanges = false,
            requiresAccount = false,
            directDownload = true,
          )
        )
      }
    }
    if (mirrors.isEmpty()) return null
    val title = searchDoc.optString("title").ifBlank { identifier }
    val description = searchDoc.optString("description")
    val subjects = searchDoc.opt("subject")?.let {
      if (it is JSONArray) (0 until it.length()).joinToString(", ") { i -> it.optString(i) }
      else it.toString()
    }.orEmpty()
    val platform = when {
      listOf(title, description, subjects).any { it.contains("xbox", ignoreCase = true) } -> "Xbox"
      else -> subjects.substringBefore(',').ifBlank { "Retro / Multiplataforma" }
    }
    return StoreGame(
      id = "ia:$identifier",
      title = title,
      description = description.ifBlank { subjects },
      category = "Internet Archive",
      coverUrl = "https://archive.org/services/img/${encode(identifier)}",
      platform = platform,
      mirrors = mirrors,
    )
  }

  private fun readHttps(address: String): String {
    require(address.startsWith("https://", ignoreCase = true))
    val connection = (URL(address).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      instanceFollowRedirects = true
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "HakuX-Store/1")
    }
    try {
      val status = connection.responseCode
      require(status in 200..299) { "Internet Archive respondeu HTTP $status" }
      require(connection.contentLengthLong <= 0 || connection.contentLengthLong <= MAX_JSON_BYTES) {
        "Resposta do catálogo excede o limite"
      }
      val output = java.io.ByteArrayOutputStream()
      connection.inputStream.use { input ->
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          total += count
          require(total <= MAX_JSON_BYTES) { "Resposta do catálogo excede o limite" }
          output.write(buffer, 0, count)
        }
      }
      return output.toString(Charsets.UTF_8.name())
    } finally {
      connection.disconnect()
    }
  }

  private fun encode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
