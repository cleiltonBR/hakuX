package com.rfandango.haku_x.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Original Xbox front-cover artwork.
 *
 * MobCat's API maps a Title ID to one or more XMIDs. The actual scans and
 * thumbnails are stored in the repository's cover/<publisher>/ and
 * thumbnail/<publisher>/ folders, named with that XMID.
 */
object XboxCoverCache {
  private const val TAG = "hakuX-covers"
  private const val API = "https://mobcat.zip/XboxIDs/api.php"
  private const val RAW = "https://raw.githubusercontent.com/MobCat/MobCats-original-xbox-game-list/main"
  private const val MAX_COVER_WIDTH = 512
  private const val MAX_COVER_HEIGHT = 640
  private const val MAX_COVER_DOWNLOAD_BYTES = 20L * 1024L * 1024L
  private const val MAX_INDEX_BYTES = 8L * 1024L * 1024L

  /** Blocking; callers must use Dispatchers.IO. */
  fun load(context: Context, titleId: Long, gameTitle: String = ""): Bitmap? {
    if (gameTitle.isNotBlank()) CustomCoverSource.load(context, gameTitle)?.let { return it }
    val id = if (titleId != 0L) String.format(Locale.ROOT, "%08X", titleId) else "name_${coverKey(gameTitle).hashCode().toUInt().toString(16)}"
    val dir = File(context.applicationContext.filesDir, "xbox_cover_cache")
    if (!dir.exists() && !dir.mkdirs()) return null
    val cached = File(dir, "$id.jpg")
    if (cached.isFile) {
      decodeCover(cached.absolutePath)?.let { return it }
      cached.delete()
    }
    val x1Cached = File(dir, "$id.x1.png")
    if (x1Cached.isFile) {
      decodeCover(x1Cached.absolutePath)?.let { return it }
      x1Cached.delete()
    }

    // A title-only lookup is still useful for imported discs whose XBE metadata
    // does not expose a valid Xbox Title ID.
    if (titleId == 0L) return loadX1Cover(context, gameTitle, dir, id)

    // Do not permanently cache misses: the previous implementation used an
    // unsupported API response shape and could poison this cache forever.
    File(dir, "$id.missing").delete()

    val xmids = try {
      resolveXmids(id)
    } catch (e: Exception) {
      Log.w(TAG, "Could not resolve Title ID $id; trying X1_Covers", e)
      emptyList()
    }
    if (xmids.isEmpty()) return loadX1Cover(context, gameTitle, dir, id)

    // Each concurrent library refresh gets its own temporary file. A shared
    // "$id.tmp" lets one caller rename/delete a file while another is writing it.
    val temp = File.createTempFile("$id-", ".tmp", dir)
    try {
      for (xmid in xmids) {
        val publisher = xmid.take(2)
        val candidates = listOf(
          "$RAW/cover/$publisher/$xmid.jpg",
          "$RAW/thumbnail/$publisher/$xmid.jpg",
        )
        for (imageUrl in candidates) {
          val bitmap = downloadBitmap(imageUrl, temp) ?: continue
          if (!temp.renameTo(cached)) {
            runCatching { temp.copyTo(cached, overwrite = true) }
              .onFailure { Log.w(TAG, "Could not cache cover for Title ID $id", it) }
          }
          Log.i(TAG, "Loaded cover for Title ID $id (XMID $xmid)")
          return bitmap
        }
      }
    } finally {
      temp.delete()
    }
    Log.i(TAG, "No MobCat cover available for Title ID $id; trying X1_Covers")
    return loadX1Cover(context, gameTitle, dir, id)
  }

