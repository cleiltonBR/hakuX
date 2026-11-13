package com.rfandango.haku_x.ui.store

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.rfandango.haku_x.data.store.ArchiveXboxItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

private data class GameMediaVideo(val title: String, val thumbnail: String, val url: String)

private data class ArchiveGameInfo(
  val description: String,
  val cover: String?,
  val background: String?,
  val screenshots: List<String>,
  val videos: List<GameMediaVideo>,
  val developer: String?,
  val publisher: String?,
  val releaseDate: String?,
  val genres: List<String>,
  val rating: String?,
  val platforms: String?,
  val sourceLabel: String,
)

private object ArchiveGameInfoRepository {
  fun load(game: ArchiveXboxItem): ArchiveGameInfo {
    // Steam's public store endpoints provide structured descriptions, artwork,
    // screenshots and (when licensed/available) short video previews without an API key.
    steamSearch(game.title)?.let { appId ->
      steamDetails(appId)?.let { steam ->
        return steam.copy(
          releaseDate = steam.releaseDate ?: game.year,
          sourceLabel = "Steam Store · correspondência por título",
        )
      }
    }
    // Fallback for classic Xbox exclusives and titles absent from Steam.
    val pt = wikipediaSummary("pt", game.title)
    val en = if (pt == null || pt.optString("extract").isBlank()) wikipediaSummary("en", game.title) else null
    val summary = pt?.takeIf { it.optString("extract").isNotBlank() } ?: en
    val description = summary?.optString("extract")?.takeIf { it.isNotBlank() }
      ?: game.description.takeIf { it.isNotBlank() }
      ?: "Ainda não há uma descrição confiável disponível para este jogo."
    val thumb = summary?.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.startsWith("https://") }
    val shots = commonsScreenshots(game.title)
    val wikidata = summary?.optString("wikibase_item")?.takeIf { it.matches(Regex("Q[0-9]+")) }?.let(::wikidataDetails)
    return ArchiveGameInfo(description, thumb, thumb, shots, emptyList(), wikidata?.first, wikidata?.second,
      wikidata?.third ?: game.year, listOf(game.genre), null, "Xbox Clássico",
      if (pt != null) "Wikipedia + Wikimedia Commons" else if (en != null) "Wikipedia + Wikimedia Commons" else "Catálogo HakuX")
  }

  private fun steamSearch(title: String): String? {
    val query = URLEncoder.encode(title, "UTF-8")
    val json = getJson("https://store.steampowered.com/api/storesearch/?term=$query&l=brazilian&cc=br") ?: return null
    val items = json.optJSONArray("items") ?: return null
    val target = normalize(title)
    var bestId: String? = null
    var bestScore = 0
    for (i in 0 until items.length()) {
      val item = items.optJSONObject(i) ?: continue
      val candidate = normalize(item.optString("name"))
      val score = when {
        candidate == target -> 100
        candidate.startsWith(target) || target.startsWith(candidate) -> 75
        candidate.contains(target) || target.contains(candidate) -> 55
        else -> 0
      }
      if (score > bestScore) {
        bestScore = score
        bestId = item.optLong("id").takeIf { it > 0 }?.toString()
      }
    }
    return bestId?.takeIf { bestScore >= 75 }
  }

  private fun steamDetails(appId: String): ArchiveGameInfo? {
    val root = getJson("https://store.steampowered.com/api/appdetails?appids=$appId&l=brazilian&cc=br")
      ?.optJSONObject(appId) ?: return null
    if (!root.optBoolean("success")) return null
    val data = root.optJSONObject("data") ?: return null
    val screenshots = buildList {
      val array = data.optJSONArray("screenshots") ?: org.json.JSONArray()
      for (i in 0 until array.length()) {
        val image = array.optJSONObject(i)?.optString("path_full").orEmpty()
        if (image.startsWith("https://")) add(image)
      }
    }.distinct().take(18)
    val videos = buildList {
      val array = data.optJSONArray("movies") ?: org.json.JSONArray()
      for (i in 0 until array.length()) {
        val movie = array.optJSONObject(i) ?: continue
        val thumb = movie.optString("thumbnail")
        val urls = movie.optJSONObject("mp4")
        val video = urls?.optString("max")?.takeIf { it.startsWith("https://") }
          ?: urls?.optString("480")?.takeIf { it.startsWith("https://") }
          ?: continue
        if (thumb.startsWith("https://")) add(GameMediaVideo(movie.optString("name").ifBlank { "Vídeo de gameplay" }, thumb, video))
      }
    }.take(6)
    fun strings(key: String): List<String> {
      val array = data.optJSONArray(key) ?: return emptyList()
      return buildList { for (i in 0 until array.length()) array.optJSONObject(i)?.optString("description")?.takeIf(String::isNotBlank)?.let(::add) }
    }
    val release = data.optJSONObject("release_date")?.optString("date")?.takeIf { it.isNotBlank() && it != "Coming soon" }
    val developers = data.optJSONArray("developers")
    val publishers = data.optJSONArray("publishers")
    val platforms = data.optJSONObject("platforms")
    val platformNames = platforms?.let { listOf("windows" to "PC", "mac" to "Mac", "linux" to "Linux").filter { pair -> it.optBoolean(pair.first) }.joinToString(", ") { it.second } }
    return ArchiveGameInfo(
      description = data.optString("short_description").ifBlank { data.optString("about_the_game").replace(Regex("<[^>]*>"), " ").trim() },
      cover = data.optString("header_image").takeIf { it.startsWith("https://") },
      background = data.optString("background").takeIf { it.startsWith("https://") }
        ?: data.optString("background_raw").takeIf { it.startsWith("https://") },
      screenshots = screenshots,
      videos = videos,
      developer = developers?.optString(0)?.takeIf { it.isNotBlank() },
      publisher = publishers?.optString(0)?.takeIf { it.isNotBlank() },
      releaseDate = release,
      genres = strings("genres").ifEmpty { emptyList() },
      rating = data.optJSONObject("metacritic")?.optInt("score")?.takeIf { it > 0 }?.let { "$it/100 Metacritic" },
      platforms = platformNames?.ifBlank { null },
      sourceLabel = "Steam Store",
    )
  }

  private fun wikipediaSummary(language: String, title: String): JSONObject? {
    val query = URLEncoder.encode("\"$title\" video game", "UTF-8")
    val results = getJson("https://$language.wikipedia.org/w/api.php?action=query&list=search&srnamespace=0&srlimit=6&srsearch=$query&format=json")
      ?.optJSONObject("query")?.optJSONArray("search") ?: return null
    val normalizedTitle = normalize(title)
    for (index in 0 until results.length()) {
      val candidate = results.optJSONObject(index)?.optString("title").orEmpty()
      val normalizedCandidate = normalize(candidate.substringBefore(" ("))
      if (normalizedCandidate == normalizedTitle || normalizedCandidate.startsWith("$normalizedTitle ")) {
        val slug = URLEncoder.encode(candidate.replace(' ', '_'), "UTF-8").replace("+", "%20")
        return getJson("https://$language.wikipedia.org/api/rest_v1/page/summary/$slug")
      }
    }
    return null
  }

  private fun wikidataDetails(id: String): Triple<String?, String?, String?>? {
    val json = getJson("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$id&props=claims%7Clabels&languages=pt%7Cen&format=json") ?: return null
    val entity = json.optJSONObject("entities")?.optJSONObject(id) ?: return null
    val claims = entity.optJSONObject("claims") ?: return null
    fun linkedLabel(property: String): String? {
      val entityId = claims.optJSONArray(property)?.optJSONObject(0)?.optJSONObject("mainsnak")
        ?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id") ?: return null
      val linked = getJson("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$entityId&props=labels&languages=pt%7Cen&format=json")
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
    val query = URLEncoder.encode("\"$title\" video game screenshot", "UTF-8")
    val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=$query&gsrnamespace=6&gsrlimit=20&prop=imageinfo&iiprop=url|size&iiurlwidth=1400&format=json&origin=*"
    val pages = getJson(url)?.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
    val target = normalize(title)
    val matches = mutableListOf<Pair<Int, String>>()
    val iterator = pages.keys()
    while (iterator.hasNext()) {
      val page = pages.optJSONObject(iterator.next()) ?: continue
      val file = normalize(page.optString("title").substringAfter("File:").substringBeforeLast('.'))
      val score = when {
        file.contains(target) -> 100
        target.split(" ").filter { it.length > 2 }.count { file.contains(it) } >= 2 -> 70
        else -> 0
      }
      if (score < 70) continue
      val image = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
      if (image.optInt("width") < 300 || image.optInt("height") < 160) continue
      val imageUrl = image.optString("thumburl").ifBlank { image.optString("url") }
      if (imageUrl.startsWith("https://")) matches.add(score to imageUrl)
    }
    return matches.sortedByDescending { it.first }.distinctBy { it.second }.take(12).map { it.second }
  }

  private fun normalize(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), " ").trim()

  private fun getJson(rawUrl: String): JSONObject? {
    val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
      connectTimeout = 9000
      readTimeout = 12000
      instanceFollowRedirects = true
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "HakuX-Android/1.0 (game metadata and media)")
    }
    return try {
      if (connection.responseCode in 200..299) JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }) else null
    } catch (_: Exception) { null } finally { connection.disconnect() }
  }
}

