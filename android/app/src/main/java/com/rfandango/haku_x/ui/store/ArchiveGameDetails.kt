package com.rfandango.haku_x.ui.store

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.rfandango.haku_x.data.store.ArchiveXboxItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

private data class ArchiveGameInfo(
  val description: String,
  val thumbnail: String?,
  val screenshots: List<String>,
  val developer: String?,
  val publisher: String?,
  val releaseDate: String?,
  val sourceLabel: String,
)

private object ArchiveGameInfoRepository {
  fun load(game: ArchiveXboxItem): ArchiveGameInfo {
    val pt = wikipediaSummary("pt", game.title)
    val en = if (pt == null || pt.optString("extract").isBlank()) wikipediaSummary("en", game.title) else null
    val summary = pt?.takeIf { it.optString("extract").isNotBlank() } ?: en
    val description = summary?.optString("extract")?.takeIf { it.isNotBlank() }
      ?: "Informações adicionais ainda não disponíveis para este jogo."
    val thumb = summary?.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.startsWith("https://") }
    val shots = commonsScreenshots(game.title)
    val wikidata = summary?.optString("wikibase_item")?.takeIf { it.matches(Regex("Q[0-9]+")) }?.let(::wikidataDetails)
    return ArchiveGameInfo(description, thumb, shots, wikidata?.first, wikidata?.second,
      wikidata?.third ?: game.year,
      if (pt != null) "Wikipedia em português" else if (en != null) "Wikipedia (inglês)" else "Catálogo HakuX")
  }

  private fun wikipediaSummary(language: String, title: String): JSONObject? {
    val slug = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
    return getJson("https://" + language + ".wikipedia.org/api/rest_v1/page/summary/" + slug)
  }

  private fun wikidataDetails(id: String): Triple<String?, String?, String?>? {
    val json = getJson("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=" + id + "&props=claims%7Clabels&languages=pt%7Cen&format=json") ?: return null
    val entity = json.optJSONObject("entities")?.optJSONObject(id) ?: return null
    val claims = entity.optJSONObject("claims") ?: return null
    fun linkedLabel(property: String): String? {
      val entityId = claims.optJSONArray(property)?.optJSONObject(0)?.optJSONObject("mainsnak")
        ?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id") ?: return null
      val linked = getJson("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=" + entityId + "&props=labels&languages=pt%7Cen&format=json")
      val labels = linked?.optJSONObject("entities")?.optJSONObject(entityId)?.optJSONObject("labels")
      return labels?.optJSONObject("pt")?.optString("value")?.takeIf { it.isNotBlank() }
        ?: labels?.optJSONObject("en")?.optString("value")?.takeIf { it.isNotBlank() }
    }
    val date = claims.optJSONArray("P577")?.optJSONObject(0)?.optJSONObject("mainsnak")
      ?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time")
      ?.removePrefix("+")?.take(10)?.takeIf { it.length == 10 }
    return Triple(linkedLabel("P178"), linkedLabel("P123"), date)
  }

  private fun commonsScreenshots(title: String): List<String> {
    val query = URLEncoder.encode("\"" + title + "\" video game screenshot", "UTF-8")
    val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=" + query + "&gsrnamespace=6&gsrlimit=12&prop=imageinfo&iiprop=url&iiurlwidth=1200&format=json&origin=*"
    val json = getJson(url) ?: return emptyList()
    val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
    val iterator = pages.keys()
    val found = mutableListOf<String>()
    while (iterator.hasNext()) {
      val page = pages.optJSONObject(iterator.next()) ?: continue
      val name = page.optString("title").lowercase(Locale.ROOT)
      if (!(name.contains("screenshot") || name.contains("gameplay") || name.contains("video game"))) continue
      val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
      val imageUrl = info.optString("thumburl").ifBlank { info.optString("url") }
      if (imageUrl.startsWith("https://") && imageUrl !in found) found += imageUrl
    }
    return found.take(8)
  }

  private fun getJson(rawUrl: String): JSONObject? {
    val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
      connectTimeout = 9000
      readTimeout = 12000
      instanceFollowRedirects = true
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "HakuX-Android/1.0 (Xbox game information)")
    }
    return try {
      if (connection.responseCode !in 200..299) null
      else JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    } catch (_: Exception) { null } finally { connection.disconnect() }
  }
}

