package com.rfandango.haku_x.ui.setup

import android.net.Uri
import android.widget.Toast
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.AppPrefs
import com.rfandango.haku_x.data.LosslessDllStore
import com.rfandango.haku_x.data.Storage
import com.rfandango.haku_x.ui.common.OnboardingCard
import com.rfandango.haku_x.ui.common.OnboardingLead
import com.rfandango.haku_x.ui.common.OnboardingPage
import com.rfandango.haku_x.ui.common.GraphicsSlider

/**
 * First run: the games folder, the one thing the native core needs (it boots
 * titles with its own kernel and keeps the hard disk in app storage, so no
 * BIOS, flash or disk image is asked for).
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
  val context = LocalContext.current
  val prefs = remember { AppPrefs(context) }
  var folder by remember { mutableStateOf(prefs.gamesFolderUri) }
  var lsfgDll by remember { mutableStateOf(prefs.lsfgDllPath) }
  var lsfgMultiplier by remember { mutableStateOf(prefs.lsfgMultiplier.toFloat()) }
  var lsfgPerformance by remember { mutableStateOf(if (prefs.lsfgPerformanceMode) 1f else 0f) }
  var lsfgFlowScale by remember { mutableStateOf(prefs.lsfgFlowScalePercent.toFloat()) }
  val ready = remember(folder) { Storage.isFolderReady(context, folder) }

  val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
    if (uri != null) {
      Storage.persistPermission(context, uri)
      prefs.gamesFolderUri = uri
      folder = uri
    }
  }

  val pickLsfgDll = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    if (uri != null) {
      runCatching { LosslessDllStore.import(context, uri) }
        .onSuccess { file ->
          prefs.lsfgDllPath = file.absolutePath
          lsfgDll = file.absolutePath
          Toast.makeText(context, "Lossless.dll importada e validada.", Toast.LENGTH_LONG).show()
        }
        .onFailure { error ->
          Toast.makeText(context, error.message ?: "Falha ao importar Lossless.dll.", Toast.LENGTH_LONG).show()
        }
    }
  }

  OnboardingPage(stringResource(R.string.setup_title)) {
    OnboardingLead(stringResource(R.string.setup_subtitle))
    OnboardingCard(stringResource(R.string.setup_disc_title)) {
      Text(
        stringResource(R.string.setup_disc_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        stringResource(
          R.string.setup_disc_value,
          folder?.let { Storage.folderLabel(context, it) } ?: stringResource(R.string.setup_not_set),
        ),
        style = MaterialTheme.typography.bodyMedium,
      )
      OutlinedButton(onClick = { pickFolder.launch(folder) }) {
        Text(stringResource(R.string.setup_pick_disc))
      }
    }
    OnboardingCard("Lossless Scaling · Frame Generation") {
      Text(
        "Opcional: selecione sua Lossless.dll original (Windows x64). O HakuX guarda o arquivo em seus dados privados e lê apenas os shaders; não executa a DLL.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        if (lsfgDll.isNotBlank()) "DLL importada: ${File(lsfgDll).name}" else "Nenhuma DLL selecionada",
        style = MaterialTheme.typography.bodyMedium,
      )
      OutlinedButton(onClick = { pickLsfgDll.launch(arrayOf("*/*")) }) {
        Text(if (lsfgDll.isNotBlank()) "Substituir Lossless.dll" else "Selecionar Lossless.dll")
      }
      Text(
        "Parâmetros experimentais do LSFG",
        style = MaterialTheme.typography.titleSmall,
      )
      GraphicsSlider(
        title = "Multiplicador de quadros",
        valueLabel = "${lsfgMultiplier.toInt()}×",
        value = lsfgMultiplier,
        valueRange = 2f..4f,
        steps = 1,
        startLabel = "2×",
        endLabel = "4×",
        onValueChange = {
          lsfgMultiplier = it
          prefs.lsfgMultiplier = it.toInt()
        },
      )
      GraphicsSlider(
        title = "Modo desempenho",
        valueLabel = if (lsfgPerformance >= 0.5f) "Ativado" else "Desativado",
        value = lsfgPerformance,
        valueRange = 0f..1f,
        steps = 1,
        startLabel = "Qualidade",
        endLabel = "Desempenho",
        onValueChange = {
          lsfgPerformance = it
          prefs.lsfgPerformanceMode = it >= 0.5f
        },
      )
      GraphicsSlider(
        title = "Flow Scale",
        valueLabel = "${lsfgFlowScale.toInt()}%",
        value = lsfgFlowScale,
        valueRange = 25f..100f,
        steps = 2,
        startLabel = "25%",
        endLabel = "100%",
        onValueChange = {
          lsfgFlowScale = it
          prefs.lsfgFlowScalePercent = it.toInt()
        },
      )
      Text(
        "Os valores ficam salvos. A aplicação efetiva depende da integração do mecanismo LSFG ao renderizador Vulkan.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Spacer(Modifier.height(8.dp))
    Button(
      onClick = {
        prefs.setupComplete = true
        onDone()
      },
      enabled = ready,
      modifier = Modifier.align(Alignment.End),
    ) {
      Text(stringResource(R.string.setup_finish))
    }
  }
}
