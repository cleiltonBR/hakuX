package com.rfandango.haku_x.ui.mods

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.GameMods
import com.rfandango.haku_x.data.ModCatalog
import com.rfandango.haku_x.data.ModChoices
import com.rfandango.haku_x.ui.common.EmptyText
import com.rfandango.haku_x.ui.common.Hint
import com.rfandango.haku_x.ui.common.Section
import com.rfandango.haku_x.ui.common.SubScreen
import com.rfandango.haku_x.ui.common.SwitchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mods and patches for this exact disc release, including its remote catalog. */
@Composable
fun ModsScreen(gameKey: String, title: String, onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var game by remember(gameKey) { mutableStateOf(ModCatalog.load(context, gameKey)) }
  var downloading by remember(gameKey) { mutableStateOf(false) }
  var status by remember(gameKey) { mutableStateOf("") }
  val choices = remember(gameKey) { ModChoices(context, gameKey) }
  val mods = remember(gameKey) { mutableStateMapOf<String, Boolean>() }
  val cheats = remember(gameKey) { mutableStateMapOf<String, Boolean>() }

  fun refreshChoices(value: GameMods) {
    mods.clear()
    value.mods.forEach { mods[it.id] = choices.mod(it.id) }
    cheats.clear()
    value.cheats.forEach { cheats[it.name] = choices.cheat(it.name) }
  }
  androidx.compose.runtime.LaunchedEffect(gameKey) { refreshChoices(game) }

  SubScreen(title = stringResource(R.string.mods_title), subtitle = title, onBack = onBack) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      Section("Catálogo de mods e patches")
      Hint("Baixe os conteúdos publicados para esta versão exata do jogo. Os patches baixados ficam disponíveis após reiniciar o jogo.")
      TextButton(
        enabled = !downloading,
        onClick = {
          scope.launch {
            downloading = true
            status = "Consultando catálogo..."
            val ok = withContext(Dispatchers.IO) { ModCatalog.download(context, gameKey) }
            game = ModCatalog.load(context, gameKey)
            refreshChoices(game)
            status = if (ok) "Download concluído. Mods e patches disponíveis." else "Não há conteúdo publicado para esta versão ou o download falhou."
            downloading = false
          }
        },
      ) { Text(if (downloading) "Baixando..." else "Baixar / atualizar mods e patches") }
      if (status.isNotEmpty()) Text(status)
      if (game.isEmpty) EmptyText("Nenhum mod ou patch disponível localmente para este jogo.")
      if (game.mods.isNotEmpty()) {
        Section(stringResource(R.string.mods_section_mods))
        Hint(stringResource(R.string.mods_mods_desc))
        for (mod in game.mods) {
          val by = if (mod.author.isNotEmpty()) stringResource(R.string.mods_by, mod.author) else ""
          SwitchItem(
            title = mod.name,
            summary = listOf(mod.description, by).filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { null },
            checked = mods[mod.id] == true,
            onChange = { v -> choices.setMod(mod.id, v); mods[mod.id] = v },
          )
        }
      }
      if (game.cheats.isNotEmpty()) {
        Section(stringResource(R.string.mods_section_cheats))
        Hint(stringResource(R.string.mods_cheats_desc))
        for (cheat in game.cheats) {
          SwitchItem(
            title = cheat.name,
            summary = cheat.note.ifEmpty { null },
            checked = cheats[cheat.name] == true,
            onChange = { v -> choices.setCheat(cheat.name, v); cheats[cheat.name] = v },
          )
        }
      }
    }
  }
}
