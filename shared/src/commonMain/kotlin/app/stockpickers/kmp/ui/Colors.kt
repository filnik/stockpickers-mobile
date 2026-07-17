package app.stockpickers.kmp.ui

import androidx.compose.ui.graphics.Color

/**
 * Semantic colours for signed financial figures.
 *
 * Not in the Material 3 scheme on purpose: `colorScheme.error` means "something
 * went wrong", while a negative momentum is a perfectly valid reading. Shared by
 * the leaders board and the detail screen so a given sign always looks the same.
 */
internal val PositiveGreen = Color(0xFF1B873B)
internal val NegativeRed = Color(0xFFD32F2F)
