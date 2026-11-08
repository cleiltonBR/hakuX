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
 * MobCat's public Xbox Title ID database resolves a disc Title ID to its XMID
 * and provides the artwork URL. Images are cached per Title ID in app-private
 * storage. Network failures are not negatively cached, so the next refresh retries.
 */
object XboxCoverCache {
  private const val TAG = "hakuX-covers"
  private const val API = "https://mobcat.zip/XboxIDs/api.php"

  /** Blocking; callers must use Dispatchers.IO. */
  fun load(context: Context, titleId: Long): Bitmap? {
    if (titleId == 0L) return null
    val id = String.format(Locale.ROOT, "%08X", titleId)
    val dir = File(context.applicationContext.filesDir, "xbox_cover_cache")
    if (!dir.exists() && !dir.mkdirs()) return null
    val cached = File(dir, "$id.jpg")
    if (cached.isFile) return BitmapFactory.decodeFile(cached.absolutePath)
    val missing = File(dir, "$id.missing")
    if (missing.isFile) return null

    val coverUrl = try {
      resolveCoverUrl(id)
    } catch (e: Exception) {
      Log.w(TAG, "Could not resolve cover for $id", e)
      return null
    } ?: run {
      missing.writeText("not found")
      return null
    }

    val temp = File(dir, "$id.tmp")
    try {
      val connection = (URL(coverUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 12000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "hakuX-Android/1.0")
      }
      connection.inputStream.use { input -> temp.outputStream().use(input::copyTo) }
      connection.disconnect()
      val bitmap = BitmapFactory.decodeFile(temp.absolutePath) ?: run {
        temp.delete()
        return null
      }
      if (!temp.renameTo(cached)) {
        temp.copyTo(cached, overwrite = true)
        temp.delete()
      }
      return bitmap
    } catch (e: Exception) {
      temp.delete()
      Log.w(TAG, "Could not download cover for $id", e)
      return null
    }
  }

  private fun resolveCoverUrl(id: String): String? {
    val connection = (URL("$API?id=$id&imgs=1").openConnection() as HttpURLConnection).apply {
      connectTimeout = 8000
      readTimeout = 12000
      instanceFollowRedirects = true
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "hakuX-Android/1.0")
    }
    val body = connection.inputStream.bufferedReader().use { it.readText() }
    connection.disconnect()
    val rows = JSONArray(body)
    for (i in 0 until rows.length()) {
      val row = rows.optJSONObject(i) ?: continue
      val images = row.optJSONObject("imgs") ?: continue
      val keys = images.keys()
      var fallback: String? = null
      while (keys.hasNext()) {
        val item = images.optJSONObject(keys.next()) ?: continue
        val full = item.optString("Cover_Scan").takeIf { it.startsWith("https://") }
        if (full != null) return full
        if (fallback == null) {
          fallback = item.optString("Thumbnail").takeIf { it.startsWith("https://") }
        }
      }
      if (fallback != null) return fallback
    }
    return null
  }
}
