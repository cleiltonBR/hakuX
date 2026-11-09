package com.rfandango.haku_x.ui.library

import android.content.res.Configuration
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rfandango.haku_x.R
import com.rfandango.haku_x.data.DiscInfo
import com.rfandango.haku_x.data.Game
import com.rfandango.haku_x.data.GameOverrides
import com.rfandango.haku_x.data.ModCatalog
import com.rfandango.haku_x.data.SaveStateStore
import com.rfandango.haku_x.data.Updates
import com.rfandango.haku_x.data.XisoConverter
import com.rfandango.haku_x.ui.common.ConfirmDialog
import com.rfandango.haku_x.ui.common.ProgressBlock
import com.rfandango.haku_x.ui.common.ThumbnailShape
import com.rfandango.haku_x.ui.common.fractionOf
import com.rfandango.haku_x.ui.common.rememberFocusOnShow
import com.rfandango.haku_x.ui.update.UpdateDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  onLaunch: (Game) -> Unit,
  onOpenSettings: () -> Unit,
  onGameSettings: (Game, DiscInfo?) -> Unit,
  /** Start the game right into a savestate: its store and slot. */
  onLoadState: (Game, SaveStateStore, Int) -> Unit,
  /** The game's mods and cheats (a disc whose title has some, mods/). */
  onMods: (Game, DiscInfo) -> Unit,
  /** The dashboard started (installed on C:, Dashboard). */
  onDashboard: () -> Unit,
  vm: LibraryViewModel = viewModel(),
) {
  val state by vm.state.collectAsStateWithLifecycle()
  val update by Updates.available.collectAsStateWithLifecycle()
  var updateDialog by remember { mutableStateOf(false) }
  var dashboardAsk by remember { mutableStateOf(false) }
  var coverSourceDialog by remember { mutableStateOf(false) }
  var coverSourceText by remember { mutableStateOf(vm.coverSource) }
  val pickDashboard = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    if (uri != null) vm.installDashboard(uri)
  }
  LifecycleResumeEffect(Unit) {
    vm.onResume()
    onPauseOrDispose {}
  }

  val screenLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  Scaffold { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (screenLandscape) 2.dp else 6.dp)
          .clip(RoundedCornerShape(14.dp))
          .background(Brush.linearGradient(listOf(Color(0xFF064016), Color(0xFF0B170E), Color(0xFF050806))))
          .border(1.dp, Color(0xFF39FF14), RoundedCornerShape(14.dp))
          .padding(horizontal = 14.dp, vertical = if (screenLandscape) 3.dp else 10.dp),
      ) {
        Text("HakuX FSR", style = if (screenLandscape) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, color = Color.White)
        if (!screenLandscape) Text("XBOX GAME LIBRARY  •  ORGANIZADO, FUNCIONAL E TECNOLÓGICO",
          style = MaterialTheme.typography.labelSmall, color = Color(0xFF9BFF91), letterSpacing = 0.7.sp)
      }
      // In landscape the search field is hidden to free vertical space.
      // Keep the navigation/action icons in a full touch-target row.
      Row(
        Modifier.fillMaxWidth()
          .padding(start = 4.dp, end = 4.dp, top = if (screenLandscape) 0.dp else 2.dp)
          .then(if (screenLandscape) Modifier.height(48.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // the dashboard (Xbox Live's accounts): started, or installed from its zip first
        IconButton(
          onClick = { if (state.dashboard) onDashboard() else dashboardAsk = true },
          modifier = Modifier.padding(end = 4.dp),
        ) {
          Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.library_dashboard))
        }
        if (!screenLandscape) SearchField(state.query, vm::setQuery, Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        IconButton(onClick = { coverSourceText = vm.coverSource; coverSourceDialog = true }, modifier = Modifier.padding(start = 2.dp)) {
          Icon(Icons.Filled.Star, contentDescription = "Capas 3D")
        }
        // a newer release found by a check: its notes and download
        if (update != null) {
          IconButton(onClick = { updateDialog = true }, modifier = Modifier.padding(start = 4.dp)) {
            Icon(
              painterResource(R.drawable.ic_update),
              contentDescription = stringResource(R.string.update_available_icon),
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        }
        // the folders listed again, as a pull does (a controller can't pull)
        IconButton(onClick = vm::refresh, modifier = Modifier.padding(start = 4.dp)) {
          Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.library_refresh))
        }
        IconButton(onClick = onOpenSettings) {
          Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
        }
      }
      Row(
        Modifier.fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = if (screenLandscape) 2.dp else 0.dp)
          .then(if (screenLandscape) Modifier.height(48.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        listOf("grid" to "GRADE", "list" to "LISTA", "carousel" to "CARROSSEL").forEach { (mode, label) ->
          val selected = state.viewMode == mode
          TextButton(
            onClick = { vm.setViewMode(mode) },
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
              .background(if (selected) Color(0xFF18351C) else Color.Transparent)
              .border(1.dp, if (selected) Color(0xFF5EAA63) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
          ) {
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
              color = if (selected) Color(0xFF9BE69A) else MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      // The pull's indicator follows the refresh (under the loading dialog):
      // it only goes once isRefreshing has been true.
      PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = vm::refresh,
        modifier = Modifier.weight(1f).fillMaxWidth().padding(top = if (screenLandscape) 0.dp else 4.dp),
      ) {
        val games = state.visibleGames
        when {
          state.loading -> Unit
          !state.folderReady -> Message(stringResource(R.string.library_no_folder)) {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_games_folder_title)) }
          }
          games.isEmpty() -> Message(stringResource(R.string.library_empty_games))
          else -> GameList(games, state.info, state.icons, state.covers, state.viewMode, onLaunch, onGameSettings, onLoadState, onMods, vm::askConvert)
        }
      }
    }
  }

  val conversion = state.conversion
  when {
    conversion != null -> ConversionDialog(conversion, vm)
    // The refresh after a conversion shows once its dialog is closed.
    state.loading -> LoadingDialog(state.read, state.toRead)
  }
  if (updateDialog) {
    update?.let { UpdateDialog(it, onDismiss = { updateDialog = false }) }
  }
  if (coverSourceDialog) {
    AlertDialog(
      onDismissRequest = { if (!state.coverDownloadRunning) coverSourceDialog = false },
      title = { Text("CAPAS 3D PERSONALIZADAS") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Cole um link direto (ZIP, PNG, JPG ou manifesto JSON) ou uma página pública que disponibilize os arquivos.")
          OutlinedTextField(value = coverSourceText, onValueChange = { coverSourceText = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, label = { Text("Link da fonte") }, placeholder = { Text("https://...") })
          state.coverDownloadMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
          Text("Páginas que exigem login, assinatura ou CAPTCHA precisam de um link autorizado. O HakuX não contorna essas proteções.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      },
      confirmButton = { TextButton(enabled = !state.coverDownloadRunning && coverSourceText.isNotBlank(), onClick = { vm.downloadCustomCovers(coverSourceText) }) { Text(if (state.coverDownloadRunning) "BAIXANDO..." else "SALVAR E BAIXAR") } },
      dismissButton = { TextButton(enabled = !state.coverDownloadRunning, onClick = { vm.saveCoverSource(coverSourceText); coverSourceDialog = false }) { Text("SALVAR LINK") } },
    )
  }
  if (dashboardAsk) {
    ConfirmDialog(
      title = stringResource(R.string.library_dashboard_install_title),
      text = stringResource(R.string.library_dashboard_install_text),
      confirm = stringResource(R.string.library_dashboard_choose),
      onConfirm = {
        dashboardAsk = false
        pickDashboard.launch(arrayOf("application/zip", "application/x-zip-compressed"))
      },
      onDismiss = { dashboardAsk = false },
    )
  }
  state.dashboardInstall?.let { DashboardInstallDialog(it, onStart = onDashboard, onDismiss = vm::dismissDashboardInstall) }
}

/** The dashboard's install from its zip: its progress, then installed (to start now) or why not. */
@Composable
private fun DashboardInstallDialog(j: DashboardInstall, onStart: () -> Unit, onDismiss: () -> Unit) {
  val context = LocalContext.current
  AlertDialog(
    onDismissRequest = { if (j.finished) onDismiss() },
    properties = DialogProperties(dismissOnBackPress = j.finished, dismissOnClickOutside = j.finished),
    title = {
      Text(stringResource(when {
        j.error != null -> R.string.hdd_dashboard_failed
        j.finished -> R.string.hdd_dashboard_done
        else -> R.string.hdd_dashboard_installing
      }))
    },
    text = {
      when {
        j.error != null -> Text(j.error)
        j.finished -> Text(stringResource(R.string.library_dashboard_installed))
        else -> ProgressBlock(
          fractionOf(j.done, j.total),
          j.what.ifEmpty { "${Formatter.formatShortFileSize(context, j.done)} / ${Formatter.formatShortFileSize(context, j.total)}" },
        )
      }
    },
    confirmButton = {
      if (j.finished && j.error == null) {
        TextButton(onClick = { onDismiss(); onStart() }) { Text(stringResource(R.string.library_dashboard_start)) }
      } else if (j.finished) {
        TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
      }
    },
    dismissButton = {
      if (j.finished && j.error == null) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_dashboard_later)) }
      }
    },
  )
}

