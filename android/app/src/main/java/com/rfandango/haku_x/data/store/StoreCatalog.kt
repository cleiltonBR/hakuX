package com.rfandango.haku_x.data.store

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

/** Catalog entries are independent from the emulator library and native core. */
data class StoreGame(
  val id: String,
  val title: String,
  val description: String,
  val category: String,
  val coverUrl: String?,
  val platform: String,
  val mirrors: List<StoreMirror>,
)

data class StoreMirror(
  val id: String,
  val name: String,
  val url: String,
  val provider: String,
  val expectedBytes: Long?,
  val sha256: String?,
  val supportsRanges: Boolean,
  val requiresAccount: Boolean,
  val directDownload: Boolean,
)

data class MirrorCheck(
  val mirror: StoreMirror,
  val reachable: Boolean,
  val rangeSupported: Boolean,
  val responseBytes: Long?,
  val latencyMillis: Long?,
  val failureReason: String? = null,
)

data class StoreCatalogSource(
  val id: String,
  val name: String,
  val manifestUrl: String,
  val enabled: Boolean = true,
)

object StoreCatalogRepository {
  private const val CONNECT_TIMEOUT_MS = 12_000
  private const val READ_TIMEOUT_MS = 20_000
  private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

  fun load(source: StoreCatalogSource): List<StoreGame> {
    require(source.enabled) { "Fonte desativada" }
    require(source.manifestUrl.startsWith("https://", true)) { "O catálogo precisa usar HTTPS" }
    val connection = (URL(source.manifestUrl).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      instanceFollowRedirects = true
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "HakuX-Store/1")
    }
    try {
      val status = connection.responseCode
      require(status in 200..299) { "Catálogo respondeu HTTP $status" }
      require(connection.contentLengthLong <= 0 || connection.contentLengthLong <= MAX_MANIFEST_BYTES) {
        "Catálogo excede o limite permitido"
      }
      val output = java.io.ByteArrayOutputStream()
      connection.inputStream.use { input ->
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          total += count
          require(total <= MAX_MANIFEST_BYTES) { "Catálogo excede o limite permitido" }
          output.write(buffer, 0, count)
        }
      }
      return parse(output.toString(Charsets.UTF_8.name()))
    } finally {
      connection.disconnect()
    }
  }

  fun parse(json: String): List<StoreGame> {
    val root = JSONObject(json)
    require(root.optInt("schema", -1) == 1) { "Versão de catálogo não suportada" }
    val entries = root.optJSONArray("games") ?: JSONArray()
    return buildList {
      for (index in 0 until entries.length()) {
        val item = entries.optJSONObject(index) ?: continue
        val id = item.optString("id").trim()
        val title = item.optString("title").trim()
        if (id.isBlank() || title.isBlank()) continue
        val rawMirrors = item.optJSONArray("mirrors") ?: JSONArray()
        val mirrors = buildList {
          for (mirrorIndex in 0 until rawMirrors.length()) {
            val raw = rawMirrors.optJSONObject(mirrorIndex) ?: continue
            val url = raw.optString("url").trim()
            if (!url.startsWith("https://", true)) continue
            add(StoreMirror(
              id = raw.optString("id").ifBlank { "$id-mirror-$mirrorIndex" },
              name = raw.optString("name").ifBlank { "Fonte ${mirrorIndex + 1}" },
              url = url,
              provider = raw.optString("provider").ifBlank { "Externo" },
              expectedBytes = raw.optLong("size").takeIf { it > 0 },
              sha256 = raw.optString("sha256").takeIf { it.matches(Regex("(?i)[a-f0-9]{64}")) },
              supportsRanges = raw.optBoolean("supportsRanges", false),
              requiresAccount = raw.optBoolean("requiresAccount", false),
              directDownload = raw.optBoolean("direct", false),
            ))
          }
        }
        add(StoreGame(
          id = id,
          title = title,
          description = item.optString("description"),
          category = item.optString("category").ifBlank { "Outros" },
          coverUrl = item.optString("cover").takeIf { it.startsWith("https://", true) },
          platform = item.optString("platform").ifBlank { "Xbox" },
          mirrors = mirrors,
        ))
      }
    }
  }

  fun search(games: List<StoreGame>, query: String): List<StoreGame> {
    val needle = normalize(query)
    if (needle.isBlank()) return games
    return games.filter { game ->
      listOf(game.title, game.description, game.category, game.platform)
        .any { normalize(it).contains(needle) }
    }
  }

  /** Measures connection latency and Range support; it is not a throughput benchmark. */
  fun checkMirror(mirror: StoreMirror): MirrorCheck {
    val started = System.nanoTime()
    val connection = (URL(mirror.url).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      instanceFollowRedirects = true
      setRequestProperty("Range", "bytes=0-0")
      setRequestProperty("User-Agent", "HakuX-Store/1")
    }
    return try {
      val status = connection.responseCode
      val elapsed = (System.nanoTime() - started) / 1_000_000
      if (status in 200..299) {
        runCatching { connection.inputStream.use { it.read() } }
        MirrorCheck(
          mirror, true, status == HttpURLConnection.HTTP_PARTIAL,
          connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            ?: connection.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0 },
          elapsed,
        )
      } else MirrorCheck(mirror, false, false, null, elapsed, "HTTP $status")
    } catch (error: Exception) {
      MirrorCheck(mirror, false, false, null, null, error.message ?: "Falha de conexão")
    } finally {
      connection.disconnect()
    }
  }

  private fun normalize(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .lowercase(Locale.ROOT)
}


