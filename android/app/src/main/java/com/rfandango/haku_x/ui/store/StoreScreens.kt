package com.rfandango.haku_x.ui.store
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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

/** Xbox Classic online library. The catalog loads automatically and is paginated. */
@Composable
fun StoreScreen() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current
  var query by remember { mutableStateOf("") }
  var selectedStoreTab by remember { mutableStateOf("catalog") }
  var customUrl by remember { mutableStateOf("") }
  var activeCustomUrl by remember { mutableStateOf("") }
  var customPageError by remember { mutableStateOf<String?>(null) }
  var results by remember { mutableStateOf<List<ArchiveXboxItem>>(emptyList()) }
  var message by remember { mutableStateOf("Carregando biblioteca online do Xbox Clássico…") }
  var loading by remember { mutableStateOf(false) }
  var checkingId by remember { mutableStateOf<String?>(null) }
  var currentPage by remember { mutableStateOf(0) }
  var hasMore by remember { mutableStateOf(true) }
  var loadedQuery by remember { mutableStateOf("") }
  var selectedGenre by remember { mutableStateOf("Todos") }
  var favoritesOnly by remember { mutableStateOf(false) }
  var alphabetical by remember { mutableStateOf(false) }
  var favorites by remember { mutableStateOf(context.getSharedPreferences("hakux_store_favorites", android.content.Context.MODE_PRIVATE).getStringSet("ids", emptySet())?.toSet() ?: emptySet()) }
  var selectedGame by remember { mutableStateOf<ArchiveXboxItem?>(null) }
  var options by remember { mutableStateOf<List<ArchiveDownloadOption>>(emptyList()) }
  var selectedOption by remember { mutableStateOf<ArchiveDownloadOption?>(null) }

  fun toggleFavorite(id: String) {
    favorites = if (id in favorites) favorites - id else favorites + id
    context.getSharedPreferences("hakux_store_favorites", android.content.Context.MODE_PRIVATE).edit().putStringSet("ids", favorites.toSet()).apply()
  }

  fun loadCatalog(reset: Boolean, term: String) {
    if (loading) return
    scope.launch {
      loading = true
      message = if (reset) "Carregando catálogo do Xbox Clássico…" else "Carregando mais jogos…"
      try {
        val nextPage = if (reset) 1 else currentPage + 1
        val page = withContext(Dispatchers.IO) { InternetArchiveXboxRepository.searchPage(term, page = nextPage, rows = 100) }
        results = if (reset) page.items else results + page.items.filterNot { new -> results.any { it.identifier == new.identifier } }
        currentPage = page.page
        hasMore = page.hasMore
        loadedQuery = term
        message = "${page.total} registros encontrados · ${results.size} carregados"
      } catch (error: Exception) {
        message = error.message ?: "Não foi possível carregar a biblioteca online."
        if (reset) results = emptyList()
      } finally {
        loading = false
      }
    }
  }

  val gridState = rememberLazyGridState()
  val genres = listOf("Todos", "FPS", "Luta", "Corrida", "Ação", "Esportes", "RPG", "Plataforma", "Outros")
  val visibleGames = remember(results, selectedGenre, favoritesOnly, favorites, alphabetical) {
    val filtered = results.filter { game -> (selectedGenre == "Todos" || game.genre == selectedGenre) && (!favoritesOnly || game.identifier in favorites) }
    if (alphabetical) filtered.sortedBy { it.title.lowercase(java.util.Locale.ROOT) } else filtered
  }
  LaunchedEffect(Unit) { loadCatalog(reset = true, term = "") }
  LaunchedEffect(gridState, visibleGames.size, hasMore, loading, loadedQuery, query) {
    androidx.compose.runtime.snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
      .collect { lastVisible ->
        if (!loading && hasMore && loadedQuery == query.trim() && lastVisible >= visibleGames.size - 8 && visibleGames.isNotEmpty()) {
          loadCatalog(reset = false, term = loadedQuery)
        }
      }
  }

  Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(selected = selectedStoreTab == "catalog", onClick = { selectedStoreTab = "catalog" }, label = { Text("Catálogo Xbox") })
      FilterChip(selected = selectedStoreTab == "link", onClick = { selectedStoreTab = "link" }, label = { Text("Importar por link") })
    }
    if (selectedStoreTab == "catalog") {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text("Biblioteca Xbox Clássico", style = MaterialTheme.typography.headlineSmall)
      Text("Catálogo online · ${results.size} jogos carregados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Pesquisar no catálogo") }, placeholder = { Text("Nome do jogo") })
      Button(onClick = { loadCatalog(reset = true, term = query.trim()) }, enabled = !loading && (query.isBlank() || query.trim().length >= 2)) { Text("Buscar") }
    }
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      genres.forEach { genre -> FilterChip(selected = selectedGenre == genre, onClick = { selectedGenre = genre }, label = { Text(genre) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text("♥ Favoritos") })
      FilterChip(selected = alphabetical, onClick = { alphabetical = !alphabetical }, label = { Text(if (alphabetical) "A–Z" else "Ordem do catálogo") })
      TextButton(onClick = { selectedGenre = "Todos"; favoritesOnly = false; alphabetical = false; query = ""; if (loadedQuery.isNotEmpty()) loadCatalog(true, "") }) { Text("Limpar filtros") }
    }
    Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (loading && results.isEmpty()) {
      Column(modifier = Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Text("Buscando jogos do Xbox Clássico…", modifier = Modifier.padding(top = 12.dp))
      }
    } else {
      LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(minSize = 148.dp), modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(visibleGames, key = { it.identifier }) { game ->
          Card(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
              AsyncImage(model = game.coverUrl, contentDescription = "Capa de ${game.title}", modifier = Modifier.fillMaxWidth().aspectRatio(0.72f), contentScale = ContentScale.Crop)
              Text(game.title, modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, minLines = 2)
              Text(game.genre + (game.year?.let { " · $it" } ?: ""), modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
              Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { toggleFavorite(game.identifier) }) { Text(if (game.identifier in favorites) "♥" else "♡") }
                TextButton(onClick = { uriHandler.openUri(game.detailsUrl) }) { Text("Detalhes") }
              }
              Button(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), enabled = checkingId == null && !loading, onClick = {
                scope.launch {
                  checkingId = game.identifier
                  try {
                    val available = withContext(Dispatchers.IO) { ArchiveXboxDownloads.options(game.identifier) }
                    if (available.isEmpty()) message = "Nenhum arquivo de jogo com licença aberta reconhecida foi encontrado para ${game.title}."
                    else { selectedGame = game; options = available; selectedOption = available.first() }
                  } catch (error: Exception) {
                    message = error.message ?: "Não foi possível verificar os arquivos deste item."
                  } finally { checkingId = null }
                }
              }) { Text(if (checkingId == game.identifier) "Verificando…" else "↓ Download") }
            }
          }
        }
        if (visibleGames.isEmpty() && !loading) {
          item { Text(if (favoritesOnly) "Você ainda não adicionou jogos aos favoritos." else "Nenhum jogo encontrado nesta categoria.", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
      }
    }
    if (loading && results.isNotEmpty()) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
      CustomLinkBrowser(
        url = customUrl,
        onUrlChange = { customUrl = it },
        activeUrl = activeCustomUrl,
        onLoad = { raw ->
          val candidate = raw.trim().let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it" }
          val parsed = runCatching { Uri.parse(candidate) }.getOrNull()
          if (parsed?.scheme in listOf("http", "https") && !parsed.host.isNullOrBlank()) {
            activeCustomUrl = candidate
            customUrl = candidate
            customPageError = null
          } else customPageError = "Informe um endereço HTTP ou HTTPS válido."
        },
        error = customPageError,
      )
    }
  }

  selectedGame?.let { game ->
    AlertDialog(
      onDismissRequest = { selectedGame = null; options = emptyList(); selectedOption = null },
      title = { Text("Download · ${game.title}") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Escolha um arquivo disponível com licença aberta reconhecida:")
          options.forEach { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              RadioButton(selected = selectedOption == option, onClick = { selectedOption = option })
              Column {
                Text(option.name, style = MaterialTheme.typography.bodyMedium)
                Text(formatBytes(option.bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
          Text("ZIP, RAR e 7z são extraídos automaticamente para a pasta escolhida em Downloads.", style = MaterialTheme.typography.bodySmall)
        }
      },
      confirmButton = {
        TextButton(enabled = selectedOption != null, onClick = {
          selectedOption?.let { ArchiveXboxDownloads.enqueue(context, game.identifier, game.title, it) }
          message = "Download adicionado à fila. Acompanhe na aba Downloads."
          selectedGame = null; options = emptyList(); selectedOption = null
        }) { Text("Baixar para a Biblioteca") }
      },
      dismissButton = { TextButton(onClick = { selectedGame = null; options = emptyList(); selectedOption = null }) { Text("Cancelar") } },
    )
  }
}

/** Live view of downloads handled by ArchiveXboxDownloads. */
@Composable
fun DownloadsScreen() {
  val downloads by ArchiveXboxDownloads.downloads.collectAsState()
  val context = LocalContext.current
  LaunchedEffect(context) { ArchiveXboxDownloads.restore(context) }
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
              ArchiveDownload.Status.CANCELLED -> {
                Text(item.error ?: "Download interrompido.", color = MaterialTheme.colorScheme.error)
                if (item.downloadUrl != null) {
                  TextButton(onClick = { ArchiveXboxDownloads.resume(context, item) }) { Text("Continuar download") }
                }
              }
              ArchiveDownload.Status.FAILED -> {
                Text(item.error ?: "Falha no download.", color = MaterialTheme.colorScheme.error)
                if (item.downloadUrl != null) {
                  TextButton(onClick = { ArchiveXboxDownloads.resume(context, item) }) { Text("Continuar download") }
                }
              }
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


/** Optional in-app browser for user-supplied catalog pages. The original Xbox catalog remains untouched. */
@Composable
private fun CustomLinkBrowser(
  url: String,
  onUrlChange: (String) -> Unit,
  activeUrl: String,
  onLoad: (String) -> Unit,
  error: String?,
) {
  Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Abra uma página de catálogo ou uma fonte personalizada.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        modifier = Modifier.weight(1f),
        singleLine = true,
        label = { Text("Endereço do site") },
        placeholder = { Text("https://exemplo.com/roms/xbox") },
      )
      Button(onClick = { onLoad(url) }, enabled = url.isNotBlank()) { Text("Abrir") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (activeUrl.isBlank()) {
      Column(modifier = Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Cole um link para carregar o conteúdo do site nesta aba.", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    } else {
      AndroidView(
        modifier = Modifier.fillMaxWidth().weight(1f),
        factory = { context ->
          WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase(java.util.Locale.ROOT)
                return scheme != "https" && scheme != "http"
              }
            }
          }
        },
        update = { view ->
          if (view.tag != activeUrl) {
            view.tag = activeUrl
            view.loadUrl(activeUrl)
          }
        },
      )
    }
  }
}
