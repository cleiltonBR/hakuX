package com.rfandango.haku_x.data.store

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class MinervaXboxEntry(
  val id: String,
  val title: String,
  val fileName: String,
  val sizeLabel: String,
  val detailsUrl: String,
)

data class MinervaTorrentSource(
  val magnet: String,
  val filePath: String,
  val fileIndex: String,
)

object MinervaXboxRepository {
  const val CATALOG_URL = "https://minerva-archive.org/browse/Redump/Microsoft%20-%20Xbox/"
  private const val SEARCH_URL = "https://minerva-archive.org/v1/api/rom/search"
  private const val USER_AGENT = "HakuX-Store/1.0 (Android; Minerva catalog)"
  private val TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://open.tracker.cl:1337/announce",
    "udp://tracker.publictracker.xyz:6969/announce",
    "udp://tracker.wildkat.net:6969/announce",
    "udp://tracker.qu.ax:6969/announce",
    "udp://tracker.peerfect.org:6969/announce",
    "udp://tracker.ilibr.org:6969/announce",
    "udp://tracker.dler.org:6969/announce",
    "udp://tracker.bittor.pw:1337/announce",
    "udp://tracker.0x7c0.com:6969/announce",
    "udp://exodus.desync.com:6969/announce",
  )

  fun loadCatalog(): List<MinervaXboxEntry> {
    val connection = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 15_000
      readTimeout = 35_000
      instanceFollowRedirects = true
      setRequestProperty("Accept", "text/html,application/xhtml+xml")
      setRequestProperty("Accept-Language", "en")
      setRequestProperty("User-Agent", USER_AGENT)
    }
    try {
      val status = connection.responseCode
      val html = (if (status in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      require(!isChallenge(html)) {
        "O Minerva Archive solicitou uma verificação no navegador. Abra o catálogo oficial e tente novamente."
      }
      require(status in 200..299) { "O Minerva Archive respondeu HTTP $status." }
      return parseListing(html)
    } finally {
      connection.disconnect()
    }
  }

  internal fun parseListing(html: String): List<MinervaXboxEntry> {
    val pattern = Regex(
      """(?is)<a\b([^>]*?\bhref\s*=\s*["']([^"']*?/rom\?id=(\d+)[^"']*)["'][^>]*)>(.*?)</a>"""
    )
    val entries = LinkedHashMap<String, MinervaXboxEntry>()
    pattern.findAll(html).forEach { match ->
      val id = match.groupValues[3]
      val fileName = cleanText(match.groupValues[4]).trim()
      if (id.isBlank() || fileName.isBlank() || !fileName.endsWith(".zip", ignoreCase = true)) return@forEach
      val afterAnchor = html.substring(match.range.last + 1)
      val nextAnchor = afterAnchor.indexOf("<a", ignoreCase = true).let { if (it < 0) minOf(afterAnchor.length, 220) else minOf(it, 220) }
      val nearbyText = cleanText(afterAnchor.substring(0, nextAnchor))
      val size = Regex("""(?i)\b(\d+(?:\.\d+)?\s*(?:GB|MB|KB))\b""")
        .find(nearbyText)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: "Tamanho não informado"
      val title = fileName.removeSuffix(".zip").removeSuffix(".ZIP")
      entries.putIfAbsent(
        id,
        MinervaXboxEntry(
          id = id,
          title = title,
          fileName = fileName,
          sizeLabel = size,
          detailsUrl = "https://minerva-archive.org/rom?id=$id",
        )
      )
    }
    return entries.values.toList()
  }

  /**
   * Resolves one catalog row through MiNERVA's public search API.
   * The API returns a collection magnet plus so_id, the selected-file index.
   * Appending BEP 53's so parameter prevents downloading the entire Xbox set.
   */
  fun resolveTorrent(entry: MinervaXboxEntry): MinervaTorrentSource {
    val searchTerm = entry.fileName.removeSuffix(".zip").removeSuffix(".ZIP")
    val params = listOf(
      "query" to searchTerm,
      "offset" to "0",
      "group" to "Redump",
      "console" to "Microsoft Xbox",
    )
    val encoded = params.joinToString("&") { (key, value) ->
      "${URLEncoder.encode(key, Charsets.UTF_8.name())}=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
    }
    val connection = (URL("$SEARCH_URL?$encoded").openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 15_000
      readTimeout = 60_000
      instanceFollowRedirects = true
      setRequestProperty("Accept", "application/json")
      setRequestProperty("Accept-Language", "en")
      setRequestProperty("User-Agent", USER_AGENT)
    }
    try {
      val status = connection.responseCode
      val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      require(status in 200..299) {
        "A API do Minerva respondeu HTTP $status ao localizar o torrent."
      }
      val rows = JSONArray(body)
      var selected: JSONObject? = null
      for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val path = row.optString("full_path").replace('\\', '/')
        val fileName = path.substringAfterLast('/')
        if (fileName.equals(entry.fileName, ignoreCase = true)) {
          selected = row
          break
        }
      }
      val row = requireNotNull(selected) {
        "O Minerva não retornou uma correspondência exata para ${entry.fileName}. Nenhum download foi iniciado."
      }
      val magnet = row.optString("magnet").trim()
      require(magnet.startsWith("magnet:?", ignoreCase = true)) {
        "O Minerva não forneceu um magnet válido para este jogo."
      }
      val fileIndex = row.optString("so_id").trim()
      require(fileIndex.matches(Regex("\\d+"))) {
        "O Minerva não informou o índice do arquivo no torrent. Nenhum download foi iniciado."
      }
      val selectedMagnet = buildString {
        append(if (Regex("(?i)[?&]so=").containsMatchIn(magnet)) magnet else "$magnet&so=$fileIndex")
        if (!Regex("(?i)[?&]tr=").containsMatchIn(magnet)) {
          TRACKERS.forEach { tracker ->
            append("&tr=").append(URLEncoder.encode(tracker, Charsets.UTF_8.name()))
          }
        }
      }
      val path = row.optString("full_path").replace('\\\\', '/')
      return MinervaTorrentSource(selectedMagnet, path, fileIndex)
    } finally {
      connection.disconnect()
    }
  }

  private fun isChallenge(html: String): Boolean =
    listOf("cf-turnstile", "challenges.cloudflare.com", "__cf_chl", "challenge-platform", "just a moment")
      .any { html.contains(it, ignoreCase = true) }

  private fun cleanText(value: String): String =
    value.replace(Regex("(?is)<[^>]+>"), " ")
      .replace("&amp;", "&", true)
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .replace("&nbsp;", " ")
      .replace(Regex("\\s+"), " ")
      .trim()
}
