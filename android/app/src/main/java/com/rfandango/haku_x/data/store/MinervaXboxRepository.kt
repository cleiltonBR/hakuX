package com.rfandango.haku_x.data.store

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
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
   * The API returns a collection magnet plus a row-level so_id.
   * so_id is not assumed to be a libtorrent file index. The exact archive
   * path is matched against torrent metadata after it arrives.
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
      connectTimeout = 10_000
      // MiNERVA normally answers in ~20-25s. Bound the wait so the UI
      // cannot remain in Preparing indefinitely on a stalled connection.
      // The public MiNERVA API is documented by its client integration as
      // taking 20–25s and requiring at least 60s of HTTP timeout budget.
      // Keep enough headroom for slow responses instead of aborting at 35s.
      readTimeout = 65_000
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
      require(body.isNotBlank()) {
        "A API do Minerva retornou uma resposta vazia. Tente novamente em instantes."
      }
      val rows = try {
        JSONArray(body)
      } catch (error: Exception) {
        val preview = body.take(160).replace(Regex("\\s+"), " ")
        throw IllegalStateException(
          "A API do Minerva não retornou JSON válido (HTTP $status). Resposta: $preview",
          error,
        )
      }
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
      // so_id is a Minerva database row identifier, not a verified
      // libtorrent file index. Do not pass it as BEP 53 "so".
      val selectedMagnet = buildString {
        append(magnet)

        // A magnet may contain one stale/unreachable tracker. Do not treat
        // the presence of a single tr= parameter as proof that discovery is
        // configured: merge every fallback tracker that is not already there.
        val existingTrackers = Regex("(?i)(?:[?&])tr=([^&]+)")
          .findAll(magnet)
          .mapNotNull { match ->
            runCatching {
              URLDecoder.decode(match.groupValues[1], Charsets.UTF_8.name())
            }.getOrNull()
          }
          .toSet()
        TRACKERS.filterNot { it in existingTrackers }.forEach { tracker ->
          append("&tr=").append(URLEncoder.encode(tracker, Charsets.UTF_8.name()))
        }
      }
      val path = row.optString("full_path").replace('\\', '/')
      return MinervaTorrentSource(selectedMagnet, path, "")
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
