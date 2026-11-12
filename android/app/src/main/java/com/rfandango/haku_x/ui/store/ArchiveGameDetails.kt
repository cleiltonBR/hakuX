package com.rfandango.haku_x.ui.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
    val title = game.title
    val pt = wikipediaSummary("pt", title)
    val en = if (pt == null || pt.optString("extract").isBlank()) wikipediaSummary("en", title) else null
    val summary = pt?.takeIf { it.optString("extract").isNotBlank() } ?: en
    val description = summary?.optString("extract")?.takeIf { it.isNotBlank() }
      ?: "Informações adicionais ainda não disponíveis para este jogo."
    val thumb = summary?.optJSONObject("thumbnail")?.optString("source")
      ?.takeIf { it.startsWith("https://") }
    val screenshots = commonsScreenshots(title)
    return ArchiveGameInfo(
      description = description,
      thumbnail = thumb,
      screenshots = screenshots,
      developer = null,
      publisher = null,
      releaseDate = game.year,
      sourceLabel = if (pt != null) "Wikipedia em português" else if (en != null) "Wikipedia (tradução não disponível)" else "Catálogo HakuX",
    )
  }

  private fun wikipediaSummary(language: String, title: String): JSONObject? {
    val slug = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
    return getJson("https://$language.wikipedia.org/api/rest_v1/page/summary/$slug")
  }

  private fun commonsScreenshots(title: String): List<String> {
    val query = URLEncoder.encode("\"$title\" video game screenshot", "UTF-8")
    val json = getJson("https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=$query&gsrnamespace=6&gsrlimit=8&prop=imageinfo&iiprop=url&iiurlwidth=1000&format=json&origin=*")
      ?: return emptyList()
    val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
    val iterator = pages.keys()
    val found = mutableListOf<String>()
    while (iterator.hasNext()) {
      val page = pages.optJSONObject(iterator.next()) ?: continue
      val name = page.optString("title").lowercase(Locale.ROOT)
      if (!(name.contains("screenshot") || name.contains("gameplay") || name.contains("game"))) continue
      val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
      val url = info.optString("thumburl").ifBlank { info.optString("url") }
      if (url.startsWith("https://") && url !in found) found += url
    }
    return found.take(6)
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
    } catch (_: Exception) {
      null
    } finally {
      connection.disconnect()
    }
  }
}

@Composable
internal fun ArchiveGameDetailsDialog(game: ArchiveXboxItem, onDismiss: () -> Unit) {
  var info by remember(game.identifier, game.archiveFileName) { mutableStateOf<ArchiveGameInfo?>(null) }
  var failed by remember(game.identifier, game.archiveFileName) { mutableStateOf(false) }
  LaunchedEffect(game.identifier, game.archiveFileName) {
    val loaded = withContext(Dispatchers.IO) { runCatching { ArchiveGameInfoRepository.load(game) }.getOrNull() }
    info = loaded
    failed = loaded == null
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(game.title) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text("Xbox Clássico", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Gênero: ${game.genre}")
            Text("Lançamento: ${game.year ?: "Não identificado"}")
            Text("Região: ${game.region}")
            Text("Desenvolvedora: ${info?.developer ?: "Não informado"}")
            Text("Publicadora: ${info?.publisher ?: "Não informado"}")
          }
          info?.thumbnail?.let { image ->
            AsyncImage(model = image, contentDescription = "Imagem de ${game.title}", modifier = Modifier.weight(0.8f), contentScale = ContentScale.Fit)
          }
        }
        Text("Sobre o jogo", style = MaterialTheme.typography.titleMedium)
        when {
          info == null && !failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text("Buscando informações e imagens…")
          }
          else -> Text(info?.description ?: "Não foi possível carregar informações adicionais agora.")
        }
        val screenshots = info?.screenshots.orEmpty()
        if (screenshots.isNotEmpty()) {
          Text("Capturas de tela", style = MaterialTheme.typography.titleMedium)
          screenshots.forEachIndexed { index, url ->
            AsyncImage(
              model = url,
              contentDescription = "Captura de tela ${index + 1} de ${game.title}",
              modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 230.dp),
              contentScale = ContentScale.Fit,
            )
          }
        } else if (info != null) {
          Text("Nenhuma captura de tela pública foi encontrada para este jogo.", style = MaterialTheme.typography.bodySmall)
        }
        info?.let { Text("Fonte das informações: ${it.sourceLabel}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
  )
}