@Composable
internal fun ArchiveGameDetailsScreen(game: ArchiveXboxItem, onBack: () -> Unit) {
  val context = LocalContext.current
  var info by remember(game.identifier, game.archiveFileName) { mutableStateOf<ArchiveGameInfo?>(null) }
  var loading by remember(game.identifier, game.archiveFileName) { mutableStateOf(true) }
  var section by remember(game.identifier, game.archiveFileName) { mutableStateOf("overview") }
  var expandedImage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(game.identifier, game.archiveFileName) {
    loading = true
    info = withContext(Dispatchers.IO) { runCatching { ArchiveGameInfoRepository.load(game) }.getOrNull() }
    loading = false
  }

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onBack) { Text("‹ Loja") }
      Text("FICHA DO JOGO", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(modifier = Modifier.fillMaxWidth().height(218.dp)) {
        val hero = info?.background ?: info?.cover ?: info?.screenshots?.firstOrNull()
        if (hero != null) AsyncImage(model = hero, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.94f)))))
        Row(
          modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp),
          horizontalArrangement = Arrangement.spacedBy(14.dp),
          verticalAlignment = Alignment.Bottom,
        ) {
          if (info?.cover != null) {
            AsyncImage(model = info?.cover, contentDescription = "Capa de ${game.title}",
              modifier = Modifier.size(width = 104.dp, height = 142.dp).clip(MaterialTheme.shapes.medium),
              contentScale = ContentScale.Crop)
          } else {
            Surface(modifier = Modifier.size(width = 104.dp, height = 142.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
              Box(contentAlignment = Alignment.Center) { Text("XBOX", style = MaterialTheme.typography.titleMedium) }
            }
          }
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(game.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("XBOX CLÁSSICO  •  ${game.year ?: "ANO NÃO IDENTIFICADO"}", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.labelSmall)
            Text(game.genre.uppercase(Locale.ROOT), color = Color.White, style = MaterialTheme.typography.labelMedium)
          }
        }
        if (loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(24.dp))
      }

      Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = section == "overview", onClick = { section = "overview" }, label = { Text("Visão geral") })
        FilterChip(selected = section == "gallery", onClick = { section = "gallery" }, label = { Text("Imagens (${info?.screenshots?.size ?: 0})") })
        FilterChip(selected = section == "videos", onClick = { section = "videos" }, label = { Text("Vídeos (${info?.videos?.size ?: 0})") })
      }

      when (section) {
        "overview" -> {
          Column(modifier = Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SOBRE O JOGO", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(info?.description ?: if (loading) "Buscando dados e artes em fontes de jogos…" else game.description.ifBlank { "Não foi possível localizar uma descrição confiável." }, style = MaterialTheme.typography.bodyMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
              Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("INFORMAÇÕES", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                MetadataRow("Desenvolvedora", info?.developer)
                MetadataRow("Publicadora", info?.publisher)
                MetadataRow("Lançamento", info?.releaseDate ?: game.year)
                MetadataRow("Gênero", info?.genres?.joinToString().takeUnless { it.isNullOrBlank() } ?: game.genre)
                MetadataRow("Plataformas", info?.platforms ?: "Xbox Clássico")
                MetadataRow("Avaliação", info?.rating)
              }
            }
            Text("Fonte: ${info?.sourceLabel ?: if (loading) "consultando" else "catálogo HakuX"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        "gallery" -> {
          Column(modifier = Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ARTES E CAPTURAS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val images = (listOfNotNull(info?.cover, info?.background) + info?.screenshots.orEmpty()).distinct()
            if (images.isEmpty()) {
              Text(if (loading) "Procurando imagens…" else "Nenhuma imagem verificada foi encontrada. Não exibiremos imagens aleatórias de outros jogos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
              images.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  pair.forEach { image ->
                    Card(modifier = Modifier.weight(1f).clickable { expandedImage = image }) {
                      AsyncImage(model = image, contentDescription = "Imagem de ${game.title}",
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f), contentScale = ContentScale.Crop)
                    }
                  }
                  if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                }
              }
            }
          }
        }
        "videos" -> {
          Column(modifier = Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("VÍDEOS E GAMEPLAY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val videos = info?.videos.orEmpty()
            if (videos.isEmpty()) {
              Text(if (loading) "Verificando trailers e vídeos disponíveis…" else "Não encontramos vídeos oficiais incorporáveis para este título. Você ainda pode pesquisar gameplays no YouTube.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
              videos.forEach { video ->
                Card(modifier = Modifier.fillMaxWidth().clickable {
                  runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.url))) }
                }) {
                  Column {
                    AsyncImage(model = video.thumbnail, contentDescription = video.title, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
                    Text(video.title, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                  }
                }
              }
            }
            OutlinedButton(onClick = {
              val query = URLEncoder.encode("\"${game.title}\" Xbox gameplay", "UTF-8")
              context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query")))
            }, modifier = Modifier.fillMaxWidth()) { Text("Buscar gameplays no YouTube") }
          }
        }
      }
      OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { Text("Voltar à loja") }
      Box(modifier = Modifier.height(8.dp))
    }
  }

  expandedImage?.let { image ->
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { expandedImage = null },
      confirmButton = { TextButton(onClick = { expandedImage = null }) { Text("Fechar") } },
      text = { AsyncImage(model = image, contentDescription = "Imagem ampliada", modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp), contentScale = ContentScale.Fit) },
    )
  }
}

@Composable
private fun MetadataRow(label: String, value: String?) {
  if (!value.isNullOrBlank()) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
      Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(value, modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
}
