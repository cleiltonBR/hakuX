package com.rfandango.haku_x.ui.store

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.rfandango.haku_x.data.XboxCoverCache
import com.rfandango.haku_x.data.store.MinervaXboxEntry
import com.rfandango.haku_x.data.store.MinervaXboxRepository
import com.rfandango.haku_x.data.store.TorrentDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MinervaXboxNativeCatalog(onBack: () -> Unit) {
  val context = LocalContext.current
  val torrentDownloads by TorrentDownloadManager.downloads.collectAsState()
  val scope = rememberCoroutineScope()
  var query by remember { mutableStateOf("") }
  var entries by remember { mutableStateOf<List<MinervaXboxEntry>>(emptyList()) }
  var loading by remember { mutableStateOf(false) }
  var downloadingId by remember { mutableStateOf<String?>(null) }
  var message by remember { mutableStateOf("Carregando catálogo Redump Xbox do Minerva Archive…") }

  fun loadCatalog() {
    if (loading) return
    scope.launch {
      loading = true
      message = "Consultando o catálogo oficial do Minerva Archive…"
      try {
        val loaded = withContext(Dispatchers.IO) { MinervaXboxRepository.loadCatalog() }
        entries = loaded
        message = if (loaded.isEmpty()) {
          "O catálogo foi aberto, mas nenhum arquivo compatível foi identificado."
        } else {
          "${loaded.size} arquivos encontrados no catálogo Redump Xbox."
        }
      } catch (error: Exception) {
        message = error.message ?: "Não foi possível carregar o catálogo do Minerva Archive."
      } finally {
        loading = false
      }
    }
  }

  val visibleEntries = remember(entries, query) {
    val term = query.trim()
    if (term.isBlank()) entries
    else entries.filter { it.title.contains(term, ignoreCase = true) || it.fileName.contains(term, ignoreCase = true) }
  }

  LaunchedEffect(Unit) {
    TorrentDownloadManager.restore(context)
    loadCatalog()
  }

  Column(
    modifier = Modifier.fillMaxSize()
      .background(Color(0xFF020503))
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    androidx.compose.foundation.layout.Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Minerva Archive · Xbox", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text("Redump · Microsoft Xbox", color = Color(0xFF39FF14), style = MaterialTheme.typography.labelSmall)
      }
      TextButton(onClick = onBack) { Text("Voltar", color = Color(0xFF39FF14)) }
      TextButton(onClick = { loadCatalog() }, enabled = !loading) { Text("Atualizar", color = Color(0xFF39FF14)) }
    }

    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      shape = RoundedCornerShape(24.dp),
      label = { Text("Pesquisar jogos Xbox") },
      placeholder = { Text("Ex.: Halo, Forza, Burnout…") },
    )

    Text(message, color = Color(0xFFBFE8C5), style = MaterialTheme.typography.bodySmall)
    if (downloadingId != null) {
      Text("Localizando o torrent oficial e preparando o download seletivo…", color = Color(0xFFFFD27A), style = MaterialTheme.typography.labelSmall)
    }

    if (loading && entries.isEmpty()) {
      Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color(0xFF39FF14))
      }
    } else if (visibleEntries.isEmpty()) {
      Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text(
          if (entries.isEmpty()) "Nenhum item carregado. Tente atualizar ou abra o catálogo oficial no navegador."
          else "Nenhum jogo corresponde à pesquisa.",
          color = Color(0xFFB9C5BC),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        contentPadding = PaddingValues(bottom = 14.dp),
      ) {
        items(visibleEntries, key = { it.id }) { entry ->
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B120E)),
            border = BorderStroke(1.dp, Color(0xFF31543A)),
          ) {
            Column(
              modifier = Modifier.padding(9.dp),
              verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
              MinervaGameCover(title = entry.title, modifier = Modifier.fillMaxWidth().aspectRatio(0.72f))
              Text(
                entry.title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                minLines = 2,
                maxLines = 3,
              )
              Text(
                "ZIP · ${entry.sizeLabel}",
                color = Color(0xFFB9C5BC),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
              )
              val queued = torrentDownloads.firstOrNull { it.name.equals(entry.title, ignoreCase = true) }
              Button(
                onClick = {
                  if (downloadingId == null) {
                    scope.launch {
                      downloadingId = entry.id
                      message = "Localizando o torrent de ${entry.title} no índice oficial do Minerva…"
                      try {
                        val source = withContext(Dispatchers.IO) {
                          MinervaXboxRepository.resolveTorrent(entry)
                        }
                        message = "Torrent localizado. Registrando ${entry.title} na fila interna…"
                        withContext(Dispatchers.IO) {
                          TorrentDownloadManager.addMagnet(context, source.magnet, entry.title, source.filePath)
                        }
                        message = "Download de ${entry.title} registrado na fila interna. O motor BitTorrent está inicializando em segundo plano; apenas este arquivo foi selecionado e será importado para a Biblioteca ao concluir."
                      } catch (error: Exception) {
                        message = error.message ?: "Não foi possível iniciar o torrent deste jogo."
                      } finally {
                        downloadingId = null
                      }
                    }
                  }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = downloadingId == null && queued == null,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
              ) {
                Text(
                  when {
                    downloadingId == entry.id -> "Preparando…"
                    queued != null -> "Na fila"
                    else -> "↓ Download"
                  },
                  style = MaterialTheme.typography.labelSmall,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MinervaGameCover(title: String, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var cover by remember(title) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(title) {
    cover = withContext(Dispatchers.IO) { XboxCoverCache.load(context, 0L, title) }
  }
  AsyncImage(
    model = cover,
    contentDescription = "Capa de $title",
    modifier = modifier.background(Color(0xFF101A13), RoundedCornerShape(12.dp)),
    contentScale = ContentScale.Fit,
  )
}
