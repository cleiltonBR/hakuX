package com.rfandango.haku_x.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared Material 3 graphics slider used by both settings and the in-game quick menu. */
@Composable
fun GraphicsSlider(
  title: String,
  valueLabel: String,
  value: Float,
  valueRange: ClosedFloatingPointRange<Float>,
  steps: Int,
  startLabel: String,
  endLabel: String,
  onValueChange: (Float) -> Unit,
) {
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(Color(0xFF101B12))
      .border(1.dp, Color(0xFF397A40), RoundedCornerShape(12.dp))
      .padding(horizontal = 14.dp, vertical = 8.dp),
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
      Text(title, style = MaterialTheme.typography.labelMedium, color = Color(0xFFB8E8B8))
      Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = Color(0xFF9BE69A))
    }
    Slider(value = value.coerceIn(valueRange), onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
      Text(startLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(endLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
