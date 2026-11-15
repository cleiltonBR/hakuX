package com.rfandango.haku_x.ui.store

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.rfandango.haku_x.data.XboxCoverCache
import com.rfandango.haku_x.data.store.ArchiveXboxDownloads
import com.rfandango.haku_x.data.store.VimmXboxGame
import com.rfandango.haku_x.data.store.VimmXboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VimmXboxNativeCatalog() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current
  var query by remember { mutableStateOf("") }
  var games by remember { mutableStateOf<List<VimmXboxGame>>(emptyList()) }
  var loading by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf("Pesquise no catálogo Xbox do Vimm's Lair para carregar os jogos.") }
  var downloadingGameId by remember { mutableStateOf<Int?>(null) }
  var downloadStatus by remember { mutableStateOf<String?>(null) }
  var downloadFailed by remember { mutableStateOf(false) }
  var cloudflareGame by remember { mutableStateOf<VimmXboxGame?>(null) }

  fun startDownload(game: VimmXboxGame) {
    if (downloadingGameId != null) return
    scope.launch {
      downloadingGameId = game.vaultId
      downloadStatus = "Obtendo o link oficial de download para ${game.title}…"
      downloadFailed = false
      cloudflareGame = null
      try {
        val resolved = withContext(Dispatchers.IO) { VimmXboxRepository.resolveDownload(game) }
        ArchiveXboxDownloads.enqueueDirect(
          context = context,
          title = game.title,
          rawUrl = resolved.url,
          suggestedFileName = resolved.fileName,
          totalBytes = resolved.bytes,
        )
        downloadStatus = "Download de ${game.title} adicionado à fila interna. Acompanhe na aba Downloads."
      } catch (error: Exception) {
        downloadFailed = true
        val errorMessage = error.message ?: "Não foi possível iniciar o download pelo Vimm."
        downloadStatus = errorMessage
        if (errorMessage.contains("Cloudflare", ignoreCase = true)) {
          cloudflareGame = game
          runCatching { uriHandler.openUri(game.detailsUrl) }
        }
      } finally {
        downloadingGameId = null
      }
    }
  }

  fun search() {
    val term = query.trim()
    if (term.length < 2 || loading) return
    scope.launch {
      loading = true
      message = "Pesquisando no catálogo oficial do Vimm's Lair…"
      try {
        val found = withContext(Dispatchers.IO) { VimmXboxRepository.search(term) }
        games = found
        message = if (found.isEmpty()) "Nenhum jogo encontrado para “$term”." else "${found.size} jogos encontrados no Vimm's Lair."
      } catch (error: Exception) {
        games = emptyList()
        message = error.message ?: "Não foi possível consultar o catálogo do Vimm's Lair."
      } finally {
        loading = false
      }
    }
  }

  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    Text("Vimm's Lair · Xbox Vault", color = Color.White, style = MaterialTheme.typography.titleLarge)
    Text("Catálogo nativo HakuX · resultados e capas por jogo", color = Color(0xFFB9C5BC), style = MaterialTheme.typography.labelSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.weight(1f).height(58.dp),
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        label = { Text("Pesquisar jogos Xbox") },
        placeholder = { Text("Ex.: Halo, Forza, Fable…") },
      )
      Button(onClick = { search() }, enabled = !loading && query.trim().length >= 2) {
        Text("Buscar")
      }
    }
    Text(message, color = Color(0xFFBFE8C5), style = MaterialTheme.typography.bodySmall)
    downloadStatus?.let {
      Text(it, color = if (downloadFailed) MaterialTheme.colorScheme.error else Color(0xFFBFE8C5), style = MaterialTheme.typography.bodySmall)
    }
    cloudflareGame?.let { game ->
      Text(
        "A página oficial foi aberta no navegador. Conclua a verificação do Cloudflare e volte ao HakuX.",
        color = Color(0xFFFFD27A),
        style = MaterialTheme.typography.bodySmall,
      )
      Button(
        onClick = { startDownload(game) },
        enabled = downloadingGameId == null,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Tentar novamente após a verificação")
      }
    }
    if (loading) {
      Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color(0xFF39FF14))
      }
    } else if (games.isEmpty()) {
      Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text("Os resultados aparecerão aqui em cards, sem abrir o site dentro do aplicativo.", color = Color(0xFFB9C5BC), style = MaterialTheme.typography.bodyMedium)
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        contentPadding = PaddingValues(bottom = 14.dp),
      ) {
        items(games, key = { it.vaultId }) { game ->
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B120E)),
            border = BorderStroke(1.dp, Color(0xFF31543A)),
          ) {
            Column(modifier = Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
              VimmGameCover(title = game.title, modifier = Modifier.fillMaxWidth().aspectRatio(0.72f))
              Text(game.title, color = Color.White, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2)
              Text(game.regions, color = Color(0xFFB9C5BC), style = MaterialTheme.typography.labelSmall, maxLines = 2)
              Button(
                onClick = { startDownload(game) },
                enabled = downloadingGameId == null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
              ) {
                Text(
                  if (downloadingGameId == game.vaultId) "Obtendo link…" else "↓ Baixar",
                  style = MaterialTheme.typography.labelSmall,
                )
              }
              Button(
                onClick = { uriHandler.openUri(game.detailsUrl) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
              ) { Text("ⓘ Detalhes no Vimm", style = MaterialTheme.typography.labelSmall) }
            }
          }
        }
      }
    }
  }

}

@Composable
private fun VimmGameCover(title: String, modifier: Modifier = Modifier) {
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