  /**
   * Fallback to the X1_Covers community collection. Its files are named
   * "Game Title (Region).png"; the directory listing is cached locally so
   * the GitHub API is not queried once per game on every library refresh.
   */
  @Synchronized
  private fun loadX1Cover(context: Context, gameTitle: String, cacheDir: File, titleId: String): Bitmap? {
    if (gameTitle.isBlank()) return null
    // GitHub caps contents listings at 100 entries per page; per_page=1000
    // silently returns only the first 100 covers. Walk every page and cache
    // the complete root index for 24 hours.
    val index = File(cacheDir, "x1_covers_index_v2.json")
    val entries = try {
      val cached = index.isFile && System.currentTimeMillis() - index.lastModified() < 24L * 60 * 60 * 1000
      if (cached) {
        JSONArray(readLimited(index, MAX_INDEX_BYTES))
      } else {
        val all = JSONArray()
        for (page in 1..20) {
          val api = "https://api.github.com/repos/izzy2lost/X1_Covers/contents?per_page=100&page=$page"
          val connection = (URL(api).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "hakuX-Android/1.0")
          }
          val rows = try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("GitHub X1_Covers HTTP ${connection.responseCode}")
            JSONArray(connection.inputStream.use { input -> readLimited(input, MAX_INDEX_BYTES) })
          } finally { connection.disconnect() }
          for (i in 0 until rows.length()) all.put(rows.getJSONObject(i))
          if (rows.length() < 100) break
        }
        val serialized = all.toString()
        require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_INDEX_BYTES) { "X1_Covers index exceeds the safe cache limit" }
        index.writeText(serialized)
        all
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not load X1_Covers index", e)
      return null
    }
    val wanted = coverKey(gameTitle)
    if (wanted.isBlank()) return null
    val candidates = (0 until entries.length()).mapNotNull { i ->
      val row = entries.optJSONObject(i) ?: return@mapNotNull null
      val name = row.optString("name")
      if (!name.lowercase(Locale.ROOT).endsWith(".png")) return@mapNotNull null
      val actual = coverKey(name.substringBeforeLast('.'))
      val regionless = coverKey(name.substringBeforeLast('.').replace(Regex("\\s*\\([^)]*\\)\\s*$"), ""))
      if (actual == wanted || regionless == wanted) row.optString("download_url").takeIf { it.startsWith("https://") } else null
    }.distinct()
    for (imageUrl in candidates) {
      val file = File(cacheDir, "$titleId.x1.png")
      val bitmap = downloadBitmap(imageUrl, file) ?: continue
      Log.i(TAG, "Loaded X1_Covers artwork for Title ID $titleId")
      return bitmap
    }
    return null
  }

  private fun coverKey(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("^(microsoft\\s+)?xbox(\\s+classic)?\\s*[-:]+\\s*"), "")
    .replace(Regex("\\s*\\((usa|us|europe|european|eu|world|japan|japanese|pal|ntsc[-_ ]?[uj])\\)\\s*$"), "")
    .replace(Regex("[^a-z0-9]+"), "")

  private fun resolveXmids(id: String): List<String> {
    val connection = (URL("$API?id=$id").openConnection() as HttpURLConnection).apply {
      connectTimeout = 10000
      readTimeout = 15000
      instanceFollowRedirects = true
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "hakuX-Android/1.0")
    }
    try {
      val code = connection.responseCode
      if (code !in 200..299) throw IllegalStateException("MobCat API HTTP $code")
      val body = connection.inputStream.bufferedReader().use { it.readText() }
      val rows = JSONArray(body)
      val result = ArrayList<String>()
      for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        val xmid = row.optString("XMID").uppercase(Locale.ROOT)
        if (xmid.matches(Regex("[A-Z0-9]{8}")) && xmid !in result) result += xmid
      }
      return result
    } finally {
      connection.disconnect()
    }
  }

  private fun decodeCover(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > MAX_COVER_WIDTH || bounds.outHeight / sample > MAX_COVER_HEIGHT) {
      sample *= 2
    }
    val options = BitmapFactory.Options().apply {
      inSampleSize = sample
      inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(path, options)
  }

  private fun readLimited(file: File, maxBytes: Long): String {
    require(file.length() <= maxBytes) { "Cached cover index exceeds the safe size limit" }
    return file.inputStream().use { readLimited(it, maxBytes) }
  }

  private fun readLimited(input: java.io.InputStream, maxBytes: Long): String {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      total += count
      require(total <= maxBytes) { "X1_Covers index exceeds the safe size limit" }
      output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
  }

  private fun downloadBitmap(url: String, temp: File): Bitmap? {
    val connection = try {
      (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10000
        readTimeout = 20000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "hakuX-Android/1.0")
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not open artwork URL", e)
      return null
    }
    return try {
      if (connection.responseCode !in 200..299) return null
      if (connection.contentLengthLong > MAX_COVER_DOWNLOAD_BYTES) return null
      connection.inputStream.use { input ->
        temp.outputStream().use { output ->
          val buffer = ByteArray(32 * 1024)
          var total = 0L
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_COVER_DOWNLOAD_BYTES) {
              temp.delete()
              return null
            }
            output.write(buffer, 0, count)
          }
        }
      }
      decodeCover(temp.absolutePath)
    } catch (e: Exception) {
      Log.w(TAG, "Could not download artwork", e)
      null
    } finally {
      connection.disconnect()
    }
  }
}
