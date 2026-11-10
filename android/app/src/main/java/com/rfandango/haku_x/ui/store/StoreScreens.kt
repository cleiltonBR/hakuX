package com.rfandango.haku_x.ui.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Store landing page. It deliberately does not invent catalog entries: validated,
 * populated catalog sources will be connected here as the store integration advances.
 */
@Composable
fun StoreScreen() {
  var query by remember { mutableStateOf("") }
  Column(
    modifier = Modifier.fillMaxSize().padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Icon(Icons.Filled.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
      Column {
        Text("Loja HakuX", style = MaterialTheme.typography.headlineSmall)
        Text("Encontre jogos e escolha a fonte de download", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      label = { Text("Pesquisar jogos") },
      placeholder = { Text("Nome, plataforma ou categoria") },
    )
    Column(
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("Catálogo em preparação", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
      Text(
        "Os jogos só serão exibidos quando houver fontes reais verificadas e links de arquivo disponíveis. Nenhum servidor vazio ou jogo fictício será mostrado.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
      )
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
        "Quando o gerenciador de downloads for conectado à loja, os downloads ativos, pausados e concluídos aparecerão aqui.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}
