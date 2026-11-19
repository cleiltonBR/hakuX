package com.rfandango.haku_x.ui.files

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.rfandango.haku_x.ui.common.Hint
import com.rfandango.haku_x.ui.common.Section
import com.rfandango.haku_x.ui.common.SubScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class BrowserEntry(val file: File, val label: String = file.name)

@Composable
fun FileManagerScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  var current by remember { mutableStateOf<File?>(null) }
  var exportTarget by remember { mutableStateOf<File?>(null) }
  var busy by remember { mutableStateOf(false) }
  var refresh by remember { mutableStateOf(0) }
  var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
  var showDeleteConfirmation by remember { mutableStateOf(false) }

  val roots by produceState<List<BrowserEntry>>(initialValue = emptyList(), refresh) {
    value = withContext(Dispatchers.IO) {
      val entries = context.dataDir.listFiles()?.map { BrowserEntry(it) }?.toMutableList() ?: mutableListOf()
      context.getExternalFilesDirs(null).filterNotNull().forEachIndexed { index, dir ->
        if (dir.exists()) entries += BrowserEntry(dir, if (index == 0) "external-files" else "external-files-$index")
      }
      context.externalCacheDirs.filterNotNull().forEachIndexed { index, dir ->
        if (dir.exists()) entries += BrowserEntry(dir, if (index == 0) "external-cache" else "external-cache-$index")
      }
      entries.distinctBy { it.file.absolutePath }.sortedBy { it.label.lowercase() }
    }
  }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
    val source = exportTarget
    exportTarget = null
    if (uri != null && source != null) {
      busy = true
      scope.launch {
        val result = withContext(Dispatchers.IO) {
          runCatching {
            val destination = DocumentFile.fromTreeUri(context, uri)
              ?: error("Não foi possível acessar a pasta escolhida.")
            copyInto(context, source, destination)
          }
        }
        busy = false
        result.onSuccess { snackbar.showSnackbar("Exportação concluída: ${source.name}") }
          .onFailure { snackbar.showSnackbar("Falha ao exportar: ${it.message ?: "erro desconhecido"}") }
      }
    }
  }

  val entries by produceState<List<BrowserEntry>>(initialValue = emptyList(), current, roots, refresh) {
    value = withContext(Dispatchers.IO) {
      val dir = current
      if (dir == null) roots
      else dir.listFiles()?.map { BrowserEntry(it) }?.sortedWith(compareBy<BrowserEntry>({ !it.file.isDirectory }, { it.label.lowercase() })) ?: emptyList()
    }
  }
  val sizes by produceState<Map<String, Long>>(initialValue = emptyMap(), entries, current, refresh) {
    value = withContext(Dispatchers.IO) { entries.associate { it.file.absolutePath to it.file.sizeRecursively() } }
  }

  val selectedCount = selectedPaths.size
  val allSelected = entries.isNotEmpty() && entries.all { it.file.absolutePath in selectedPaths }

  if (showDeleteConfirmation) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirmation = false },
      title = { Text("Excluir permanentemente?") },
      text = {
        Text("Você está prestes a excluir $selectedCount item(ns), incluindo todo o conteúdo das pastas selecionadas. Esta ação não pode ser desfeita.")
      },
      confirmButton = {
        TextButton(
          enabled = !busy,
          onClick = {
            showDeleteConfirmation = false
            val paths = selectedPaths.toList()
            busy = true
            scope.launch {
              val result = withContext(Dispatchers.IO) {
                runCatching {
                  val files = paths.map(::File).filter { it.exists() }
                  val topLevel = files.filter { candidate ->
                    files.none { other -> other != candidate && candidate.toPath().startsWith(other.toPath()) }
                  }
                  var deleted = 0
                  topLevel.forEach { file ->
                    check(file.deleteRecursively()) { "Não foi possível excluir ${file.name}." }
                    deleted++
                  }
                  deleted
                }
              }
              busy = false
              selectedPaths = emptySet()
              refresh++
              result.onSuccess { snackbar.showSnackbar("$it item(ns) excluído(s) permanentemente.") }
                .onFailure { snackbar.showSnackbar("Falha ao excluir: ${it.message ?: "erro desconhecido"}") }
            }
          },
        ) { Text("Excluir permanentemente") }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancelar") }
      },
    )
  }

  SubScreen(
    title = "Gerenciador de arquivos",
    onBack = {
      selectedPaths = emptySet()
      if (current != null) current = current?.parentFile else onBack()
    },
    subtitle = current?.absolutePath ?: "Armazenamento privado do HakuX",
    snackbar = snackbar,
  ) { padding ->
    LazyColumn(Modifier.fillMaxSize().padding(padding)) {
      item {
        Hint("Navegue pelos dados internos, selecione um ou vários itens e exporte cópias. A exclusão é permanente e só ocorre após confirmação.")
        Section(if (current == null) "Pastas e arquivos do aplicativo" else current?.name ?: "Conteúdo")
        if (current != null) {
          ListItem(
            headlineContent = { Text("Voltar") },
            leadingContent = { Text("📁") },
            modifier = Modifier.clickable {
              current = current?.parentFile
              selectedPaths = emptySet()
            },
          )
        }
        if (entries.isNotEmpty()) {
          ListItem(
            headlineContent = { Text(if (allSelected) "Desmarcar todos" else "Selecionar todos") },
            supportingContent = { Text("$selectedCount selecionado(s)") },
            leadingContent = {
              Checkbox(
                checked = allSelected,
                onCheckedChange = { checked ->
                  selectedPaths = if (checked) entries.map { it.file.absolutePath }.toSet() else emptySet()
                },
                enabled = !busy,
              )
            },
            trailingContent = {
              IconButton(
                onClick = { showDeleteConfirmation = true },
                enabled = selectedCount > 0 && !busy,
              ) {
                Icon(Icons.Filled.Delete, contentDescription = "Excluir itens selecionados")
              }
            },
          )
        }
        if (busy) Hint("Processando arquivos…")
      }
      items(entries, key = { it.file.absolutePath }) { entry ->
        val file = entry.file
        val path = file.absolutePath
        val size = sizes[path] ?: 0L
        ListItem(
          headlineContent = { Text(entry.label, maxLines = 1) },
          supportingContent = {
            Text((if (file.isDirectory) "Pasta" else "Arquivo") + " • " + Formatter.formatShortFileSize(context, size))
          },
          leadingContent = {
            Checkbox(
              checked = path in selectedPaths,
              onCheckedChange = { checked ->
                selectedPaths = if (checked) selectedPaths + path else selectedPaths - path
              },
              enabled = !busy,
            )
          },
          trailingContent = {
            IconButton(onClick = { exportTarget = file; picker.launch(null) }, enabled = !busy) {
              Icon(Icons.Filled.Share, contentDescription = "Exportar ${entry.label}")
            }
          },
          modifier = Modifier.clickable(enabled = file.isDirectory && !busy) {
            current = file
            selectedPaths = emptySet()
          },
        )
      }
      if (entries.isEmpty()) item { Hint("Esta pasta está vazia ou não pôde ser lida.") }
    }
  }
}

private fun File.sizeRecursively(): Long {
  if (isFile) return length()
  return listFiles()?.sumOf { it.sizeRecursively() } ?: 0L
}

private fun copyInto(context: Context, source: File, destination: DocumentFile) {
  if (source.isDirectory) {
    val folder = destination.createDirectory(source.name)
      ?: error("Não foi possível criar a pasta ${source.name}.")
    source.listFiles()?.forEach { child -> copyInto(context, child, folder) }
  } else {
    val target = destination.createFile("application/octet-stream", source.name)
      ?: error("Não foi possível criar o arquivo ${source.name}.")
    context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
      source.inputStream().use { input -> input.copyTo(output, 128 * 1024) }
    } ?: error("Não foi possível gravar o arquivo ${source.name}.")
  }
}
