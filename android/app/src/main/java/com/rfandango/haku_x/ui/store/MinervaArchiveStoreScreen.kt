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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.rfandango.haku_x.data.CustomCoverSource
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
  var availableFiles by remember { mutableStateOf<List<TorrentDownloadManager.TorrentFileEntry>>(emptyList()) }
  var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
  var pendingTorrent by remember { mutableStateOf<File?>(null) }
  var selecting by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }

  fun enqueueMagnet(magnet: String) {
    message = "Para evitar baixar a coleção Xbox inteira, use “Download Torrent”. O HakuX abrirá a seleção dos jogos."
  }

  fun enqueueTorrent(torrentUrl: String) {
    if (busy) return
    scope.launch {
      busy = true
      message = "Obtendo catálogo torrent do MiNERVA…"
      try {
        val target = withContext(Dispatchers.IO) {
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
            check(connection.responseCode in 200..299) { "MiNERVA respondeu HTTP ${connection.responseCode}." }
            check(connection.contentLengthLong <= 64L * 1024L * 1024L) { "O arquivo torrent excede o limite de 64 MiB." }
            val directory = File(context.filesDir, "torrent-sources").apply { mkdirs() }
            val file = File(directory, "minerva-${System.currentTimeMillis()}.torrent")
            connection.inputStream.use { input ->
              file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                  val count = input.read(buffer)
                  if (count < 0) break
                  total += count
                  require(total <= 64L * 1024L * 1024L) { "O arquivo torrent excede o limite de 64 MiB." }
                  output.write(buffer, 0, count)
                }
              }
            }
            check(file.length() > 0L) { "O MiNERVA retornou um arquivo torrent vazio." }
            file
          } finally { connection.disconnect() }
        }
        val entries = withContext(Dispatchers.IO) { TorrentDownloadManager.inspectTorrentFile(target) }
        check(entries.isNotEmpty()) { "Nenhum arquivo ZIP foi encontrado neste torrent." }
        pendingTorrent = target
        availableFiles = entries
        selectedIndices = emptySet()
        selecting = true
        message = "Selecione somente os jogos que deseja baixar."
      } catch (error: Exception) {
        message = error.message ?: "Não foi possível preparar a seleção do torrent."
      } finally { busy = false }
    }
  }

  if (selecting) {
    AlertDialog(
      onDismissRequest = { selecting = false; pendingTorrent = null },
      title = { Text("Escolher jogos para baixar") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("${availableFiles.size} arquivos ZIP disponíveis. Apenas os marcados serão baixados.")
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { selectedIndices = availableFiles.map { it.index }.toSet() }) { Text("Todos") }
            TextButton(onClick = { selectedIndices = emptySet() }) { Text("Limpar") }
          }
          LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(availableFiles, key = { it.index }) { entry ->
              Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Start) {
                Checkbox(
                  checked = entry.index in selectedIndices,
                  onCheckedChange = { checked ->
                    selectedIndices = if (checked) selectedIndices + entry.index else selectedIndices - entry.index
                  },
                )
                Text(File(entry.path).name, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          enabled = selectedIndices.isNotEmpty() && !busy,
          onClick = {
            val torrent = pendingTorrent ?: return@Button
            val chosen = availableFiles.filter { it.index in selectedIndices }
            scope.launch {
              busy = true
              try {
                withContext(Dispatchers.IO) { TorrentDownloadManager.addTorrentFile(context, torrent, chosen) }
                message = "${chosen.size} arquivo(s) selecionado(s). O HakuX baixará somente esses ZIPs."
                selecting = false
                pendingTorrent = null
                onOpenDownloads()
              } catch (error: Exception) {
                message = error.message ?: "Não foi possível adicionar os arquivos selecionados."
              } finally { busy = false }
            }
          },
        ) { Text(if (busy) "Adicionando…" else "Baixar selecionados (${selectedIndices.size})") }
      },
      dismissButton = { TextButton(onClick = { selecting = false; pendingTorrent = null }) { Text("Cancelar") } },
    )
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
              if (target.startsWith("magnet:", true)) { enqueueMagnet(target); return true }
              if (target.startsWith("https://minerva-archive.org/", true) &&
                target.substringBefore('?').endsWith(".torrent", true)
              ) { enqueueTorrent(target); return true }
              return false
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
              message = "Navegando no catálogo MiNERVA…"
            }
            override fun onPageFinished(view: WebView, url: String?) {
              message = "Abra um jogo e toque em Download Torrent para escolher os ZIPs."
              scope.launch {
                val manifest = withContext(Dispatchers.IO) { CustomCoverSource.manifestJson(context) }
                if (manifest != null && view === webView) {
                  val script = """
                    (function() {
                      const data = $manifest;
                      const covers = data.covers || {};
                      const normalize = value => value.toLowerCase()
                        .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
                        .replace(/\.zip$/i, '').replace(/[^a-z0-9]+/g, '');
                      const index = {};
                      Object.keys(covers).forEach(title => {
                        const url = covers[title];
                        index[normalize(title)] = url;
                        const base = title.replace(/\s*\\([^)]*\\)\s*$/, '');
                        index[normalize(base)] = index[normalize(base)] || url;
                      });
                      function addCovers() {
                        document.querySelectorAll('a[href*="/rom?id="]').forEach(link => {
                          if (link.querySelector('img[data-hakux-cover="true"]')) return;
                          const title = (link.textContent || '').trim();
                          if (!title) return;
                          let key = normalize(title);
                          let image = index[key];
                          let candidate = title.replace(/\.zip$/i, '');
                          while (!image && /\s*\\([^)]*\\)\s*$/.test(candidate)) {
                            candidate = candidate.replace(/\s*\\([^)]*\\)\s*$/, '');
                            image = index[normalize(candidate)];
                          }
                          if (!image) return;
                          const cover = document.createElement('img');
                          cover.src = image;
                          cover.alt = 'Capa 3D de ' + title;
                          cover.dataset.hakuxCover = 'true';
                          cover.loading = 'lazy';
                          cover.style.cssText = 'width:76px;height:108px;object-fit:contain;flex:none;margin-right:12px;border-radius:5px;filter:drop-shadow(0 3px 5px #0009)';
                          link.style.setProperty('display', 'flex', 'important');
                          link.style.setProperty('align-items', 'center', 'important');
                          link.style.setProperty('min-height', '116px', 'important');
                          link.insertBefore(cover, link.firstChild);
                        });
                      }
                      addCovers();
                      if (!window.__hakuxCoverObserver) {
                        window.__hakuxCoverObserver = new MutationObserver(addCovers);
                        window.__hakuxCoverObserver.observe(document.body, {childList:true,subtree:true});
                      }
                    })();
                  """.trimIndent()
                  view.evaluateJavascript(script, null)
                }
              }
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
    onDispose { webView?.stopLoading(); webView?.destroy(); webView = null }
  }
}
