package com.rfandango.haku_x.ui.store

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rfandango.haku_x.data.store.TorrentDownload
import com.rfandango.haku_x.data.store.TorrentDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun TorrentDownloadsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val downloads by TorrentDownloadManager.downloads.collectAsState()
  var magnet by remember { mutableStateOf("") }
  var message by remember { mutableStateOf("Adicione um magnet ou selecione um arquivo .torrent.") }
  var busy by remember { mutableStateOf(false) }

  val torrentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    if (uri != null) scope.launch {
      busy = true
      message = "Importando arquivo torrent…"
      try {
        withContext(Dispatchers.IO) {
          val directory = File(context.filesDir, "torrent-sources").apply { mkdirs() }
          val target = File(directory, "import-${System.currentTimeMillis()}.torrent")
          context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
          } ?: error("Não foi possível ler o arquivo selecionado.")
          TorrentDownloadManager.addTorrentFile(context, target)
        }
        message = "Torrent adicionado à fila interna do HakuX."
      } catch (error: Exception) {
        message = error.message ?: "Não foi possível adicionar o torrent."
      } finally { busy = false }
    }
  }

  LaunchedEffect(Unit) { TorrentDownloadManager.restore(context) }

  Column(
    modifier = Modifier.fillMaxSize().background(Color(0xFF020503)).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Downloads BitTorrent", color = Color.White, style = MaterialTheme.typography.titleLarge)
      TextButton(onClick = onBack) { Text("Voltar", color = Color(0xFF39FF14)) }
    }
    OutlinedTextField(value = magnet, onValueChange = { magnet = it }, modifier = Modifier.fillMaxWidth(),
      label = { Text("Link magnet") }, placeholder = { Text("magnet:?xt=urn:btih:…") }, singleLine = true)
    Text(
      "Para jogos da Minerva, use o botão Download no próprio card. O HakuX localiza o magnet e seleciona somente o arquivo escolhido; este campo aceita apenas links magnet, não endereços de páginas.",
      color = Color(0xFFB9C5BC),
      style = MaterialTheme.typography.labelSmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = {
          scope.launch {
            busy = true
            try {
              withContext(Dispatchers.IO) { TorrentDownloadManager.addMagnet(context, magnet) }
              magnet = ""
              message = "Magnet adicionado à fila do HakuX."
            } catch (error: Exception) {
              message = error.message ?: "Magnet inválido."
            } finally { busy = false }
          }
        },
        enabled = !busy && magnet.startsWith("magnet:", true),
      ) { Text("Adicionar magnet") }
      Button(onClick = { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream")) }, enabled = !busy) {
        Text("Abrir .torrent")
      }
    }
    Text(message, color = Color(0xFFBFE8C5), style = MaterialTheme.typography.bodySmall)
    Text("Destino interno: ${File(context.filesDir, "torrent-downloads").absolutePath}", color = Color(0xFF9BA99E), style = MaterialTheme.typography.labelSmall)
    if (downloads.isEmpty()) Text("Nenhum torrent na fila.", color = Color(0xFFB9C5BC))
    else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      items(downloads, key = { it.id }) { item ->
        TorrentDownloadCard(item, { TorrentDownloadManager.pause(item.id) }, { TorrentDownloadManager.resume(item.id) }, { TorrentDownloadManager.remove(item.id) })
      }
    }
  }
}

@Composable
private fun TorrentDownloadCard(item: TorrentDownload, onPause: () -> Unit, onResume: () -> Unit, onRemove: () -> Unit) {
  val progress = if (item.total > 0L) (item.done.toFloat() / item.total).coerceIn(0f, 1f) else 0f
  Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0B120E))) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(item.name, color = Color.White, style = MaterialTheme.typography.titleSmall)
      Text("${item.state.name} · ${formatTorrentBytes(item.done)} / ${if (item.total > 0L) formatTorrentBytes(item.total) else "—"}", color = Color(0xFFB9C5BC), style = MaterialTheme.typography.labelSmall)
      LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
      Text("↓ ${formatTorrentBytes(item.downloadRate.toLong())}/s   ↑ ${formatTorrentBytes(item.uploadRate.toLong())}/s", color = Color(0xFF9BA99E), style = MaterialTheme.typography.labelSmall)
      item.error?.let { Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item.state == TorrentDownload.State.PAUSED) TextButton(onClick = onResume) { Text("Retomar") }
        else if (item.state != TorrentDownload.State.COMPLETED && item.state != TorrentDownload.State.SEEDING) TextButton(onClick = onPause) { Text("Pausar") }
        TextButton(onClick = onRemove) { Text("Remover") }
      }
    }
  }
}

private fun formatTorrentBytes(bytes: Long): String {
  if (bytes <= 0L) return "0 B"
  val units = listOf("B", "KB", "MB", "GB", "TB")
  var value = bytes.toDouble()
  var unit = 0
  while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit++ }
  return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
}
