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


  data class DownloadResolution(
    val url: String,
    val fileName: String,
    val bytes: Long,
  )

  /**
   * Resolves only the download form that Vimm itself currently publishes.
   * If the site's Download control is absent/disabled (for example, a takedown
   * or an unavailable vault item), this deliberately refuses to synthesize it.
   */
  fun resolveDownload(game: VimmXboxGame): DownloadResolution {
    val pageUrl = URL(game.detailsUrl)
    val pageConnection = (pageUrl.openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 15_000
      readTimeout = 25_000
      instanceFollowRedirects = true
      setRequestProperty("Accept", "text/html,application/xhtml+xml")
      setRequestProperty("Accept-Language", "en")
      setRequestProperty("User-Agent", USER_AGENT)
    }
    val html = try {
      val status = pageConnection.responseCode
      require(status in 200..299) { "Não foi possível abrir a página do jogo no Vimm (HTTP $status)." }
      pageConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
      pageConnection.disconnect()
    }

    require(!Regex("(?i)download unavailable|temporarily offline|not available at the request").containsMatchIn(html)) {
      "O Vimm informa que o download deste jogo não está disponível."
    }

    val formMatch = Regex("(?is)<form\\b([^>]*)>(.*?)</form>").findAll(html)
      .firstOrNull { match -> Regex("(?i)\\bid\\s*=\\s*['\"]dl_form['\"]").containsMatchIn(match.groupValues[1]) }
      ?: error("A página do Vimm não disponibilizou um formulário de download para este jogo.")
    val formAttributes = formMatch.groupValues[1]
    val formBody = formMatch.groupValues[2]
    val controls = Regex("(?is)<button\\b([^>]*)>(.*?)</button>|<input\\b([^>]*)>")
    val hasActiveDownloadButton = controls.findAll(formBody).any { match ->
      val attrs = if (match.groupValues[1].isNotEmpty()) match.groupValues[1] else match.groupValues[3]
      val label = if (match.groupValues[2].isNotEmpty()) match.groupValues[2].replace(Regex("(?is)<[^>]+>"), " ") else {
        Regex("(?i)\\bvalue\\s*=\\s*['\"]([^'\"]*)['\"]").find(attrs)?.groupValues?.get(1).orEmpty()
      }
      val type = Regex("(?i)\\btype\\s*=\\s*['\"]([^'\"]*)['\"]").find(attrs)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
      "disabled" !in attrs.lowercase(Locale.ROOT) &&
        (type == null || type == "submit") &&
        label.contains("download", ignoreCase = true)
    }
    require(hasActiveDownloadButton) {
      "O Vimm não disponibilizou um botão de download ativo para este jogo. O HakuX não tentará contornar essa indisponibilidade."
    }

    fun attribute(source: String, name: String): String? =
      Regex("(?i)\\b$name\\s*=\\s*(?:[\"']([^\"']*)[\"']|([^\\s>]+))")
        .find(source)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }

    val action = attribute(formAttributes, "action")?.takeIf { it.isNotBlank() }
      ?: error("O formulário oficial do Vimm não informou o endereço de download.")
    val method = (attribute(formAttributes, "method") ?: "GET").uppercase(Locale.ROOT)
    require(method == "GET" || method == "POST") { "O método de download informado pelo Vimm não é compatível." }
    val params = Regex("(?is)<input\\b([^>]*)>").findAll(formBody).mapNotNull { match ->
      val attrs = match.groupValues[1]
      if (Regex("(?i)\\bdisabled(?:\\s|=|$)").containsMatchIn(attrs)) return@mapNotNull null
      val name = attribute(attrs, "name") ?: return@mapNotNull null
      val value = attribute(attrs, "value") ?: ""
      name to value
    }.toList()
    require(params.any { it.first == "mediaId" && it.second.isNotBlank() }) {
      "O formulário oficial do Vimm não contém um identificador de mídia válido."
    }
    val encodedParams = params.joinToString("&") {
      "${URLEncoder.encode(it.first, Charsets.UTF_8.name())}=${URLEncoder.encode(it.second, Charsets.UTF_8.name())}"
    }
    var requestUrl = URL(pageUrl, action)
    var requestMethod = method
    var requestBody: String? = if (method == "POST") encodedParams else null
    if (method == "GET" && encodedParams.isNotBlank()) {
      requestUrl = URL(requestUrl.toString() + (if (requestUrl.query == null) "?" else "&") + encodedParams)
    }

    repeat(6) {
      val methodForRequest = requestMethod
      val connection = (requestUrl.openConnection() as HttpURLConnection).apply {
        requestMethod = methodForRequest
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = false
        setRequestProperty("Accept", "*/*")
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Referer", game.detailsUrl)
        if (methodForRequest == "POST") {
          doOutput = true
          setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
      }
      val status: Int
      try {
        if (requestMethod == "POST") {
          connection.outputStream.use { it.write((requestBody ?: "").toByteArray(Charsets.UTF_8)) }
        }
        status = connection.responseCode
        if (status in 300..399) {
          val location = connection.getHeaderField("Location")
            ?: error("O servidor do Vimm redirecionou sem informar o destino.")
          requestUrl = URL(requestUrl, location)
          if (status == 303 || ((status == 301 || status == 302) && requestMethod == "POST")) {
            requestMethod = "GET"
            requestBody = null
          }
          return@repeat
        }
        require(status in 200..299) { "O servidor de download do Vimm respondeu HTTP $status." }
        val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
        val dispositionName = Regex("""(?i)filename\\*?=(?:UTF-8''|")?([^;"]+)""")
          .find(disposition)?.groupValues?.get(1)?.trim()?.trim('"')
          ?.let { runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrDefault(it) }
        val pathName = requestUrl.path.substringAfterLast('/').takeIf { it.contains('.') }
        val fileName = (dispositionName ?: pathName)
          ?.substringAfterLast('/')?.substringAfterLast('\\')
          ?.takeIf { it.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("iso", "xiso", "cso", "cci", "zip", "rar", "7z") }
          ?: error("O Vimm respondeu, mas não informou um nome de arquivo compatível. Abra os detalhes do jogo para verificar a disponibilidade.")
        return DownloadResolution(requestUrl.toString(), fileName, connection.contentLengthLong.coerceAtLeast(0L))
      } finally {
        connection.disconnect()
      }
    }
    error("O servidor do Vimm excedeu o limite de redirecionamentos.")
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
