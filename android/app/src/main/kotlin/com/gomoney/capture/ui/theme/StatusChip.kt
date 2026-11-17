package com.gomoney.capture.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun StatusChip(
    presentation: StatusPresentation,
    modifier: Modifier = Modifier,
) {
    val extended = CaptureThemeTokens.extendedColors
    val (bgColor, textColor) = when (presentation.tone) {
        StatusTone.Success -> extended.success.copy(alpha = 0.15f) to extended.success
        StatusTone.Warning -> extended.warning.copy(alpha = 0.15f) to extended.warning
        StatusTone.Error -> extended.error.copy(alpha = 0.15f) to extended.error
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = presentation.label,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun StatusChip(
    state: String?,
    errorCategory: String? = null,
    hasNormalizedTx: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val presentation = StatusPresentation.from(state, errorCategory, hasNormalizedTx)
    StatusChip(presentation = presentation, modifier = modifier)
}
