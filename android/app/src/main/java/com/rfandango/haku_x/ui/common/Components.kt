package com.rfandango.haku_x.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rfandango.haku_x.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The pieces the app's screens are made of, one look each: a sub-screen's
 * frame, sections and their notes, rows (switches, check boxes, actions),
 * the dialogs (a choice of one, a confirmation), progress, and the first-run
 * pages.
 */

/**
 * A screen under another: its top bar (the title, a subtitle such as the game's name under it, the
 * back button, actions) and a snackbar host for its notices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
  title: String,
  onBack: () -> Unit,
  subtitle: String? = null,
  snackbar: SnackbarHostState? = null,
  actions: @Composable RowScope.() -> Unit = {},
  content: @Composable (PaddingValues) -> Unit,
) {
  Scaffold(
    snackbarHost = { snackbar?.let { SnackbarHost(it) } },
    topBar = {
      TopAppBar(
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
          titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        title = {
          Column {
            Text("HAKUX FSR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
              Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
          }
        },
        actions = actions,
      )
    },
    content = content,
  )
}

/** A screen's notices (a view model's, say) shown as they come; onShown clears the one shown. */
@Composable
fun NoticeEffect(snackbar: SnackbarHostState, notice: String?, onShown: () -> Unit) {
  LaunchedEffect(notice) {
    // cleared once shown: clearing it first changes the key, which cancels the snackbar
    snackbar.showSnackbar(notice ?: return@LaunchedEffect)
    onShown()
  }
}

/** A notice now, in place of the one showing (a job's outcome after its "working" notice). */
fun SnackbarHostState.notify(scope: CoroutineScope, text: String) {
  currentSnackbarData?.dismiss()
  scope.launch { showSnackbar(text) }
}

/** A group's title in a list of settings. */
@Composable
fun Section(title: String) {
  Text(
    title,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 3.dp),
  )
}

/** A screen's or a section's explanation, under its title. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
  )
}

/** A list with nothing in it, in its place. */
@Composable
fun EmptyText(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
  )
}

/** An on/off setting: the whole row toggles it. */
@Composable
fun SwitchItem(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = summary?.let { { Text(it) } },
    trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    modifier = Modifier
      .padding(horizontal = 6.dp, vertical = 1.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerLow)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
      .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
  )
}

/** A check box with its label (and a note under it): the whole row toggles it. */
@Composable
fun CheckItem(label: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 3.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerLow)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
      .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
    Column {
      Text(label)
      note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
  }
}

/** A row that does something (adds, imports, exports): its icon first. */
@Composable
fun ActionItem(title: String, summary: String?, icon: ImageVector?, enabled: Boolean = true, onClick: () -> Unit) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = summary?.let { { Text(it) } },
    leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
    modifier = Modifier
      .padding(horizontal = 12.dp, vertical = 3.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerLow)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
      .clickable(enabled = enabled, onClick = onClick),
  )
}

/** A row's delete button. */
@Composable
fun DeleteButton(onClick: () -> Unit, enabled: Boolean = true, description: String = stringResource(R.string.common_delete)) {
  IconButton(onClick = onClick, enabled = enabled) {
    Icon(Icons.Filled.Delete, contentDescription = description)
  }
}

/**
 * What takes the focus as it shows (a dialog's choice, a menu's first item; its element's
 * Modifier.focusRequester), with a controller or keyboard in use, so their keys act on it at once.
 * With touch nothing is focused.
 */
@Composable
fun rememberFocusOnShow(): FocusRequester {
  val requester = remember { FocusRequester() }
  val keys = LocalInputModeManager.current.inputMode == InputMode.Keyboard
  LaunchedEffect(requester) {
    // its element may not be there (no choice is the current one)
    if (keys) runCatching { requester.requestFocus() }
  }
  return requester
}

/** A choice of one, as radio buttons (the current one focused for a controller). */
@Composable
fun <T> ChoiceDialog(
  title: String,
  options: List<Pair<T, String>>,
  current: T,
  onPick: (T) -> Unit,
  onDismiss: () -> Unit,
) {
  val shown = rememberFocusOnShow()
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        for ((value, label) in options) {
          Row(
            Modifier
              .fillMaxWidth()
              .then(if (value == current) Modifier.focusRequester(shown) else Modifier)
              .selectable(selected = value == current, role = Role.RadioButton) { onPick(value) }
              .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = value == current, onClick = null)
            Text(label, modifier = Modifier.padding(start = 12.dp))
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
  )
}

/**
 * A question before something is done: its button names what (Delete, Remove, Convert…); the other
 * button cancels (or says dismiss: Not now), and has a controller's focus first: a second press of A
 * doesn't delete.
 */
@Composable
fun ConfirmDialog(
  title: String,
  text: String,
  confirm: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  dismiss: String = stringResource(android.R.string.cancel),
) {
  val shown = rememberFocusOnShow()
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(text) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
    dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(shown)) { Text(dismiss) } },
  )
}

/**
 * A long job's progress: the bar (moving, while how much there is isn't known: fraction null) and a
 * line under it saying what it is at.
 */
@Composable
fun ProgressBlock(fraction: Float?, caption: String?, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxWidth()) {
    if (fraction != null) {
      LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    } else {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    if (!caption.isNullOrEmpty()) {
      Spacer(Modifier.height(8.dp))
      Text(
        caption,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** done of total as ProgressBlock's fraction: null while the total isn't known. */
fun fractionOf(done: Long, total: Long): Float? = if (total > 0) done.toFloat() / total else null

/** A first-run page (the setup, the migration): its title over its content, centred and scrolled. */
@Composable
fun OnboardingPage(title: String, content: @Composable ColumnScope.() -> Unit) {
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(
      Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
      }
    }
  }
}

/** A first-run page's lead paragraph, under its title. */
@Composable
fun OnboardingLead(text: String) {
  Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One thing a first-run page asks about or reports: its title and what it says. */
@Composable
fun OnboardingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
  OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(title, style = MaterialTheme.typography.titleLarge)
      content()
    }
  }
}

/** A first-run card's smaller print. */
@Composable
fun OnboardingNote(text: String) {
  Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The corners of a thumbnail (a title's image, a savestate's frame). */
val ThumbnailShape = RoundedCornerShape(8.dp)
