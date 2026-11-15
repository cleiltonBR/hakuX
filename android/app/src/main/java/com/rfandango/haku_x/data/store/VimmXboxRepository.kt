package com.rfandango.haku_x.data.store

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

data class VimmXboxGame(
  val vaultId: Int,
  val title: String,
  val regions: String,
) {
  val detailsUrl: String get() = "https://vimm.net/vault/$vaultId"
}

object VimmXboxRepository {
  private const val BASE_URL = "https://vimm.net/vault/"
  private const val USER_AGENT = "HakuX-Store/1.0 (Android; native catalog)"

  fun search(query: String): List<VimmXboxGame> {
    val term = query.trim()
    require(term.length >= 2) { "Digite pelo menos 2 caracteres para pesquisar no Vimm's Lair." }
    val encoded = URLEncoder.encode(term, Charsets.UTF_8.name())
    val url = "$BASE_URL?p=list&system=Xbox&q=$encoded&version=all&discs=all"
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 15_000
      readTimeout = 25_000
      instanceFollowRedirects = true
      setRequestProperty("Accept", "text/html,application/xhtml+xml")
      setRequestProperty("Accept-Language", "en")
      setRequestProperty("User-Agent", USER_AGENT)
    }
    try {
      val status = connection.responseCode
      require(status in 200..299) { "Vimm's Lair respondeu HTTP $status." }
      val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
      require(!html.contains("cf-turnstile", true) && !html.contains("challenges.cloudflare.com/turnstile", true)) {
        "O Vimm's Lair solicitou uma verificação no navegador. Abra o site oficial para continuar."
      }
      return parseListing(html)
    } finally {
      connection.disconnect()
    }
  }

  internal fun parseListing(html: String): List<VimmXboxGame> {
    val rows = Regex("(?is)<tr\\b[^>]*>(.*?)</tr>").findAll(html).map { it.groupValues[1] }.toList()
    val candidates = if (rows.isNotEmpty()) rows else listOf(html)
    val found = LinkedHashMap<Int, VimmXboxGame>()
    val anchorPattern = Regex("(?is)<a\\b([^>]*?)href\\s*=\\s*[\"']\\s*/vault/(\\d+)/?[^>]*>(.*?)</a>")
    for (row in candidates) {
      for (match in anchorPattern.findAll(row)) {
        val attributes = match.groupValues[1]
        if (Regex("(?i)display\\s*:\\s*none").containsMatchIn(attributes)) continue
        val id = match.groupValues[2].toIntOrNull() ?: continue
        if (id == 999999 || found.containsKey(id)) continue
        val title = cleanText(match.groupValues[3])
        if (title.isBlank() || title == "9") continue
        val regionTitles = Regex("(?is)<img\\b[^>]*title\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>")
          .findAll(row)
          .map { decode(it.groupValues[1]) }
          .filter { it.isNotBlank() && it.length < 40 }
          .distinct()
          .joinToString(", ")
        found[id] = VimmXboxGame(id, title, regionTitles.ifBlank { "Região não informada" })
      }
    }
    return found.values.toList()
  }

  private fun cleanText(value: String): String =
    decode(value.replace(Regex("(?is)<[^>]+>"), " "))
      .replace(Regex("\\s+"), " ")
      .trim()

  private fun decode(value: String): String = value
    .replace("&amp;", "&", true)
    .replace("&lt;", "<", true)
    .replace("&gt;", ">", true)
    .replace("&quot;", "\"", true)
    .replace("&#39;", "'", true)
    .replace("&apos;", "'", true)
    .replace("&nbsp;", " ", true)
    .replace(Regex("&#(\\d+);")) { match ->
      match.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: ""
    }
}
