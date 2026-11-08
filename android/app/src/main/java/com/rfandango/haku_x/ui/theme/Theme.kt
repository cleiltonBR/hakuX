package com.rfandango.haku_x.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.RippleDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.rfandango.haku_x.data.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Xbox Series X-inspired palette: graphite surfaces with restrained Xbox green accents. */
private val HakuXColors = lightColorScheme(
  primary = Color(0xFF107C10),
  onPrimary = Color.White,
  primaryContainer = Color(0xFFD7F2D7),
  onPrimaryContainer = Color(0xFF063B08),
  secondary = Color(0xFF107C10),
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFEAF7EA),
  onSecondaryContainer = Color(0xFF063B08),
  background = Color(0xFFF4F5F4),
  onBackground = Color(0xFF1C1B1F),
  surface = Color(0xFFFAFBFA),
  onSurface = Color(0xFF1C1B1F),
  surfaceVariant = Color(0xFFE9ECE9),
  onSurfaceVariant = Color(0xFF5F6368),
  surfaceContainerLowest = Color(0xFFFFFFFF),
  surfaceContainerLow = Color(0xFFF7F8F7),
  surfaceContainer = Color(0xFFEFF1EF),
  surfaceContainerHigh = Color(0xFFE6E9E6),
  surfaceContainerHighest = Color(0xFFDDE2DD),
  outline = Color(0xFFDADCE0),
  outlineVariant = Color(0xFFE0E0E0),
  error = Color(0xFFD32F2F),
)

/** The same at night: near-black surfaces, the green lightened to read on them. */
private val HakuXDarkColors = darkColorScheme(
  primary = Color(0xFF5CC85C),
  onPrimary = Color(0xFF003A0B),
  primaryContainer = Color(0xFF174D1A),
  onPrimaryContainer = Color(0xFFD7F2D7),
  secondary = Color(0xFF5CC85C),
  onSecondary = Color(0xFF003A0B),
  secondaryContainer = Color(0xFF1B2C1D),
  onSecondaryContainer = Color(0xFFD7F2D7),
  background = Color(0xFF090B09),
  onBackground = Color(0xFFE6E6E6),
  surface = Color(0xFF101310),
  onSurface = Color(0xFFE6E6E6),
  surfaceVariant = Color(0xFF242924),
  onSurfaceVariant = Color(0xFFB4B8BC),
  surfaceContainerLowest = Color(0xFF080A08),
  surfaceContainerLow = Color(0xFF121612),
  surfaceContainer = Color(0xFF181D18),
  surfaceContainerHigh = Color(0xFF202620),
  surfaceContainerHighest = Color(0xFF293129),
  outline = Color(0xFF566056),
  outlineVariant = Color(0xFF343D34),
  error = Color(0xFFEF9A9A),
)

/**
 * The app's theme setting (AppPrefs.theme: the phone's, light or dark), held for the screens to
 * follow as it changes (the settings set it).
 */
object AppTheme {
  private val _mode = MutableStateFlow(AppPrefs.THEME_SYSTEM)
  val mode: StateFlow<String> = _mode

  fun load(context: Context) {
    _mode.value = AppPrefs(context).theme
  }

  fun set(context: Context, value: String) {
    AppPrefs(context).theme = value
    _mode.value = value
  }

  /** Dark, for the setting and the phone's own night mode (systemDark). */
  fun isDark(mode: String, systemDark: Boolean) =
    mode == AppPrefs.THEME_DARK || (mode == AppPrefs.THEME_SYSTEM && systemDark)

  /** Dark, as a view-based screen starts (the game's activity: its menus): the setting, read now. */
  fun isDark(context: Context) = isDark(
    AppPrefs(context).theme,
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
  )
}

/** Dark, as the theme setting is now (Compose: followed as it or the phone's night mode changes). */
@Composable
fun appInDarkTheme(): Boolean {
  val mode by AppTheme.mode.collectAsState()
  return AppTheme.isDark(mode, isSystemInDarkTheme())
}

/**
 * What has a controller's (or keyboard's) focus stands out: its highlight at 24 %, Material's 10 % is
 * hard to find on a handheld's screen. Presses and the rest are Material's.
 */
@OptIn(ExperimentalMaterial3Api::class)
private val FocusRipple = RippleDefaults.RippleAlpha.let {
  RippleConfiguration(
    rippleAlpha = RippleAlpha(
      draggedAlpha = it.draggedAlpha,
      focusedAlpha = 0.24f,
      hoveredAlpha = it.hoveredAlpha,
      pressedAlpha = it.pressedAlpha,
    ),
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HakuXTheme(dark: Boolean = appInDarkTheme(), content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (dark) HakuXDarkColors else HakuXColors) {
    CompositionLocalProvider(LocalRippleConfiguration provides FocusRipple, content = content)
  }
}
