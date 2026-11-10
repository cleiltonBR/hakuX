package com.rfandango.haku_x.ui.store

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.rfandango.haku_x.data.store.ArchiveXboxItem
import com.rfandango.haku_x.data.store.InternetArchiveXboxRepository
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Internet Archive search, constrained to Xbox software metadata. */
@Composable
fun StoreScreen() {
  var query by remember { mutableStateOf("") }
  var results by remember { mutableStateOf<List<ArchiveXboxItem>>(emptyList()) }
  var message by remember { mutableStateOf("Pesquise jogos do Xbox clássico no Internet Archive.") }
  var loading by remember { mutableStateOf(false) }
  var currentPage by remember { mutableStateOf(1) }
  var hasMore by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current

  Column(
    modifier = Modifier.fillMaxSize().padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Icon(Icons.Filled.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
      Column {
        Text("Loja HakuX", style = MaterialTheme.typography.headlineSmall)
        Text("Jogos do Xbox clássico · Internet Archive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      label = { Text("Pesquisar jogos de Xbox") },
      placeholder = { Text("Digite o nome do jogo") },
    )
    Button(
      onClick = {
        scope.launch {
          loading = true
          message = "Pesquisando no Internet Archive…"
          try {
            val page = withContext(Dispatchers.IO) { InternetArchiveXboxRepository.searchPage(query, page = 1) }
            results = page.items
            currentPage = page.page
            hasMore = page.hasMore
            message = if (results.isEmpty()) "Nenhum resultado encontrado para essa pesquisa." else "${page.total} jogo(s) encontrado(s). Mostrando ${results.size}."
          } catch (error: Exception) {
            results = emptyList()
            hasMore = false
            message = error.message ?: "Não foi possível consultar o Internet Archive."
          } finally {
            loading = false
          }
        }
      },
      enabled = query.trim().length >= 2 && !loading,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
      Text(if (loading) " Pesquisando…" else " Pesquisar no Internet Archive")
    }
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(results, key = { it.identifier }) { game ->
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.Top,
        ) {
          AsyncImage(
            model = game.coverUrl,
            contentDescription = "Capa de ${game.title}",
            modifier = Modifier.size(width = 104.dp, height = 136.dp),
            contentScale = ContentScale.Crop,
          )
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(game.title, style = MaterialTheme.typography.titleMedium)
            game.year?.let { Text("Ano: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (game.description.isNotBlank()) {
              Text(game.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
            TextButton(onClick = { uriHandler.openUri(game.detailsUrl) }) {
              Text("Abrir no Internet Archive")
            }
          }
        }
      }
    }
    if (hasMore) {
      Button(
        onClick = {
          scope.launch {
            loading = true
            message = "Carregando mais resultados…"
            try {
              val next = withContext(Dispatchers.IO) {
                InternetArchiveXboxRepository.searchPage(query, page = currentPage + 1)
              }
              results = results + next.items.filterNot { item -> results.any { it.identifier == item.identifier } }
              currentPage = next.page
              hasMore = next.hasMore
              message = "${results.size} resultado(s) carregados de ${next.total}."
            } catch (error: Exception) {
              message = error.message ?: "Não foi possível carregar mais resultados."
            } finally {
              loading = false
            }
          }
        },
        enabled = !loading,
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(if (loading) " Carregando…" else " Carregar mais jogos")
      }
    }
  }
}

/** Download queue destination; wired to the download manager in the next integration stage. */
@Composable
fun DownloadsScreen() {
  Column(
    modifier = Modifier.fillMaxSize().padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
      Column {
        Text("Downloads", style = MaterialTheme.typography.headlineSmall)
        Text("Acompanhe os jogos que você baixar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Column(
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("Nenhum download iniciado", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
      Text(
        "Os downloads ainda não estão conectados ao gerenciador da Loja.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}
