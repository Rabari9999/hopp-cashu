package space.sovereigncast.hopp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.figtree_bold
import space.sovereigncast.hopp.resources.figtree_extrabold
import space.sovereigncast.hopp.resources.figtree_medium
import space.sovereigncast.hopp.resources.figtree_regular
import space.sovereigncast.hopp.resources.figtree_semibold

/** Figtree everywhere (ADR 0019), the wordmark's typeface. Static weights
 *  rather than the variable font: identical rendering on Android and iOS. */
@Composable
fun figtree(): FontFamily = FontFamily(
    Font(Res.font.figtree_regular, FontWeight.Normal),
    Font(Res.font.figtree_medium, FontWeight.Medium),
    Font(Res.font.figtree_semibold, FontWeight.SemiBold),
    Font(Res.font.figtree_bold, FontWeight.Bold),
    Font(Res.font.figtree_extrabold, FontWeight.ExtraBold),
)

/**
 * Built once per font family, not on every call: a new Typography gives
 * every text in the app a new style, and with it a new layout. Unremembered,
 * that happened each time the app frame recomposed — on every navigation,
 * every time now playing opened or closed — and made those animations stutter
 * (device feedback 2026-10-08).
 */
@Composable
internal fun hoppTypography(): Typography {
    val regular = Font(Res.font.figtree_regular, FontWeight.Normal)
    val medium = Font(Res.font.figtree_medium, FontWeight.Medium)
    val semibold = Font(Res.font.figtree_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.figtree_bold, FontWeight.Bold)
    val extrabold = Font(Res.font.figtree_extrabold, FontWeight.ExtraBold)
    return remember(regular, medium, semibold, bold, extrabold) {
        buildTypography(FontFamily(regular, medium, semibold, bold, extrabold))
    }
}

private fun buildTypography(family: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family, fontWeight = FontWeight.ExtraBold),
        displayMedium = base.displayMedium.copy(fontFamily = family, fontWeight = FontWeight.ExtraBold),
        displaySmall = base.displaySmall.copy(fontFamily = family, fontWeight = FontWeight.ExtraBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = family, fontWeight = FontWeight.ExtraBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.ExtraBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
    )
}
