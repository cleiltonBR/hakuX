package com.rfandango.haku_x.data

import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the official Libretro shader repositories and installs them through
 * the same validation/staging path used by ZIP imports.
 */
object RetroArchShaderDownload {
  private const val CONNECT_TIMEOUT_MS = 20_000
  private const val READ_TIMEOUT_MS = 120_000

  fun download(repository: String, displayName: String, libraryRoot: java.io.File): RetroArchShaderStore.ImportResult {
    require(repository == "glsl-shaders" || repository == "slang-shaders") {
      "Repositório de shaders não autorizado"
    }
    val url = URL("https://codeload.github.com/libretro/$repository/zip/refs/heads/master")
    val connection = (url.openConnection() as HttpURLConnection).apply {
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      instanceFollowRedirects = true
      requestMethod = "GET"
      setRequestProperty("User-Agent", "hakuX-RetroArch-Shader-Manager")
      setRequestProperty("Accept", "application/zip")
    }
    try {
      val status = connection.responseCode
      require(status == HttpURLConnection.HTTP_OK) { "GitHub respondeu HTTP $status ao baixar $repository" }
      val length = connection.contentLengthLong
      require(length <= 256L * 1024 * 1024 || length < 0) { "Pacote oficial excede o limite de download de 256 MiB" }
      return connection.inputStream.use { input ->
        RetroArchShaderStore.importZip(input, displayName, libraryRoot)
      }
    } finally {
      connection.disconnect()
    }
  }
}
