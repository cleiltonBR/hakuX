package com.rfandango.haku_x.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rfandango.haku_x.data.AppPrefs
import com.rfandango.haku_x.data.RetroArchShaderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun RetroArchShadersSection(prefs: AppPrefs) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val scope = rememberCoroutineScope()
  val library = remember { File(context.filesDir, "retroarch-shaders") }
  var refresh by remember { mutableIntStateOf(0) }
  var busy by remember { mutableStateOf(false) }
  var operation by remember { mutableStateOf("") }
  val presets = remember(refresh) { RetroArchShaderStore.listPresets(library) }
  val selected = prefs.retroArchPresetPath

  fun installOnline(repository: String, displayName: String) {
    scope.launch {
      busy = true
      operation = "Baixando $displayName…"
      val result = runCatching {
        withContext(Dispatchers.IO) {
          RetroArchShaderStore.downloadOfficialPack(repository, displayName, library)
        }
      }
      busy = false
      operation = ""
      result.onSuccess {
        refresh++
        Toast.makeText(context, "Download concluído: ${it.presets.size} presets instalados.", Toast.LENGTH_LONG).show()
      }.onFailure {
        Toast.makeText(context, it.message ?: "Falha ao baixar shaders.", Toast.LENGTH_LONG).show()
      }
    }
  }

  val importPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    if (uri != null) {
      scope.launch {
        busy = true
        val result = runCatching {
          withContext(Dispatchers.IO) {
            val name = queryDisplayName(context, uri) ?: "shader-pack.zip"
            context.contentResolver.openInputStream(uri)?.use { input ->
              RetroArchShaderStore.importZip(input, name, library)
            } ?: error("Não foi possível abrir o arquivo selecionado")
          }
        }
        busy = false
        result.onSuccess {
          refresh++
          Toast.makeText(context, "Pacote instalado: ${it.presets.size} preset(s), ${it.fileCount} arquivo(s).", Toast.LENGTH_LONG).show()
        }.onFailure {
          Toast.makeText(context, it.message ?: "Falha ao importar shaders.", Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  Column(Modifier.fillMaxWidth()) {
    Text("Shaders RetroArch", style = MaterialTheme.typography.titleMedium)
    Text(
      "Baixe os pacotes oficiais diretamente do GitHub do RetroArch ou importe um ZIP. Os arquivos ficam salvos na biblioteca local.",
      style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = { installOnline("glsl-shaders", "RetroArch GLSL") }, enabled = !busy) {
      Text("Baixar shaders GLSL oficiais")
    }
    Button(onClick = { installOnline("slang-shaders", "RetroArch Slang") }, enabled = !busy) {
      Text("Baixar shaders Slang oficiais")
    }
    Button(
      onClick = { importPack.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
      enabled = !busy,
    ) {
      Text("Importar pacote ZIP")
    }
    if (busy) Text(operation, style = MaterialTheme.typography.bodySmall)
    if (presets.isEmpty()) {
      Text("Nenhum preset instalado. Baixe um pacote oficial ou importe um ZIP.", style = MaterialTheme.typography.bodySmall)
    } else {
      presets.forEach { preset ->
        val isSelected = selected == preset.relativePath
        ListItem(
          headlineContent = { Text(preset.relativePath.substringAfterLast('/')) },
          supportingContent = {
            Text(
              when {
                preset.requiresSlangCompiler -> "Slang · ${preset.passCount} passe(s) · requer backend Slang"
                else -> "GLSL · ${preset.passCount} passe(s)"
              },
              style = MaterialTheme.typography.bodySmall,
            )
          },
          trailingContent = { Text(if (isSelected) "Selecionado" else "Selecionar") },
          modifier = Modifier.clickable {
            prefs.retroArchPresetPath = if (isSelected) "" else preset.relativePath
            refresh++
          },
        )
      }
    }
    Text(
      if (selected.isBlank()) "Nenhum preset selecionado." else "Preset selecionado: $selected",
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
  context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0) else null
  }