/**
 * A full-front Xbox case with a subtle spine and perspective. Box art always uses Fit,
 * so the complete scan (including its edges and title) remains visible.
 */
@Composable
private fun SeriesXGameCover(
  game: Game,
  cover: ImageBitmap?,
  icon: ImageBitmap?,
  modifier: Modifier = Modifier,
) {
  val density = androidx.compose.ui.platform.LocalDensity.current
  Box(
    modifier = modifier
      // The downloaded artwork already contains the complete 3D cover.
      // Do not draw an extra green plastic case behind it.
      .graphicsLayer {
        rotationY = -9f
        cameraDistance = 18f * density.density
      },
    contentAlignment = Alignment.Center,
  ) {
    if (cover != null) {
      Image(
        bitmap = cover,
        contentDescription = "${game.title} — capa 3D",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
          .shadow(9.dp, RoundedCornerShape(3.dp))
          .clip(RoundedCornerShape(3.dp)),
      )
    } else if (icon != null) {
      Image(
        bitmap = icon,
        contentDescription = "${game.title} — ícone do jogo",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().padding(8.dp),
      )
    } else {
      Text(
        game.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
        style = MaterialTheme.typography.displayMedium,
        color = Color(0xFF9BE69A),
      )
    }
  }
}

/**
 * The games, as cards. A fast scroll brings several cards in a frame, and the
 * debug build's Compose code runs interpreted (a debuggable app is never
 * compiled ahead of time), so each card is kept cheap to compose: a bordered
 * box rather than an OutlinedCard's Surface, its icon a bitmap the view model
 * decoded, the ID and the region flags one text, painters and strings shared
 * by every card, and the long-press menu composed only while it is open.
 * With a controller the focused card is outlined in green; A starts its game,
 * Y or select (or holding A) opens its menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameList(
  games: List<Game>,
  info: Map<Uri, DiscInfo>,
  icons: Map<Uri, ImageBitmap>,
  covers: Map<Uri, ImageBitmap>,
  viewMode: String,
  onLaunch: (Game) -> Unit,
  onGameSettings: (Game, DiscInfo?) -> Unit,
  onLoadState: (Game, SaveStateStore, Int) -> Unit,
  onMods: (Game, DiscInfo) -> Unit,
  onConvert: (Game) -> Unit,
) {
  var menuFor by remember { mutableStateOf<Uri?>(null) }
  var statesFor by remember { mutableStateOf<Pair<Game, SaveStateStore>?>(null) }
  val context = LocalContext.current
  val haptics = LocalHapticFeedback.current
  val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val colors = MaterialTheme.colorScheme
  val shape = remember { RoundedCornerShape(16.dp) }
  val carouselState = rememberLazyListState()
  var carouselIndex by remember { mutableStateOf(0) }
  LaunchedEffect(carouselState, games.size) {
    snapshotFlow { carouselState.firstVisibleItemIndex }.collect { carouselIndex = it }
  }
  val titleStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface)
  val idStyle = SMALL_PRINT.copy(fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)

  @Composable
  fun GameEntry(game: Game, gameIndex: Int = 0) {
    val disc = info[game.uri]
    var focused by remember { mutableStateOf(false) }
    val cardWidth = if (landscape) 270.dp else 176.dp
    val coverWidth = if (landscape) 104.dp else 82.dp
    val coverHeight = if (landscape) 90.dp else 116.dp
    val carouselOffset = gameIndex - carouselIndex
    val cardModifier = Modifier
      .then(if (viewMode == "carousel") Modifier.width(cardWidth).height(if (landscape) 245.dp else 300.dp).graphicsLayer {
        rotationY = (-carouselOffset * 24f).coerceIn(-48f, 48f)
        val depthScale = 1f - (carouselOffset.absoluteValue * 0.10f).coerceAtMost(0.30f)
        scaleX = depthScale
        scaleY = depthScale
        cameraDistance = 18f * density
      } else Modifier.fillMaxWidth())
      .clip(shape)
      .background(colors.surfaceContainerLow)
      .border(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.outlineVariant, shape)
      .onFocusChanged { focused = it.isFocused }
      .onKeyEvent { e ->
        if (e.key != Key.ButtonY && e.key != Key.Menu) return@onKeyEvent false
        if (e.type == KeyEventType.KeyUp) menuFor = game.uri
        true
      }
      .combinedClickable(
        onClick = { onLaunch(game) },
        onLongClick = {
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          menuFor = game.uri
        },
      )
    Box(cardModifier) {
      if (viewMode == "carousel") {
        // Keep the 3D artwork completely unobstructed; all labels live in a
        // dedicated footer below the cover, including on the side cards.
        Column(Modifier.fillMaxSize().background(Color(0xFF080D09))) {
          Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            SeriesXGameCover(game, covers[game.uri], icons[game.uri], Modifier.fillMaxSize())
          }
          Column(
            Modifier.fillMaxWidth()
              .background(Color(0xE908100B))
              .padding(horizontal = 10.dp, vertical = if (landscape) 7.dp else 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Text(
              game.title,
              style = if (landscape) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
              color = Color.White,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.Center,
            )
            if (disc != null && disc.titleId != 0L) {
              IdLine(disc, idStyle.copy(color = Color(0xFFB8C8B8)))
            }
            Text(
              if (gameIndex == carouselIndex) "A  •  INICIAR JOGO" else "SELECIONAR",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF9BE69A),
              modifier = Modifier.padding(top = 3.dp),
            )
          }
        }
      } else if (viewMode == "grid") {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          SeriesXGameCover(game, covers[game.uri], icons[game.uri], Modifier.width(coverWidth).height(coverHeight))
          Text(game.title, Modifier.fillMaxWidth().padding(top = 5.dp), style = MaterialTheme.typography.labelLarge.copy(color = colors.onSurface), maxLines = 2, overflow = TextOverflow.Ellipsis)
          if (disc != null && disc.titleId != 0L) IdLine(disc, idStyle)
        }
      } else {
        Row(Modifier.padding(horizontal = 12.dp, vertical = if (landscape) 6.dp else 7.dp), verticalAlignment = Alignment.CenterVertically) {
          SeriesXGameCover(game, covers[game.uri], icons[game.uri], Modifier.width(72.dp).height(72.dp))
          Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(game.title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (disc != null && disc.titleId != 0L) IdLine(disc, idStyle)
          }
        }
      }
      if (menuFor == game.uri) {
        val key = remember(game.uri, disc) { GameOverrides.gameKey(disc?.titleId ?: 0L, disc?.version ?: 0L, game.relativePath) }
        val store = remember(key) { SaveStateStore(context, key, game.uri.toString()) }
        val hasStates = remember(store) { store.saved().isNotEmpty() }
        DropdownMenu(expanded = true, onDismissRequest = { menuFor = null }) {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.library_menu_settings)) },
            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            modifier = Modifier.focusRequester(rememberFocusOnShow()),
            onClick = { menuFor = null; onGameSettings(game, disc) },
          )
          if (hasStates) DropdownMenuItem(
            text = { Text(stringResource(R.string.library_menu_load_state)) },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            onClick = { menuFor = null; statesFor = game to store },
          )
          if (disc != null && disc.titleId != 0L) DropdownMenuItem(
            text = { Text(if (ModCatalog.has(context, key)) "Mods e patches / atualizar catálogo" else "Baixar mods e patches") },
            leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
            onClick = { menuFor = null; onMods(game, disc) },
          )
          if (disc?.isFullDump == true) DropdownMenuItem(
            text = { Text(stringResource(R.string.library_menu_convert)) },
            leadingIcon = { Icon(Icons.Filled.Build, contentDescription = null) },
            onClick = { menuFor = null; onConvert(game) },
          )
        }
      }
    }
  }

  when (viewMode) {
    "carousel" -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
      if (!landscape) Text("HAKUX FSR  /  3D COVER FLOW", Modifier.padding(top = 12.dp, bottom = 10.dp), style = MaterialTheme.typography.labelLarge, color = Color(0xFF9BE69A))
      BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val carouselCardWidth = if (landscape) 270.dp else 176.dp
        val side = ((maxWidth - carouselCardWidth) / 2).coerceAtLeast(12.dp)
        LazyRow(
          state = carouselState,
          flingBehavior = rememberSnapFlingBehavior(carouselState),
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(horizontal = side, vertical = if (landscape) 2.dp else 12.dp),
          horizontalArrangement = Arrangement.spacedBy(18.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) { itemsIndexed(games, key = { _, game -> game.uri.toString() }) { index, game -> GameEntry(game, index) } }
      }
      if (games.isNotEmpty()) {
        Text(
          "DESLIZE PARA EXPLORAR  •  TOQUE PARA INICIAR  •  SEGURE PARA OPÇÕES",
          style = MaterialTheme.typography.labelSmall,
          color = colors.onSurfaceVariant,
          modifier = Modifier.padding(bottom = if (landscape) 2.dp else 14.dp),
        )
      }
    }
    "list" -> LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item { Text("TODOS OS JOGOS", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant) }
      items(games, key = { it.uri.toString() }) { GameEntry(it) }
    }
    else -> LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = if (landscape) 145.dp else 108.dp),
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        Text("TODOS OS JOGOS", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
      }
      items(games, key = { it.uri.toString() }) { GameEntry(it) }
    }
  }
  statesFor?.let { (game, store) ->
    LoadStatesDialog(
      title = game.title,
      store = store,
      onLoad = { slot -> statesFor = null; onLoadState(game, store, slot) },
      onDismiss = { statesFor = null },
    )
  }
}

/**
 * The title ID and the region flags as one text (one layout, wrapping the
 * flags onto a second line when they don't fit), read out by TalkBack as the
 * ID and the regions' labels.
 */