/** Public Internet Archive search results, limited to Xbox software records. */
data class ArchiveXboxItem(
  val identifier: String,
  val title: String,
  val description: String,
  val year: String?,
  val region: String = "Região não identificada",
  val archiveFileName: String? = null,
) {
  val genre: String get() = classifyGenre("$title $description")
  val detailsUrl: String get() = "https://archive.org/details/$identifier"
  val coverUrl: String get() = "https://archive.org/services/img/$identifier"
}

data class ArchiveXboxSearchPage(
  val items: List<ArchiveXboxItem>,
  val total: Int,
  val page: Int,
  val rows: Int,
) {
  val hasMore: Boolean get() = page * rows < total
}

private fun classifyRegion(raw: String): String {
  val value = Normalizer.normalize(raw, Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
  return when {
    Regex("\\b(ntsc[- _]?j|japan|japanese|jpn)\\b").containsMatchIn(value) -> "NTSC-J"
    Regex("\\b(ntsc[- _]?u|ntsc[- _]?usa|usa|united states|north america|canada)\\b").containsMatchIn(value) -> "NTSC-U"
    Regex("\\b(pal|europe|european|australia|australian|uk|united kingdom|germany|france|italy|spain)\\b").containsMatchIn(value) -> "PAL"
    else -> "Região não identificada"
  }
}

private fun classifyGenre(raw: String): String {
  val value = Normalizer.normalize(raw, Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
  return when {
    listOf("first person shooter", "fps", "call of duty", "halo", "doom", "quake", "medal of honor", "counter strike", "rainbow six").any(value::contains) -> "FPS"
    listOf("fighting", "fight", "tekken", "mortal kombat", "dead or alive", "soulcalibur", "street fighter", "virtua fighter", "wwe", "wrestling").any(value::contains) -> "Luta"
    listOf("racing", "race", "corrida", "forza", "need for speed", "midnight club", "burnout", "ralli", "nascar", "motogp").any(value::contains) -> "Corrida"
    listOf("football", "soccer", "fifa", "pes ", "pro evolution", "nba", "nfl", "nhl", "tennis", "golf", "sports").any(value::contains) -> "Esportes"
    listOf("role-playing", "role playing", " rpg", "final fantasy", "kingdom hearts", "star wars knights", "fable", "elder scrolls").any(value::contains) -> "RPG"
    listOf("platform", "platformer", "crash bandicoot", "sonic", "rayman", "spongebob", "jak and daxter", "ratchet").any(value::contains) -> "Plataforma"
    listOf("action", "adventure", "ação", "adventure").any(value::contains) -> "Ação"
    else -> "Outros"
  }
}

object InternetArchiveXboxRepository {
  internal const val ARCHIVE_ITEM_ID = "Xbox_classico_Velho_BX_Retrogamer"
  private const val METADATA_ENDPOINT = "https://archive.org/metadata/$ARCHIVE_ITEM_ID"

  internal fun parseArchiveFiles(
    json: String,
    rawQuery: String = "",
    page: Int = 1,
    rows: Int = 40,
  ): ArchiveXboxSearchPage {
    val root = JSONObject(json)
    val files = root.optJSONArray("files") ?: JSONArray()
    val needle = Normalizer.normalize(rawQuery.trim(), Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
    val games = buildList {
      for (index in 0 until files.length()) {
        val file = files.optJSONObject(index) ?: continue
        val fullName = file.optString("name").trim()
        val name = fullName.substringAfterLast('/')
        if (name.isBlank() || name.startsWith("_") || name.startsWith(".")) continue
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in setOf("iso", "xiso", "cci", "cso")) continue
        val title = name.substringBeforeLast('.').replace(Regex("[_.]+"), " ").trim()
        if (title.isBlank()) continue
        val normalizedTitle = Normalizer.normalize(title, Normalizer.Form.NFD)
          .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        if (needle.isNotBlank() && !normalizedTitle.contains(needle)) continue
        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(title)?.value
        add(ArchiveXboxItem(
          identifier = ARCHIVE_ITEM_ID,
          title = title,
          description = "Jogo Xbox Clássico · arquivo .$extension",
          year = year,
          region = classifyRegion(title),
          archiveFileName = fullName,
        ))
      }
    }.sortedBy { it.title.lowercase(Locale.ROOT) }
    val limit = rows.coerceIn(1, 100)
    val pageNumber = page.coerceAtLeast(1)
    val offset = (pageNumber - 1) * limit
    return ArchiveXboxSearchPage(games.drop(offset).take(limit), games.size, pageNumber, limit)
  }

  fun search(rawQuery: String, rows: Int = 40): List<ArchiveXboxItem> =
    searchPage(rawQuery, page = 1, rows = rows).items

  fun searchPage(rawQuery: String, page: Int = 1, rows: Int = 40): ArchiveXboxSearchPage {
    val connection = (URL(METADATA_ENDPOINT).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 15_000
      readTimeout = 30_000
      instanceFollowRedirects = true
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "HakuX-Store/1.0")
    }
    try {
      val status = connection.responseCode
      require(status in 200..299) { "Internet Archive respondeu HTTP $status ao abrir a biblioteca Xbox." }
      val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
      val result = parseArchiveFiles(json, rawQuery, page, rows)
      require(result.total > 0 || rawQuery.isNotBlank()) {
        "O item Xbox foi aberto, mas não contém arquivos ISO/XISO/CCI/CSO reconhecidos."
      }
      return result
    } finally {
      connection.disconnect()
    }
  }
}
