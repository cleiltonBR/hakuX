package com.rfandango.haku_x.ui.store

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.rfandango.haku_x.data.AppPrefs
import com.rfandango.haku_x.data.Storage
import com.rfandango.haku_x.data.store.ArchiveDownload
import com.rfandango.haku_x.data.store.ArchiveDownloadOption
import com.rfandango.haku_x.data.store.ArchiveXboxDownloads
import com.rfandango.haku_x.data.store.ArchiveXboxItem
import com.rfandango.haku_x.data.store.InternetArchiveXboxRepository
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
  var checkingFiles by remember { mutableStateOf(false) }
  var currentPage by remember { mutableStateOf(1) }
  var hasMore by remember { mutableStateOf(false) }
  var searchedQuery by remember { mutableStateOf("") }
  var selectedGame by remember { mutableStateOf<ArchiveXboxItem?>(null) }
  var options by remember { mutableStateOf<List<ArchiveDownloadOption>>(emptyList()) }
  var selectedOption by remember { mutableStateOf<ArchiveDownloadOption?>(null) }
  var dialogMessage by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current
  val context = LocalContext.current

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
            searchedQuery = query.trim()
            message = if (results.isEmpty()) "Nenhum resultado encontrado para essa pesquisa." else "${page.total} jogo(s) encontrado(s). Mostrando ${results.size}."
          } catch (error: Exception) {
            results = emptyList()
            hasMore = false
            searchedQuery = ""
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
            TextButton(
              enabled = !checkingFiles && !loading,
              onClick = {
                scope.launch {
                  checkingFiles = true
                  dialogMessage = null
                  try {
                    val available = withContext(Dispatchers.IO) { ArchiveXboxDownloads.options(game.identifier) }
                    if (available.isEmpty()) {
                      message = "Este item não oferece imagens de jogo nem arquivos ZIP/RAR/7z com licença aberta reconhecida."
                    } else {
                      selectedGame = game
                      options = available
                      selectedOption = available.first()
                    }
                  } catch (error: Exception) {
                    message = error.message ?: "Não foi possível verificar a licença e os arquivos deste item."
                  } finally {
                    checkingFiles = false
                  }
                }
              },
            ) {
              Text(if (checkingFiles) "Verificando licença…" else "Verificar download permitido")
            }
          }
        }
      }
    }
    if (hasMore && query.trim() == searchedQuery) {
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

  selectedGame?.let { game ->
    AlertDialog(
      onDismissRequest = { selectedGame = null; options = emptyList(); selectedOption = null },
      title = { Text("Download autorizado") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("O Internet Archive informa uma licença aberta compatível para este item. Escolha um arquivo de jogo:")
          options.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              RadioButton(selected = selectedOption == option, onClick = { selectedOption = option })
              Column {
                Text(option.name, style = MaterialTheme.typography.bodyMedium)
                Text(formatBytes(option.bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
          Text("Arquivos ZIP, RAR e 7z serão extraídos automaticamente. O jogo extraído será salvo na pasta escolhida em Downloads e ficará disponível na Biblioteca.", style = MaterialTheme.typography.bodySmall)
          dialogMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
      },
      confirmButton = {
        TextButton(
          enabled = selectedOption != null,
          onClick = {
            val option = selectedOption
            if (option != null) {
              ArchiveXboxDownloads.enqueue(context, game.identifier, game.title, option)
              message = "Download adicionado. Acompanhe o progresso na aba Downloads."
              selectedGame = null
              options = emptyList()
              selectedOption = null
            }
          },
        ) { Text("Baixar para a Biblioteca") }
      },
      dismissButton = {
        TextButton(onClick = { selectedGame = null; options = emptyList(); selectedOption = null }) { Text("Cancelar") }
      },
    )
  }
}

/** Live view of downloads handled by ArchiveXboxDownloads. */
@Composable
fun DownloadsScreen() {
  val downloads by ArchiveXboxDownloads.downloads.collectAsState()
  val context = LocalContext.current
  val prefs = remember { AppPrefs(context) }
  var destinationUri by remember { mutableStateOf(prefs.downloadFolderUri) }
  var destinationError by remember { mutableStateOf<String?>(null) }
  val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
    if (uri != null) {
      Storage.persistPermission(context, uri)
      val folder = DocumentFile.fromTreeUri(context, uri)
      if (Storage.isFolderReady(context, uri) && folder?.canWrite() == true) {
        prefs.downloadFolderUri = uri
        if (uri !in prefs.gamesFolders) prefs.gamesFolders = prefs.gamesFolders + uri
        destinationUri = uri
        destinationError = null
      } else {
        destinationError = "Não foi possível acessar essa pasta para leitura e gravação. Escolha outra pasta."
      }
    }
  }
  Column(
    modifier = Modifier.fillMaxSize().padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
      Column {
        Text("Downloads", style = MaterialTheme.typography.headlineSmall)
        Text("Jogos baixados e extraídos para a Biblioteca", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Pasta de destino dos jogos", style = MaterialTheme.typography.titleMedium)
      Text(
        destinationUri?.let { Storage.folderLabel(context, it) } ?: "Nenhuma pasta selecionada",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Button(onClick = { pickDestination.launch(destinationUri) }, modifier = Modifier.fillMaxWidth()) {
        Text("Escolher pasta de destino")
      }
      Text("A pasta escolhida também será adicionada às pastas monitoradas pela Biblioteca.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      destinationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (downloads.isEmpty()) {
      Column(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Nenhum download iniciado", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Text("Os downloads iniciados na Loja aparecerão aqui.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
      }
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(downloads, key = { "${it.identifier}:${it.fileName}" }) { item ->
          Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text(item.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (item.status) {
              ArchiveDownload.Status.QUEUED -> Text("Na fila")
              ArchiveDownload.Status.DOWNLOADING -> {
                val fraction = if (item.total > 0) (item.done.toFloat() / item.total).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(item.done)} / ${formatBytes(item.total)} (${(fraction * 100).toInt()}%)", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { ArchiveXboxDownloads.cancel() }) { Text("Cancelar download") }
              }
              ArchiveDownload.Status.EXTRACTING -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Extraindo arquivos compactados…")
                TextButton(onClick = { ArchiveXboxDownloads.cancel() }) { Text("Cancelar extração") }
              }
              ArchiveDownload.Status.COMPLETED -> {
                Text("Concluído", color = MaterialTheme.colorScheme.primary)
                item.outputName?.let { Text("Salvo em: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
              }
              ArchiveDownload.Status.CANCELLED -> Text("Cancelado")
              ArchiveDownload.Status.FAILED -> Text(item.error ?: "Falha no download.", color = MaterialTheme.colorScheme.error)
            }
          }
        }
      }
    }
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes <= 0L) return "Tamanho desconhecido"
  val units = listOf("B", "KB", "MB", "GB", "TB")
  var value = bytes.toDouble()
  var unit = 0
  while (value >= 1024.0 && unit < units.lastIndex) {
    value /= 1024.0
    unit++
  }
  return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
}
