package com.rfandango.haku_x.ui.store

import android.graphics.Bitmap
import android.webkit.DownloadListener
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rfandango.haku_x.data.store.TorrentDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val MINERVA_XBOX_URL = "https://minerva-archive.org/browse/Redump/Microsoft%20-%20Xbox/"

@Composable
internal fun MinervaArchiveStoreScreen(onOpenDownloads: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var message by remember { mutableStateOf("Catálogo Redump · Microsoft Xbox") }
  var webView by remember { mutableStateOf<WebView?>(null) }

  fun enqueueMagnet(magnet: String) {
    scope.launch {
      try {
        withContext(Dispatchers.IO) { TorrentDownloadManager.addMagnet(context, magnet) }
        message = "Magnet adicionado à fila BitTorrent interna do HakuX."
      } catch (error: Exception) {
        message = error.message ?: "Não foi possível adicionar o magnet."
      }
    }
  }

  fun enqueueTorrent(torrentUrl: String) {
    scope.launch {
      message = "Obtendo torrent do MiNERVA…"
      try {
        withContext(Dispatchers.IO) {
          val parsed = URL(torrentUrl)
          require(parsed.protocol == "https" && parsed.host.equals("minerva-archive.org", true)) {
            "Endereço torrent fora do domínio MiNERVA."
          }
          val connection = parsed.openConnection() as HttpURLConnection
          connection.connectTimeout = 20000
          connection.readTimeout = 60000
          connection.instanceFollowRedirects = true
          connection.setRequestProperty("User-Agent", "HakuX-Android/1.0")
          try {
            check(connection.responseCode in 200..299) {
              "MiNERVA respondeu HTTP ${connection.responseCode}."
            }
            check(connection.contentLengthLong <= 64L * 1024L * 1024L) {
              "O arquivo torrent excede o limite de 64 MiB."
            }
            val directory = File(context.filesDir, "torrent-sources").apply { mkdirs() }
            val target = File(directory, "minerva-${System.currentTimeMillis()}.torrent")
            connection.inputStream.use { input ->
              target.outputStream().use { output -> input.copyTo(output) }
            }
            check(target.length() in 1..(64L * 1024L * 1024L)) { "Arquivo torrent inválido ou muito grande." }
            TorrentDownloadManager.addTorrentFile(context, target)
          } finally {
            connection.disconnect()
          }
        }
        message = "Torrent adicionado à fila interna. Abra Downloads para acompanhar."
      } catch (error: Exception) {
        message = error.message ?: "Não foi possível obter o torrent."
      }
    }
  }

  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("MiNERVA · Redump Xbox", color = Color.White, style = MaterialTheme.typography.titleSmall)
      Button(onClick = onOpenDownloads) { Text("Downloads") }
    }
    Text(message, color = Color(0xFFBFE8C5), style = MaterialTheme.typography.labelSmall)
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        WebView(ctx).apply {
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          settings.loadsImagesAutomatically = true
          webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
              val target = request.url.toString()
              if (target.startsWith("magnet:", true)) {
                enqueueMagnet(target)
                return true
              }
              if (target.startsWith("https://minerva-archive.org/", true) &&
                target.substringBefore('?').endsWith(".torrent", true)
              ) {
                enqueueTorrent(target)
                return true
              }
              return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
              message = "Navegando no catálogo MiNERVA…"
            }

            override fun onPageFinished(view: WebView, url: String?) {
              message = "Escolha um jogo e toque em Open Magnet Link ou Download Torrent."
            }
          }
          setDownloadListener(DownloadListener { url, _, _, _, _ ->
            if (url.startsWith("https://minerva-archive.org/", true) &&
              url.substringBefore('?').endsWith(".torrent", true)
            ) enqueueTorrent(url)
            else message = "Somente arquivos torrent do domínio MiNERVA são aceitos aqui."
          })
          loadUrl(MINERVA_XBOX_URL)
          webView = this
        }
      },
      update = { webView = it },
    )
  }

  DisposableEffect(Unit) {
    onDispose {
      webView?.stopLoading()
      webView?.destroy()
      webView = null
    }
  }
}
