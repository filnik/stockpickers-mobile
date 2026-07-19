package app.stockpickers.kmp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stockpickers.kmp.resources.Res
import app.stockpickers.kmp.resources.ibmplexsans_bold
import app.stockpickers.kmp.resources.ibmplexsans_semibold
import app.stockpickers.kmp.resources.jetbrainsmono_bold
import app.stockpickers.kmp.resources.jetbrainsmono_regular
import app.stockpickers.kmp.resources.jetbrainsmono_semibold
import org.jetbrains.compose.resources.Font

// ---------------------------------------------------------------------------
// Palette — ported verbatim from the web client's MD3 tokens.
//
// There is NO dark theme: the web ships a single light scheme (no `dark:` classes,
// no prefers-color-scheme), so these are plain top-level constants rather than a
// light/dark pair. If a dark mode is ever designed upstream, THAT is the moment to
// turn these into a scheme — inventing one here would drift from the product.
// ---------------------------------------------------------------------------

/** Page background — a blue-tinted white, not pure #fff. */
internal val Background = Color(0xFFF9F9FF)

/** Card fill. Cards are pure white ON the tinted background; that contrast IS the card. */
internal val SurfaceCard = Color(0xFFFFFFFF)

/** Borderless tiles nested inside a card, and the segmented-control track. */
internal val SurfaceTile = Color(0xFFE7EEFF)

/** Near-black navy. Titles, tickers, the active segment fill. */
internal val Primary = Color(0xFF000E24)

/** The saturated navy used for filled controls and info tints. */
internal val PrimaryContainer = Color(0xFF022448)

internal val OnSurface = Color(0xFF001C3B)
internal val OnSurfaceVariant = Color(0xFF43474E)

/** Every border and divider in the design. Hairlines carry the structure, not shadows. */
internal val OutlineVariant = Color(0xFFC4C6CF)

// --- Semantic finance colours: a MUTED semaphore, deliberately not neon fintech. ---

internal val PositiveGreen = Color(0xFF008A3D)
internal val NegativeRed = Color(0xFFBA1A1A)
internal val WarnAmber = Color(0xFF9A6F1E)

/**
 * Green darkened for use as TEXT on [PositiveTint]. The web makes this exact
 * exception for contrast — [PositiveGreen] on the pale green chip does not clear AA.
 */
internal val PositiveOnTint = Color(0xFF00702F)

internal val PositiveTint = PositiveGreen.copy(alpha = 0.12f)
internal val NegativeTint = NegativeRed.copy(alpha = 0.12f)
internal val WarnTint = WarnAmber.copy(alpha = 0.18f)
internal val InfoTint = PrimaryContainer.copy(alpha = 0.08f)

// ---------------------------------------------------------------------------
// Type
// ---------------------------------------------------------------------------

/**
 * IBM Plex Sans — titles and the uppercase micro-labels. Only the two weights the
 * design actually uses are bundled; a weight that is never set is dead binary.
 */
@Composable
private fun plexFamily() = FontFamily(
    Font(Res.font.ibmplexsans_semibold, FontWeight.SemiBold),
    Font(Res.font.ibmplexsans_bold, FontWeight.Bold),
)

/**
 * JetBrains Mono — EVERY figure in the app: tickers, prices, scores, percentages,
 * counts. Monospaced digits are what let a column of numbers be compared by shape
 * instead of read one by one, which is the whole point of a screener.
 */
@Composable
private fun monoFamily() = FontFamily(
    Font(Res.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(Res.font.jetbrainsmono_semibold, FontWeight.SemiBold),
    Font(Res.font.jetbrainsmono_bold, FontWeight.Bold),
)

/**
 * The mono family, for the many call sites that set it per-Text. Fonts must be
 * loaded from a @Composable, so it is provided once by [StockpickersTheme] rather
 * than re-resolved at every usage.
 */
internal val LocalMonoFamily: ProvidableCompositionLocal<FontFamily> =
    staticCompositionLocalOf { FontFamily.Monospace }

/** Body text intentionally uses the PLATFORM face (Roboto / SF), not a bundled one. */
private fun typography(plex: FontFamily, mono: FontFamily) = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(
            fontFamily = plex, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
        ),
        titleLarge = titleLarge.copy(fontFamily = plex, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = plex, fontWeight = FontWeight.SemiBold),
        // Column headers and section labels: the design's signature.
        labelSmall = labelSmall.copy(
            fontFamily = plex, fontWeight = FontWeight.Bold,
            fontSize = 10.sp, letterSpacing = 0.5.sp,
        ),
        labelMedium = labelMedium.copy(fontFamily = plex, fontWeight = FontWeight.Bold),
        // Figures.
        titleSmall = titleSmall.copy(fontFamily = mono, fontWeight = FontWeight.SemiBold),
    )
}

/**
 * The uppercase 10sp micro-label sitting above every block of data — the single
 * most recognisable element of the web design, and the cheapest way to make this
 * app read as the same product.
 */
@Composable
internal fun microLabelStyle(): TextStyle = MaterialTheme.typography.labelSmall

/** Figures: mono, semibold, with the size passed by the call site. */
@Composable
internal fun monoStyle(size: Int, weight: FontWeight = FontWeight.SemiBold): TextStyle =
    TextStyle(fontFamily = LocalMonoFamily.current, fontWeight = weight, fontSize = size.sp)

/** Hairline width. One value, so every rule in the app matches. */
internal val Hairline = 1.dp

/** Structural surfaces are 8dp; interactive/status elements are fully round. */
internal val CardRadius = 8.dp

@Composable
fun StockpickersTheme(content: @Composable () -> Unit) {
    val plex = plexFamily()
    val mono = monoFamily()
    val scheme = lightColorScheme(
        primary = Primary,
        onPrimary = Color.White,
        primaryContainer = PrimaryContainer,
        onPrimaryContainer = Color.White,
        background = Background,
        onBackground = OnSurface,
        surface = Background,
        onSurface = OnSurface,
        surfaceVariant = SurfaceTile,
        onSurfaceVariant = OnSurfaceVariant,
        surfaceContainerLowest = SurfaceCard,
        surfaceContainer = SurfaceTile,
        outline = Color(0xFF74777F),
        outlineVariant = OutlineVariant,
        error = NegativeRed,
    )
    CompositionLocalProvider(LocalMonoFamily provides mono) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography(plex, mono),
            content = content,
        )
    }
}
