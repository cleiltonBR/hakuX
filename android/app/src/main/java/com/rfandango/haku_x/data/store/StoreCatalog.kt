package com.rfandango.haku_x.data.store

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data contract for HakuX's built-in game store.
 *
 * The catalog is deliberately independent from the emulator library and native core.
 * Providers publish metadata and one or more download mirrors; the app decides which
 * mirror is usable and the user chooses where to download/install the game.
 */
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

/** A catalog source can be hosted independently and added without an app update. */
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

  /**
   * Manifest format:
   * {
   *   "schema": 1,
   *   "games": [{
   *     "id": "...", "title": "...", "description": "...",
   *     "category": "...", "platform": "Xbox",
   *     "cover": "https://...",
   *     "mirrors": [{
   *       "id": "...", "name": "...", "provider": "...",
   *       "url": "https://...", "size": 123, "sha256": "...",
   *       "supportsRanges": true, "requiresAccount": false,
   *       "direct": true
   *     }]
   *   }]
   * }
   */
  fun load(source: StoreCatalogSource): List<StoreGame> {
    require(source.enabled) { "Fonte desativada" }
    requireHttps(source.manifestUrl)
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
      val declaredLength = connection.contentLengthLong
      require(declaredLength <= 0 || declaredLength <= MAX_MANIFEST_BYTES) { "Catálogo excede o limite permitido" }
      val bytes = connection.inputStream.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          total += count
          require(total <= MAX_MANIFEST_BYTES) { "Catálogo excede o limite permitido" }
          output.write(buffer, 0, count)
        }
        output.toByteArray()
      }
      return parse(String(bytes, Charsets.UTF_8))
    } finally {
      connection.disconnect()
    }
  }

  fun parse(json: String): List<StoreGame> {
    val root = JSONObject(json)
    require(root.optInt("schema", -1) == 1) { "Versão de catálogo não suportada" }
    val games = root.optJSONArray("games") ?: JSONArray()
    return buildList {
      for (index in 0 until games.length()) {
        val item = games.optJSONObject(index) ?: continue
        val id = item.optString("id").trim()
        val title = item.optString("title").trim()
        if (id.isEmpty() || title.isEmpty()) continue
        val mirrorsJson = item.optJSONArray("mirrors") ?: JSONArray()
        val mirrors = buildList {
          for (mirrorIndex in 0 until mirrorsJson.length()) {
            val value = mirrorsJson.optJSONObject(mirrorIndex) ?: continue
            val url = value.optString("url").trim()
            if (!url.startsWith("https://", ignoreCase = true)) continue
            val mirrorId = value.optString("id").ifBlank { "$id-mirror-$mirrorIndex" }
            add(
              StoreMirror(
                id = mirrorId,
                name = value.optString("name").ifBlank { "Servidor ${mirrorIndex + 1}" },
                url = url,
                provider = value.optString("provider").ifBlank { "Externo" },
                expectedBytes = value.optLong("size").takeIf { it > 0 },
                sha256 = value.optString("sha256").takeIf { it.matches(Regex("(?i)[a-f0-9]{64}")) },
                supportsRanges = value.optBoolean("supportsRanges", false),
                requiresAccount = value.optBoolean("requiresAccount", false),
                directDownload = value.optBoolean("direct", false),
              ),
            )
          }
        }
        add(
          StoreGame(
            id = id,
            title = title,
            description = item.optString("description"),
            category = item.optString("category").ifBlank { "Outros" },
            coverUrl = item.optString("cover").takeIf { it.startsWith("https://", true) },
            platform = item.optString("platform").ifBlank { "Xbox" },
            mirrors = mirrors,
          ),
        )
      }
    }
  }

  /**
   * Quick server check. This measures response latency and verifies HTTP Range support;
   * it does not label a mirror "fast" based on a HEAD request alone.
   */
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
      val rangeSupported = status == HttpURLConnection.HTTP_PARTIAL
      if (status in 200..299) {
        // A range-aware endpoint returns at most one byte for this probe.
        runCatching { connection.inputStream.use { it.read() } }
        MirrorCheck(
          mirror = mirror,
          reachable = true,
          rangeSupported = rangeSupported,
          responseBytes = connection.getHeaderField("Content-Range")
            ?.substringAfterLast('/')?.toLongOrNull()
            ?: connection.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0 },
          latencyMillis = elapsed,
        )
      } else {
        MirrorCheck(mirror, false, false, null, elapsed, "HTTP $status")
      }
    } catch (error: Exception) {
      MirrorCheck(mirror, false, false, null, null, error.message ?: "Falha de conexão")
    } finally {
      connection.disconnect()
    }
  }

  private fun requireHttps(value: String) {
    require(value.startsWith("https://", ignoreCase = true)) {
      "A fonte do catálogo precisa usar HTTPS"
    }
  }
}