@Composable
internal fun ArchiveGameDetailsScreen(game: ArchiveXboxItem, onBack: () -> Unit) {
  var info by remember(game.identifier, game.archiveFileName) { mutableStateOf<ArchiveGameInfo?>(null) }
  var failed by remember(game.identifier, game.archiveFileName) { mutableStateOf(false) }
  var section by remember(game.identifier, game.archiveFileName) { mutableStateOf("info") }
  var expandedImage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(game.identifier, game.archiveFileName) {
    val loaded = withContext(Dispatchers.IO) { runCatching { ArchiveGameInfoRepository.load(game) }.getOrNull() }
    info = loaded
    failed = loaded == null
  }

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      TextButton(onClick = onBack) { Text("‹ Voltar à loja") }
      Text("Detalhes do jogo", style = MaterialTheme.typography.titleMedium)
    }
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(game.title, style = MaterialTheme.typography.headlineSmall)
      Text("Xbox Clássico", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        info?.thumbnail?.let { image ->
          AsyncImage(model = image, contentDescription = "Imagem de " + game.title, modifier = Modifier.weight(0.8f).height(150.dp), contentScale = ContentScale.Fit)
        }
        Column(modifier = Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Gênero: " + game.genre)
          Text("Lançamento: " + (info?.releaseDate ?: game.year ?: "Não identificado"))
          Text("Região: " + game.region)
          Text("Desenvolvedora: " + (info?.developer ?: "Não informado"))
          Text("Publicadora: " + (info?.publisher ?: "Não informado"))
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = section == "info", onClick = { section = "info" }, label = { Text("Informações") })
        FilterChip(selected = section == "screenshots", onClick = { section = "screenshots" }, label = { Text("Capturas") })
        FilterChip(selected = section == "gameplay", onClick = { section = "gameplay" }, label = { Text("Gameplays") })
      }
      when (section) {
        "info" -> {
          Text("Sobre o jogo", style = MaterialTheme.typography.titleLarge)
          when {
            info == null && !failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              CircularProgressIndicator()
              Text("Buscando informações do jogo…")
            }
            else -> Text(info?.description ?: "Não foi possível carregar informações adicionais agora.")
          }
          Text("Fonte das informações: " + (info?.sourceLabel ?: "Catálogo HakuX"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "screenshots" -> {
          Text("Capturas de tela", style = MaterialTheme.typography.titleLarge)
          val shots = info?.screenshots.orEmpty()
          when {
            info == null && !failed -> CircularProgressIndicator()
            shots.isEmpty() -> Text("Nenhuma captura de tela pública foi encontrada para este jogo.")
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              items(shots) { imageUrl ->
                Card(modifier = Modifier.fillParentMaxWidth(0.88f).clickable { expandedImage = imageUrl }) {
                  AsyncImage(model = imageUrl, contentDescription = "Captura de tela de " + game.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Fit)
                }
              }
            }
          }
          Text("A disponibilidade de imagens públicas varia por jogo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "gameplay" -> {
          Text("Vídeos de gameplay", style = MaterialTheme.typography.titleLarge)
          Text("Resultados do YouTube para " + game.title + ". Selecione um vídeo para assistir.", style = MaterialTheme.typography.bodySmall)
          YouTubeGameplaySearch(gameTitle = game.title)
        }
      }
      OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Voltar à loja") }
    }
  }

  expandedImage?.let { imageUrl ->
    AlertDialog(
      onDismissRequest = { expandedImage = null },
      confirmButton = { TextButton(onClick = { expandedImage = null }) { Text("Fechar") } },
      text = { AsyncImage(model = imageUrl, contentDescription = "Captura de tela ampliada", modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp), contentScale = ContentScale.Fit) },
    )
  }
}

@Composable
private fun YouTubeGameplaySearch(gameTitle: String) {
  val encoded = remember(gameTitle) { URLEncoder.encode(gameTitle + " Xbox gameplay", "UTF-8") }
  var loaded by remember(gameTitle) { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    AndroidView(
      modifier = Modifier.fillMaxWidth().height(520.dp),
      factory = { context ->
        WebView(context).apply {
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          settings.mediaPlaybackRequiresUserGesture = true
          settings.loadWithOverviewMode = true
          settings.useWideViewPort = true
          settings.setSupportZoom(true)
          webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
              val host = request.url.host?.lowercase(Locale.ROOT).orEmpty()
              return !(host == "youtube.com" || host.endsWith(".youtube.com") || host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
            }
            override fun onPageFinished(view: WebView, url: String) { loaded = true }
          }
          loadUrl("https://www.youtube.com/results?search_query=" + encoded)
        }
      },
      update = { view ->
        val target = "https://www.youtube.com/results?search_query=" + encoded
        if (view.tag != target) { view.tag = target; view.loadUrl(target) }
      },
    )
    if (!loaded) CircularProgressIndicator()
    Text("Os vídeos são carregados diretamente do YouTube. A reprodução depende da disponibilidade do vídeo e das regras da plataforma.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
