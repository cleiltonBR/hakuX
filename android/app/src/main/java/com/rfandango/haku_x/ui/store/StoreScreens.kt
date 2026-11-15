package com.rfandango.haku_x.ui.store
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.res.Configuration
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.DownloadListener
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import android.graphics.Bitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rfandango.haku_x.data.CustomCoverSource
import com.rfandango.haku_x.data.XboxCoverCache
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
  val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
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
  var detailsGame by remember { mutableStateOf<ArchiveXboxItem?>(null) }
  var options by remember { mutableStateOf<List<ArchiveDownloadOption>>(emptyList()) }
  var selectedOption by remember { mutableStateOf<ArchiveDownloadOption?>(null) }
  val gridState = rememberLazyGridState()

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
        if (reset) gridState.scrollToItem(0)
        val nextPage = if (reset) 1 else currentPage + 1
        val page = withContext(Dispatchers.IO) {
          InternetArchiveXboxRepository.searchPage(term, page = nextPage, rows = 100)
        }
        val merged = if (reset) {
          page.items
        } else {
          (results + page.items).distinctBy { game ->
            game.archiveFileName?.let { "${game.identifier}:$it" } ?: "${game.identifier}:${game.title.lowercase(java.util.Locale.ROOT)}"
          }
        }
        results = merged
        currentPage = page.page
        hasMore = page.hasMore && page.items.isNotEmpty()
        loadedQuery = term
        message = "${page.total} registros encontrados · ${merged.size} carregados"
      } catch (error: Exception) {
        message = error.message ?: "Não foi possível carregar a biblioteca online."
        if (reset) {
          results = emptyList()
          currentPage = 0
          hasMore = false
        }
      } finally {
        loading = false
      }
    }
  }
  val genres = listOf("Todos", "FPS", "Luta", "Corrida", "Ação", "Esportes", "RPG", "Plataforma", "Outros")
  val visibleGames = remember(results, selectedGenre, favoritesOnly, favorites, alphabetical) {
    val filtered = results.filter { game ->
      val favoriteKey = game.archiveFileName?.let { "${game.identifier}:$it" } ?: game.identifier
      (selectedGenre == "Todos" || game.genre == selectedGenre) && (!favoritesOnly || favoriteKey in favorites)
    }
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

  if (detailsGame == null) {
  Column(
    modifier = Modifier.fillMaxSize()
      .background(Brush.verticalGradient(listOf(Color(0xFF020503), Color(0xFF07140B), Color(0xFF020503))))
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    if (selectedStoreTab == "catalog") {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.size(58.dp).background(Brush.radialGradient(listOf(Color(0xFF39FF14), Color(0xFF12351A), Color.Transparent)), CircleShape), contentAlignment = Alignment.Center) {
          Text("X", color = Color.White, style = MaterialTheme.typography.headlineLarge)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
          Text("Loja", style = MaterialTheme.typography.headlineMedium, color = Color.White)
          Text("XBOX CLÁSSICO", style = MaterialTheme.typography.titleMedium, color = Color(0xFF39FF14))
          Text("Catálogo online · ${results.size} jogos carregados", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9C5BC))
        }
      }
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        androidx.compose.material3.OutlinedButton(
          onClick = { selectedStoreTab = "vimm" },
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(1.dp, Color(0xFF39FF14)),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("V", color = Color(0xFF39FF14), style = MaterialTheme.typography.titleLarge)
            Text("Vimm's", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text("Lair", color = Color.White, style = MaterialTheme.typography.labelSmall)
          }
        }
        androidx.compose.material3.OutlinedButton(
          onClick = { selectedStoreTab = "minerva" },
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(1.dp, Color(0xFF39FF14)),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 8.dp),
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("M", color = Color(0xFF39FF14), style = MaterialTheme.typography.titleLarge)
            Text("Minerva", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text("Archive", color = Color.White, style = MaterialTheme.typography.labelSmall)
          }
        }
        androidx.compose.material3.OutlinedButton(
          onClick = { selectedStoreTab = "torrents" },
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(1.dp, Color(0xFF39FF14)),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 8.dp),
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⇩", color = Color(0xFF39FF14), style = MaterialTheme.typography.titleLarge)
            Text("Torrents", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text("P2P", color = Color.White, style = MaterialTheme.typography.labelSmall)
          }
        }
        androidx.compose.material3.OutlinedButton(
          onClick = { selectedStoreTab = "link" },
          shape = RoundedCornerShape(18.dp),
          border = BorderStroke(1.dp, Color(0xFF39FF14)),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("↗", color = Color(0xFF39FF14), style = MaterialTheme.typography.titleLarge)
            Text("Importar", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text("por link", color = Color.White, style = MaterialTheme.typography.labelSmall)
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = query, onValueChange = { query = it },
          modifier = Modifier.weight(1f).height(58.dp),
          singleLine = true, shape = RoundedCornerShape(30.dp),
          textStyle = MaterialTheme.typography.bodyMedium,
          label = { Text("⌕  Pesquisar jogos", style = MaterialTheme.typography.labelMedium) },
          placeholder = { Text("Nome do jogo...", style = MaterialTheme.typography.bodySmall) },
        )
        Button(
          modifier = Modifier.height(54.dp),
          shape = RoundedCornerShape(28.dp),
          colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF39FF14), contentColor = Color(0xFF061006)),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 15.dp),
          onClick = { loadCatalog(reset = true, term = query.trim()) },
          enabled = !loading && (query.isBlank() || query.trim().length >= 2),
        ) { Text("⌕ Buscar", style = MaterialTheme.typography.labelLarge) }
      }
      Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        genres.forEach { genre ->
          val glyph = when (genre) { "Todos" -> "▦"; "FPS" -> "🎮"; "Luta" -> "★"; "Corrida" -> "🚘"; "Ação" -> "♟"; "Esportes" -> "⚽"; "RPG" -> "⚔"; "Plataforma" -> "◈"; else -> "•" }
          FilterChip(
            modifier = Modifier.height(38.dp), selected = selectedGenre == genre,
            onClick = { selectedGenre = genre },
            label = { Text("$glyph  $genre", style = MaterialTheme.typography.labelSmall) },
            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
              selectedContainerColor = Color(0xFF39FF14), selectedLabelColor = Color(0xFF061006),
              containerColor = Color(0xFF080E0A), labelColor = Color(0xFFE4EEE6),
            ),
            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selectedGenre == genre, borderColor = Color(0xFF294A32), selectedBorderColor = Color(0xFF39FF14)),
          )
        }
      }
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
          modifier = Modifier.weight(1f).height(42.dp), selected = favoritesOnly,
          onClick = { favoritesOnly = !favoritesOnly },
          label = { Text("♥  Favoritos", style = MaterialTheme.typography.labelMedium) },
          colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF18321D), selectedLabelColor = Color(0xFFFF5252), containerColor = Color(0xFF080E0A), labelColor = Color.White),
          border = FilterChipDefaults.filterChipBorder(enabled = true, selected = favoritesOnly, borderColor = Color(0xFF294A32), selectedBorderColor = Color(0xFF39FF14)),
        )
        FilterChip(
          modifier = Modifier.weight(1f).height(42.dp), selected = alphabetical,
          onClick = { alphabetical = !alphabetical },
          label = { Text("☷  ${if (alphabetical) "A–Z" else "Ordem"}", style = MaterialTheme.typography.labelMedium) },
          colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(containerColor = Color(0xFF080E0A), labelColor = Color.White),
          border = FilterChipDefaults.filterChipBorder(enabled = true, selected = alphabetical, borderColor = Color(0xFF294A32), selectedBorderColor = Color(0xFF39FF14)),
        )
        TextButton(onClick = { selectedGenre = "Todos"; favoritesOnly = false; alphabetical = false; query = ""; if (loadedQuery.isNotEmpty()) loadCatalog(true, "") }, modifier = Modifier.weight(0.8f)) {
          Text("♟ Limpar", color = Color(0xFF39FF14), style = MaterialTheme.typography.labelMedium)
        }
      }
      Row(
        modifier = Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF0C1C10), Color(0xFF142A18), Color(0xFF0C1C10))), RoundedCornerShape(24.dp))
          .border(1.dp, Color(0xFF286A31), RoundedCornerShape(24.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        Text("▰  ${message.substringBefore("·").trim()}", color = Color(0xFFE2F5E5), style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(22.dp).background(Color(0xFF376B3D)))
        Text("♜  ${results.size} carregados", color = Color(0xFFBFE8C5), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.85f), maxLines = 1)
      }
    if (loading && results.isEmpty()) {
      Column(modifier = Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Text("Buscando jogos do Xbox Clássico…", modifier = Modifier.padding(top = 12.dp))
      }
    } else {
      LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 14.dp)) {
        items(visibleGames, key = { game -> game.archiveFileName?.let { "${game.identifier}:$it" } ?: "${game.identifier}:${game.title}" }) { game ->
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B120E)),
            border = BorderStroke(1.dp, Color(0xFF31543A)),
          ) {
            Row(modifier = Modifier.padding(7.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
              ArchiveGameCover(game = game, onClick = { detailsGame = game }, modifier = Modifier.weight(0.82f).height(148.dp))
              Column(modifier = Modifier.weight(1.18f).height(148.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Text(game.title, style = MaterialTheme.typography.titleSmall, color = Color(0xFFF0F7F1), maxLines = 2, minLines = 2)
                  Text(game.genre + (game.year?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = Color(0xFFAAB5AD), maxLines = 1)
                  Text("● ${game.region}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF39FF14), maxLines = 2)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                  TextButton(modifier = Modifier.weight(0.85f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 1.dp, vertical = 0.dp), onClick = {
                    val favoriteKey = game.archiveFileName?.let { "${game.identifier}:$it" } ?: game.identifier
                    toggleFavorite(favoriteKey)
                  }) {
                    val favoriteKey = game.archiveFileName?.let { "${game.identifier}:$it" } ?: game.identifier
                    Text(if (favoriteKey in favorites) "♥" else "♡", color = if (favoriteKey in favorites) Color(0xFFFF5252) else Color(0xFFE8F5E9), style = MaterialTheme.typography.titleMedium)
                  }
                  Button(
                    modifier = Modifier.weight(1.5f).height(34.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF39FF14), contentColor = Color(0xFF061006)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp),
                    onClick = { uriHandler.openUri(game.detailsUrl) },
                  ) { Text("ⓘ Info", style = MaterialTheme.typography.labelMedium) }
                }
              }
            }
            Button(
              modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp).height(36.dp),
              shape = RoundedCornerShape(20.dp),
              colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF39FF14), contentColor = Color(0xFF061006)),
              enabled = checkingId == null && !loading,
              onClick = {
                scope.launch {
                  checkingId = game.identifier
                  try {
                    val available = withContext(Dispatchers.IO) { ArchiveXboxDownloads.options(game.identifier, game.archiveFileName) }
                    if (available.isEmpty()) message = "Nenhum arquivo de jogo com licença aberta reconhecida foi encontrado para ${game.title}."
                    else { selectedGame = game; options = available; selectedOption = available.first() }
                  } catch (error: Exception) {
                    message = error.message ?: "Não foi possível verificar os arquivos deste item."
                  } finally { checkingId = null }
                }
              },
            ) { Text(if (checkingId == game.identifier) "Verificando…" else "↓  Baixar jogo", style = MaterialTheme.typography.labelMedium) }
          }
        }
        if (visibleGames.isEmpty() && !loading) {
          item { Text(if (favoritesOnly) "Você ainda não adicionou jogos aos favoritos nesta parte do catálogo." else "Nenhum jogo encontrado nesta categoria.", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (hasMore) {
          item {
            TextButton(
              modifier = Modifier.fillMaxWidth(),
              enabled = !loading,
              onClick = { loadCatalog(reset = false, term = loadedQuery) },
            ) {
              Text(if (loading) "Carregando…" else "Carregar mais jogos")
            }
          }
        }
      }
    }
    if (loading && results.isNotEmpty()) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else if (selectedStoreTab == "vimm") {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Vimm's Lair · Xbox Vault", style = MaterialTheme.typography.titleLarge, color = Color.White)
          Text("Catálogo oficial aberto dentro da Loja HakuX", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9C5BC))
        }
        TextButton(onClick = { selectedStoreTab = "catalog" }) { Text("Voltar", color = Color(0xFF39FF14)) }
      }
      VimmXboxNativeCatalog()
    } else if (selectedStoreTab == "minerva") {
      MinervaXboxNativeCatalog(onBack = { selectedStoreTab = "catalog" })
    } else if (selectedStoreTab == "torrents") {
      TorrentDownloadsScreen(onBack = { selectedStoreTab = "catalog" })
    } else {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Importar por link", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Color.White)
        TextButton(onClick = { selectedStoreTab = "catalog" }) { Text("Voltar ao catálogo", color = Color(0xFF39FF14)) }
      }
      CustomLinkBrowser(
        url = customUrl,
        onUrlChange = { customUrl = it },
        activeUrl = activeCustomUrl,
        onLoad = { raw ->
          val candidate = raw.trim().let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it" }
          val parsed = runCatching { Uri.parse(candidate) }.getOrNull()
          if (parsed != null && parsed.scheme in listOf("http", "https") && !parsed.host.isNullOrBlank()) {
            activeCustomUrl = candidate
            customUrl = candidate
            customPageError = null
          } else customPageError = "Informe um endereço HTTP ou HTTPS válido."
        },
        error = customPageError,
        onDownload = { downloadUrl, fileName, mimeType, contentLength ->
          runCatching {
            ArchiveXboxDownloads.enqueueDirect(
              context = context,
              title = fileName?.substringBeforeLast('.')?.ifBlank { "Download da web" } ?: "Download da web",
              rawUrl = downloadUrl,
              suggestedFileName = fileName,
              totalBytes = contentLength,
            )
          }.onSuccess {
            customPageError = "Download adicionado à fila do HakuX. Acompanhe na aba Downloads."
          }.onFailure { failure ->
            customPageError = failure.message ?: "Não foi possível iniciar este download."
          }
        },
      )
    }
  }

  } else {
    detailsGame?.let { game -> ArchiveGameDetailsScreen(game = game, onBack = { detailsGame = null }) }
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

/** Official Vimm Xbox Vault browser. It does not intercept or automate protected-game downloads. */
@Composable
private fun VimmXboxVaultBrowser() {
  Column(modifier = Modifier.fillMaxSize()) {
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
              val host = request.url.host?.lowercase(java.util.Locale.ROOT).orEmpty()
              return host != "vimm.net" && !host.endsWith(".vimm.net")
            }
          }
          loadUrl("https://vimm.net/vault/Xbox")
        }
      },
    )
  }
}

