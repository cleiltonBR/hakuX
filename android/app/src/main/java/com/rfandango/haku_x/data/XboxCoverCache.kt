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

  /** Blocking; callers must use Dispatchers.IO. */
  fun load(context: Context, titleId: Long): Bitmap? {
    if (titleId == 0L) return null
    val id = String.format(Locale.ROOT, "%08X", titleId)
    val dir = File(context.applicationContext.filesDir, "xbox_cover_cache")
    if (!dir.exists() && !dir.mkdirs()) return null
    val cached = File(dir, "$id.jpg")
    if (cached.isFile) {
      BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
      cached.delete()
    }

    // Do not permanently cache misses: the previous implementation used an
    // unsupported API response shape and could poison this cache forever.
    File(dir, "$id.missing").delete()

    val xmid = try {
      resolveXmids(id).firstOrNull()
    } catch (e: Exception) {
      Log.w(TAG, "Could not resolve Title ID $id", e)
      return null
    } ?: return null

    val publisher = xmid.take(2)
    val candidates = listOf(
      "$RAW/cover/$publisher/$xmid.jpg",
      "$RAW/thumbnail/$publisher/$xmid.jpg",
    )
    for (imageUrl in candidates) {
      val bitmap = downloadBitmap(imageUrl, File(dir, "$id.tmp")) ?: continue
      val temp = File(dir, "$id.tmp")
      if (!temp.renameTo(cached)) {
        temp.copyTo(cached, overwrite = true)
        temp.delete()
      }
      Log.i(TAG, "Loaded cover for Title ID $id (XMID $xmid)")
      return bitmap
    }
    Log.i(TAG, "No cover scan or thumbnail available for Title ID $id (XMID $xmid)")
    return null
  }

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
      connection.inputStream.use { input ->
        temp.outputStream().use { output -> input.copyTo(output) }
      }
      BitmapFactory.decodeFile(temp.absolutePath)
    } catch (e: Exception) {
      Log.w(TAG, "Could not download artwork", e)
      null
    } finally {
      connection.disconnect()
    }
  }
}
