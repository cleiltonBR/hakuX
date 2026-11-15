package com.rfandango.haku_x.data.store

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class MinervaXboxEntry(
  val id: String,
  val title: String,
  val fileName: String,
  val sizeLabel: String,
  val detailsUrl: String,
)

object MinervaXboxRepository {
  const val CATALOG_URL = "https://minerva-archive.org/browse/Redump/Microsoft%20-%20Xbox/"
  private const val USER_AGENT = "HakuX-Store/1.0 (Android; Minerva catalog)"

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
      """(?is)<a\\b([^>]*?\\bhref\\s*=\\s*["']([^"']*?/rom\\?id=(\\d+)[^"']*)["'][^>]*)>(.*?)</a>"""
    )
    val entries = LinkedHashMap<String, MinervaXboxEntry>()
    pattern.findAll(html).forEach { match ->
      val id = match.groupValues[3]
      val fileName = cleanText(match.groupValues[4]).trim()
      if (id.isBlank() || fileName.isBlank() || !fileName.endsWith(".zip", ignoreCase = true)) return@forEach
      val afterAnchor = html.substring(match.range.last + 1)
      val nextAnchor = afterAnchor.indexOf("<a", ignoreCase = true).let { if (it < 0) minOf(afterAnchor.length, 220) else minOf(it, 220) }
      val nearbyText = cleanText(afterAnchor.substring(0, nextAnchor))
      val size = Regex("""(?i)\\b(\\d+(?:\\.\\d+)?\\s*(?:GB|MB|KB))\\b""")
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