@Composable
private fun IdLine(disc: DiscInfo, style: TextStyle) {
  val line = remember(disc.titleId, disc.regions) {
    buildAnnotatedString {
      append(disc.titleIdText)
      for (region in REGIONS) {
        if (disc.regions and region.bit != 0) {
          append(' ')
          withStyle(FLAG_SPAN) { append(region.flag) }
        }
      }
    }
  }
  val spoken = remember(disc.titleId, disc.regions) {
    (listOf(disc.titleIdText) + REGIONS.filter { disc.regions and it.bit != 0 }.map { it.label })
      .joinToString(", ")
  }
  Text(
    line,
    style = style,
    modifier = Modifier.padding(top = 2.dp).semantics { contentDescription = spoken },
  )
}

/** Converting a dump to an XISO: the question, the progress, the outcome. */
@Composable
private fun ConversionDialog(c: Conversion, vm: LibraryViewModel) {
  val context = LocalContext.current
  val title = c.game.title
  when (c.phase) {
    Conversion.Phase.CONFIRM -> ConfirmDialog(
      title = stringResource(R.string.library_convert_title),
      text = stringResource(R.string.library_convert_message, title, c.output, XisoConverter.ORIGINALS_FOLDER),
      confirm = stringResource(R.string.library_convert_action),
      onConfirm = vm::startConversion,
      onDismiss = vm::dismissConversion,
    )
    Conversion.Phase.RUNNING -> {
      // The screen stays on: leaving the app mid-conversion risks it being stopped.
      val view = LocalView.current
      DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
      }
      AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.library_converting)) },
        text = {
          Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            ProgressBlock(
              fractionOf(c.done, c.total),
              if (c.total > 0) {
                stringResource(
                  R.string.library_convert_progress,
                  Formatter.formatShortFileSize(context, c.done),
                  Formatter.formatShortFileSize(context, c.total),
                )
              } else null,
            )
          }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = vm::cancelConversion) { Text(stringResource(android.R.string.cancel)) } },
      )
    }
    Conversion.Phase.DONE -> AlertDialog(
      onDismissRequest = vm::dismissConversion,
      title = { Text(stringResource(R.string.library_convert_done_title)) },
      text = {
        Text(
          if (c.originalMoved) {
            stringResource(R.string.library_convert_done, c.output, XisoConverter.ORIGINALS_FOLDER)
          } else {
            stringResource(R.string.library_convert_done_kept, c.output)
          },
        )
      },
      confirmButton = { TextButton(onClick = vm::dismissConversion) { Text(stringResource(android.R.string.ok)) } },
    )
    Conversion.Phase.FAILED -> AlertDialog(
      onDismissRequest = vm::dismissConversion,
      title = { Text(stringResource(R.string.library_convert_failed_title)) },
      text = { Text(c.error.orEmpty()) },
      confirmButton = { TextButton(onClick = vm::dismissConversion) { Text(stringResource(android.R.string.ok)) } },
    )
  }
}

