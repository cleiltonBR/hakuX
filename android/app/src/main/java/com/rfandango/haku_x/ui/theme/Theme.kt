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

/** hakuX's look: white surfaces and the Xbox-era green, as the app had before Compose. */
private val HakuXColors = lightColorScheme(
  primary = Color(0xFF2E7D32),
  onPrimary = Color.White,
  primaryContainer = Color(0xFFC8E6C9),
  onPrimaryContainer = Color(0xFF1B5E20),
  secondary = Color(0xFF2E7D32),
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFE8F5E9),
  onSecondaryContainer = Color(0xFF1B5E20),
  background = Color.White,
  onBackground = Color(0xFF1C1B1F),
  surface = Color.White,
  onSurface = Color(0xFF1C1B1F),
  surfaceVariant = Color(0xFFF5F5F5),
  onSurfaceVariant = Color(0xFF5F6368),
  surfaceContainerLowest = Color.White,
  surfaceContainerLow = Color(0xFFFAFAFA),
  surfaceContainer = Color(0xFFF5F5F5),
  surfaceContainerHigh = Color(0xFFEEEEEE),
  surfaceContainerHighest = Color(0xFFE8E8E8),
  outline = Color(0xFFDADCE0),
  outlineVariant = Color(0xFFE0E0E0),
  error = Color(0xFFD32F2F),
)

/** The same at night: near-black surfaces, the green lightened to read on them. */
private val HakuXDarkColors = darkColorScheme(
  primary = Color(0xFF81C784),
  onPrimary = Color(0xFF003A0B),
  primaryContainer = Color(0xFF1B5E20),
  onPrimaryContainer = Color(0xFFC8E6C9),
  secondary = Color(0xFF81C784),
  onSecondary = Color(0xFF003A0B),
  secondaryContainer = Color(0xFF23392A),
  onSecondaryContainer = Color(0xFFC8E6C9),
  background = Color(0xFF121212),
  onBackground = Color(0xFFE6E6E6),
  surface = Color(0xFF121212),
  onSurface = Color(0xFFE6E6E6),
  surfaceVariant = Color(0xFF2B2B2B),
  onSurfaceVariant = Color(0xFFB4B8BC),
  surfaceContainerLowest = Color(0xFF0E0E0E),
  surfaceContainerLow = Color(0xFF1A1A1A),
  surfaceContainer = Color(0xFF1F1F1F),
  surfaceContainerHigh = Color(0xFF262626),
  surfaceContainerHighest = Color(0xFF2F2F2F),
  outline = Color(0xFF6E7378),
  outlineVariant = Color(0xFF3A3A3A),
  error = Color(0xFFEF9A9A),
)

/**
 * The app's theme setting (AppPrefs.theme: the phone's, light or dark), held for the screens to
 * follow as it changes (the settings set it).
 */
object AppTheme {
  private val _mode = MutableStateFlow(AppPrefs.THEME_DARK)
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