/** Loads the same multi-source cached 3D artwork used by the main library. */
@Composable
private fun ArchiveGameCover(game: ArchiveXboxItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var cover by remember(game.title) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(game.title) {
    cover = withContext(Dispatchers.IO) { XboxCoverCache.load(context, 0L, game.title) }
  }
  AsyncImage(
    model = cover,
    contentDescription = "Capa de ${game.title}",
    modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    contentScale = ContentScale.Fit,
  )
}

/** Live view of downloads handled by ArchiveXboxDownloads. */
@Composable
fun DownloadsScreen() {
  val downloads by ArchiveXboxDownloads.downloads.collectAsState()
  val context = LocalContext.current
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
  LaunchedEffect(context) {
    ArchiveXboxDownloads.restore(context)
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
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
              ArchiveDownload.Status.QUEUED -> {
                Text("Na fila")
                TextButton(onClick = { ArchiveXboxDownloads.cancel(item.identifier, item.fileName) }) { Text("Cancelar download") }
              }
              ArchiveDownload.Status.DOWNLOADING -> {
                val fraction = if (item.total > 0) (item.done.toFloat() / item.total).coerceIn(0f, 1f) else 0f
                if (item.total > 0L) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(if (item.total > 0L) "${formatBytes(item.done)} / ${formatBytes(item.total)} (${(fraction * 100).toInt()}%)" else "${formatBytes(item.done)} baixados", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { ArchiveXboxDownloads.cancel(item.identifier, item.fileName) }) { Text("Cancelar download") }
              }
              ArchiveDownload.Status.EXTRACTING -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Extraindo arquivos compactados…")
                TextButton(onClick = { ArchiveXboxDownloads.cancel(item.identifier, item.fileName) }) { Text("Cancelar extração") }
              }
              ArchiveDownload.Status.COMPLETED -> {
                Text("Concluído", color = MaterialTheme.colorScheme.primary)
                item.outputName?.let { Text("Salvo em: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { ArchiveXboxDownloads.remove(item.identifier, item.fileName) }) { Text("Remover da lista") }
              }
              ArchiveDownload.Status.CANCELLED -> {
                Text(item.error ?: "Download interrompido.", color = MaterialTheme.colorScheme.error)
                Row {
                  if (item.downloadUrl != null) {
                    TextButton(onClick = { ArchiveXboxDownloads.resume(context, item) }) { Text("Continuar download") }
                  }
                  TextButton(onClick = { ArchiveXboxDownloads.remove(item.identifier, item.fileName) }) { Text("Remover") }
                }
              }
              ArchiveDownload.Status.FAILED -> {
                Text(item.error ?: "Falha no download.", color = MaterialTheme.colorScheme.error)
                Row {
                  if (item.downloadUrl != null) {
                    TextButton(onClick = { ArchiveXboxDownloads.resume(context, item) }) { Text("Continuar download") }
                  }
                  TextButton(onClick = { ArchiveXboxDownloads.remove(item.identifier, item.fileName) }) { Text("Remover") }
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
  onDownload: (String, String?, String?, Long) -> Unit,
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
            setDownloadListener(DownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, contentLength ->
              val fileName = android.webkit.URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
              onDownload(downloadUrl, fileName, mimeType, contentLength)
            })
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