/** The title ID's type: smaller than the theme's smallest. */
private val SMALL_PRINT = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.2.sp)

/** A region's flag, in the ID's line (IdLine). */
private val FLAG_SPAN = SpanStyle(fontSize = 14.sp)

/** The certificate's region bits as the flags they're known by. */
private class Region(val bit: Int, val flag: String, val label: String)

private val REGIONS = listOf(
  Region(DiscInfo.REGION_NA, "\uD83C\uDDFA\uD83C\uDDF8", "NTSC"),
  Region(DiscInfo.REGION_JAPAN, "\uD83C\uDDEF\uD83C\uDDF5", "NTSC-J"),
  Region(DiscInfo.REGION_REST_OF_WORLD, "\uD83C\uDDEA\uD83C\uDDFA", "PAL"),
)

/** The title image from the game's disc, or its initial while there is none. */
@Composable
private fun TitleIcon(game: Game, icon: ImageBitmap?, shape: Shape, background: Color, initialColor: Color) {
  Box(
    Modifier
      .size(60.dp)
      .clip(shape)
      .background(background),
    contentAlignment = Alignment.Center,
  ) {
    if (icon != null) {
      Image(
        icon,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Text(
        game.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
        style = MaterialTheme.typography.titleMedium,
        color = initialColor,
      )
    }
  }
}

/**
 * The search field (query: its text to start with), slimmer than an OutlinedTextField's 56 dp
 * minimum. A controller's d-pad passes through it (sideways too while it's empty) and its A opens
 * the keyboard: the focus alone doesn't, which would cover the screen on the way to the buttons
 * beside it; a tap does. It is the state-based field: the value-based one opens the keyboard on any
 * focus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
  val interaction = remember { MutableInteractionSource() }
  val shape = RoundedCornerShape(22.dp)
  val state = rememberTextFieldState(query)
  val change by rememberUpdatedState(onQueryChange)
  LaunchedEffect(state) {
    snapshotFlow { state.text.toString() }.collect { change(it) }
  }
  val focus = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  // A pressed: the keyboard on focus, which starts the field's input session now; off as it leaves
  var typing by remember { mutableStateOf(false) }
  BasicTextField(
    state = state,
    modifier = modifier
      .height(44.dp)
      .onFocusChanged { if (!it.isFocused) typing = false }
      .onPreviewKeyEvent { e ->
        val direction = when (e.key) {
          Key.DirectionUp -> FocusDirection.Up
          Key.DirectionDown -> FocusDirection.Down
          Key.DirectionLeft -> FocusDirection.Left.takeIf { state.text.isEmpty() }
          Key.DirectionRight -> FocusDirection.Right.takeIf { state.text.isEmpty() }
          Key.DirectionCenter, Key.ButtonA -> {
            if (e.type == KeyEventType.KeyUp) {
              typing = true
              keyboard?.show() // its session open already (the keyboard closed by back): shown again
            }
            return@onPreviewKeyEvent true
          }
          else -> null
        } ?: return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown) focus.moveFocus(direction)
        true
      },
    keyboardOptions = KeyboardOptions(showKeyboardOnFocus = typing),
    lineLimits = TextFieldLineLimits.SingleLine,
    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    interactionSource = interaction,
    decorator = { innerTextField -> SearchDecoration(state.text.toString(), innerTextField, interaction, shape) },
  )
}

/** The search field's outline, icon and placeholder, as an outlined text field's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchDecoration(
  query: String,
  innerTextField: @Composable () -> Unit,
  interaction: MutableInteractionSource,
  shape: Shape,
) {
  OutlinedTextFieldDefaults.DecorationBox(
    value = query,
    innerTextField = innerTextField,
    enabled = true,
    singleLine = true,
    visualTransformation = VisualTransformation.None,
    interactionSource = interaction,
    placeholder = {
      Text(stringResource(R.string.library_search_hint), style = MaterialTheme.typography.bodyMedium)
    },
    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
    contentPadding = PaddingValues(start = 4.dp, end = 16.dp),
    container = {
      OutlinedTextFieldDefaults.Container(
        enabled = true,
        isError = false,
        interactionSource = interaction,
        shape = shape,
      )
    },
  )
}

/** The library refreshing: the folder listed, then the discs not read before. */
@Composable
private fun LoadingDialog(read: Int, toRead: Int) {
  AlertDialog(
    onDismissRequest = {},
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    title = { Text(stringResource(R.string.library_loading_games)) },
    text = {
      // Listing the folder: how many discs there are isn't known yet.
      ProgressBlock(
        fractionOf(read.toLong(), toRead.toLong()),
        if (toRead > 0) stringResource(R.string.library_loading_discs, read, toRead) else null,
      )
    },
    confirmButton = {},
  )
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit = {}) {
  // Inside the pull-to-refresh box: a scrollable column so the gesture still works.
  LazyColumn(
    Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item {
      Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(320.dp),
      )
      action()
    }
  }
}
