package com.rfandango.haku_x.ui.store

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
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
    // Resolve the title through Wikipedia search rather than assuming an exact
    // article slug. Prefer a matching game entry to avoid unrelated same-name pages.
    val query = URLEncoder.encode("\\"$title\\" video game", "UTF-8")
    val searchUrl = "https://" + language +
      ".wikipedia.org/w/api.php?action=query&list=search&srnamespace=0&srlimit=5&srsearch=" +
      query + "&format=json"
    val results = getJson(searchUrl)?.optJSONObject("query")?.optJSONArray("search")
      ?: return null
    val normalizedTitle = normalize(title)
    var selectedTitle: String? = null
    for (index in 0 until results.length()) {
      val candidate = results.optJSONObject(index)?.optString("title").orEmpty()
      val normalizedCandidate = normalize(candidate.substringBefore(" ("))
      if (normalizedCandidate == normalizedTitle ||
        normalizedCandidate.startsWith(normalizedTitle + " ")) {
        selectedTitle = candidate
        break
      }
    }
    val articleTitle = selectedTitle ?: return null
    val slug = URLEncoder.encode(articleTitle.replace(' ', '_'), "UTF-8").replace("+", "%20")
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
    val normalizedTitle = normalize(title)
    if (normalizedTitle.isBlank()) return emptyList()
    val queries = listOf(
      "\"$title\"",
      "$title Xbox",
      "$title game",
    ).distinct()
    val ranked = linkedMapOf<String, Int>()
    for (term in queries) {
      val query = URLEncoder.encode(term, "UTF-8")
      val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=$query&gsrnamespace=6&gsrlimit=30&prop=imageinfo&iiprop=url|size&iiurlwidth=1400&format=json&origin=*"
      val json = getJson(url) ?: continue
      val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: continue
      val iterator = pages.keys()
      while (iterator.hasNext()) {
        val page = pages.optJSONObject(iterator.next()) ?: continue
        val fileName = page.optString("title").substringAfter("File:", "").substringBeforeLast('.')
        val key = normalize(fileName)
        val score = when {
          key.contains(normalizedTitle) -> 100
          normalizedTitle.contains(key) && key.length >= 8 -> 85
          normalizedTitle.split(" ").filter { it.length >= 3 }.count { key.contains(it) } >= 2 -> 65
          else -> 0
        }
        if (score < 65) continue
        val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
        val width = info.optInt("width")
        val height = info.optInt("height")
        if (width < 300 || height < 160) continue
        val imageUrl = info.optString("thumburl").ifBlank { info.optString("url") }
        if (imageUrl.startsWith("https://")) {
          val previous = ranked[imageUrl] ?: 0
          if (score > previous) ranked[imageUrl] = score
        }
      }
    }
    return ranked.entries.sortedByDescending { it.value }.take(12).map { it.key }
  }

  private fun normalize(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()

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
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Text(game.title, style = MaterialTheme.typography.titleLarge)
      Text("Xbox Clássico", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        info?.thumbnail?.let { image ->
          AsyncImage(model = image, contentDescription = "Imagem de " + game.title, modifier = Modifier.weight(0.75f).height(112.dp), contentScale = ContentScale.Fit)
        }
        Column(modifier = Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("Gênero: " + game.genre, style = MaterialTheme.typography.bodySmall)
          Text("Lançamento: " + (info?.releaseDate ?: game.year ?: "Não identificado"), style = MaterialTheme.typography.bodySmall)
          Text("Região: " + game.region, style = MaterialTheme.typography.bodySmall)
          Text("Desenvolvedora: " + (info?.developer ?: "Não informado"), style = MaterialTheme.typography.bodySmall)
          Text("Publicadora: " + (info?.publisher ?: "Não informado"), style = MaterialTheme.typography.bodySmall)
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = section == "info", onClick = { section = "info" }, label = { Text("Informações") })
        FilterChip(selected = section == "screenshots", onClick = { section = "screenshots" }, label = { Text("Capturas") })
        FilterChip(selected = section == "gameplay", onClick = { section = "gameplay" }, label = { Text("Gameplays") })
      }
      when (section) {
        "info" -> {
          Text("Sobre o jogo", style = MaterialTheme.typography.titleMedium)
          when {
            info == null && !failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              CircularProgressIndicator()
              Text("Buscando informações do jogo…")
            }
            else -> Text(
              info?.description ?: "Não foi possível carregar informações adicionais agora.",
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 6,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
          }
          Text(
            "Fonte: " + (info?.sourceLabel ?: "Catálogo HakuX") +
              " • Conteúdo informativo sobre o jogo; pode incluir versões de outras plataformas.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
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
          Text("Vídeos do mesmo jogo, inclusive de outras plataformas, podem aparecer aqui.", style = MaterialTheme.typography.bodySmall)
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
  val context = LocalContext.current
  // Platform-agnostic search: videos from other versions are acceptable
  // when they depict the same game.
  val encoded = remember(gameTitle) { URLEncoder.encode("\\"$gameTitle\\" gameplay", "UTF-8") }
  val searchUrl = "https://www.youtube.com/results?search_query=" + encoded
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(
      "Pesquise gameplays de " + gameTitle + " diretamente no YouTube. A reprodução será aberta no aplicativo oficial ou no navegador, evitando a verificação antirrobô do WebView interno.",
      style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(
      onClick = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl))
        context.startActivity(intent)
      },
      modifier = Modifier.fillMaxWidth(),
    ) { Text("Abrir gameplays no YouTube") }
    Text(
      "Se o YouTube estiver instalado, o Android poderá abrir o aplicativo oficial. Caso contrário, abrirá o navegador. O HakuX não solicita sua senha nem tenta contornar verificações da plataforma.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
